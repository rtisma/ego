package bio.overture.ego.security;

import static bio.overture.ego.model.enums.ProviderType.*;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Objects.isNull;

import bio.overture.ego.model.enums.ProviderType;
import bio.overture.ego.model.exceptions.InternalServerException;
import bio.overture.ego.model.exceptions.NoPrimaryEmailException;
import bio.overture.ego.service.ApplicationService;
import bio.overture.ego.service.GithubService;
import bio.overture.ego.service.LinkedinService;
import bio.overture.ego.service.OrcidService;
import bio.overture.ego.utils.Redirects;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import javax.servlet.Filter;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.http.client.utils.URIBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2ClientContext;
import org.springframework.security.oauth2.client.OAuth2RestOperations;
import org.springframework.security.oauth2.client.OAuth2RestTemplate;
import org.springframework.security.oauth2.client.filter.OAuth2ClientAuthenticationProcessingFilter;
import org.springframework.security.oauth2.common.exceptions.UnauthorizedClientException;
import org.springframework.security.oauth2.common.exceptions.UserDeniedAuthorizationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.filter.CompositeFilter;

@Slf4j
@Component
@Profile("auth")
public class OAuth2SsoFilter extends CompositeFilter {

  private OAuth2ClientContext oauth2ClientContext;
  private ApplicationService applicationService;
  private SimpleUrlAuthenticationSuccessHandler simpleUrlAuthenticationSuccessHandler =
      new SimpleUrlAuthenticationSuccessHandler() {
        public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
          val application =
              applicationService.getByClientId(
                  (String) request.getSession().getAttribute("ego_client_id"));

          String redirectUri = (String) request.getSession().getAttribute("ego_redirect_uri");

          val redirect = Redirects.getRedirectUri(application, redirectUri);
          if (!redirect.isEmpty()) {
            this.setDefaultTargetUrl(redirect);
            super.onAuthenticationSuccess(request, response, authentication);
          } else {
            throw new UnauthorizedClientException("Incorrect redirect uri for ego client.");
          }
        }
      };

  private OrcidService orcidService;
  private GithubService githubService;
  private LinkedinService linkedinService;

  private AuthenticationFailureHandler failureHandler =
      (request, response, exception) -> {
        val rootExceptionThrowable = exception.getCause();
        val application =
            applicationService.getByClientId(
                (String) request.getSession().getAttribute("ego_client_id"));
        String errorRedirectUri =
            (String) request.getSession().getAttribute("ego_error_redirect_uri");

        val errorRedirect = Redirects.getErrorRedirectUri(application, errorRedirectUri);
        URIBuilder errorUri;

        if (rootExceptionThrowable instanceof NoPrimaryEmailException) {
          try {
            val reqUri = new ArrayList<String>(asList(request.getRequestURI().split("/")));
            val provider = reqUri.get(reqUri.size() - 1).toUpperCase();
            errorUri = new URIBuilder(errorRedirect);
            errorUri.addParameter("error_code", "403");
            errorUri.addParameter("error_type", rootExceptionThrowable.getMessage());
            try {
              ProviderType.resolveProviderType(provider);
              errorUri.addParameter("provider_type", provider);
            } catch (IllegalArgumentException e) {
              log.warn(format("Invalid provider: '%s'", provider));
            }
            response.setStatus(403);
            response.sendRedirect(errorUri.build().toString());
          } catch (URISyntaxException e) {
            log.error("Invalid redirect uri");
          }
        } else if (rootExceptionThrowable instanceof UserDeniedAuthorizationException) {
          // User denies Ego access/cancels login
          try {
            errorUri = new URIBuilder(errorRedirect);
            errorUri.addParameter("error_code", "403");
            // setting explicitly to "access_denied" because LinkedIn error_code differs from other
            // providers
            errorUri.addParameter("error_type", "access_denied");
            response.setStatus(403);
            response.sendRedirect(errorUri.build().toString());
          } catch (URISyntaxException e) {
            log.warn("Invalid redirect uri");
          }
        } else {
          throw new InternalServerException("Invalid error from OAuth Service.");
        }
      };

  @Autowired
  public OAuth2SsoFilter(
      @Qualifier("oauth2ClientContext") OAuth2ClientContext oauth2ClientContext,
      ApplicationService applicationService,
      OAuth2ClientResources google,
      OAuth2ClientResources facebook,
      OAuth2ClientResources github,
      OAuth2ClientResources linkedin,
      OAuth2ClientResources orcid,
      OrcidService orcidService,
      GithubService githubService,
      LinkedinService linkedinService) {
    this.oauth2ClientContext = oauth2ClientContext;
    this.applicationService = applicationService;
    this.orcidService = orcidService;
    this.githubService = githubService;
    this.linkedinService = linkedinService;

    val filters = new ArrayList<Filter>();

    filters.add(new GoogleFilter(google));
    filters.add(new FacebookFilter(facebook));
    filters.add(new GithubFilter(github));
    filters.add(new LinkedInFilter(linkedin));
    filters.add(new OrcidFilter(orcid));
    setFilters(filters);
  }

  class OAuth2SsoChildFilter extends OAuth2ClientAuthenticationProcessingFilter {
    public OAuth2SsoChildFilter(String path, OAuth2ClientResources client) {
      super(path);
      OAuth2RestTemplate template = new OAuth2RestTemplate(client.getClient(), oauth2ClientContext);
      super.setRestTemplate(template);
      super.setAuthenticationSuccessHandler(simpleUrlAuthenticationSuccessHandler);
      super.setAuthenticationFailureHandler(failureHandler);
    }

    @Override
    public Authentication attemptAuthentication(
        HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException {
      // Don't use the existing access token, otherwise, it would attempt to get github user info
      // with linkedin access token
      this.restTemplate.getOAuth2ClientContext().setAccessToken(null);
      return super.attemptAuthentication(request, response);
    }
  }

  class GithubFilter extends OAuth2SsoChildFilter {
    public GithubFilter(OAuth2ClientResources client) {
      super("/oauth/login/github", client);
      super.setTokenServices(
          new OAuth2UserInfoTokenServices(
              client.getResource().getUserInfoUri(),
              client.getClient().getClientId(),
              super.restTemplate,
              GITHUB) {
            @Override
            protected Map<String, Object> transformMap(Map<String, Object> map, String accessToken)
                throws NoSuchElementException {
              OAuth2RestOperations restTemplate = getRestTemplate(accessToken);
              String email;

              try {
                email = githubService.getVerifiedEmail(restTemplate);
              } catch (RestClientException | ClassCastException ex) {
                return Collections.singletonMap("error", "Could not fetch user details");
              }

              if (!isNull(email)) {
                map.put("email", email);

                String name = (String) map.get("name");
                // github allows the name field to be null
                if (!isNull(name)) {
                  githubService.parseName(name, map);
                }

                return map;
              } else {
                return Collections.singletonMap("error", "Could not fetch user details");
              }
            }
          });
    }
  }

  class LinkedInFilter extends OAuth2SsoChildFilter {
    public LinkedInFilter(OAuth2ClientResources client) {
      super("/oauth/login/linkedin", client);
      super.setTokenServices(
          new OAuth2UserInfoTokenServices(
              client.getResource().getUserInfoUri(),
              client.getClient().getClientId(),
              super.restTemplate,
              LINKEDIN) {
            @Override
            protected Map<String, Object> transformMap(
                Map<String, Object> map, String accessToken) {
              val restTemplate = getRestTemplate(accessToken);
              return linkedinService.getPrimaryEmail(restTemplate, map);
            }
          });
    }
  }

  class GoogleFilter extends OAuth2SsoChildFilter {
    public GoogleFilter(OAuth2ClientResources client) {
      super("/oauth/login/google", client);
      super.setTokenServices(
          new OAuth2UserInfoTokenServices(
              client.getResource().getUserInfoUri(),
              client.getClient().getClientId(),
              super.restTemplate,
              GOOGLE));
    }
  }

  class FacebookFilter extends OAuth2SsoChildFilter {
    public FacebookFilter(OAuth2ClientResources client) {
      super("/oauth/login/facebook", client);
      super.setTokenServices(
          new OAuth2UserInfoTokenServices(
              client.getResource().getUserInfoUri(),
              client.getClient().getClientId(),
              super.restTemplate,
              FACEBOOK));
    }
  }

  class OrcidFilter extends OAuth2SsoChildFilter {
    public OrcidFilter(OAuth2ClientResources client) {
      super("/oauth/login/orcid", client);
      super.setTokenServices(
          new OAuth2UserInfoTokenServices(
              client.getResource().getUserInfoUri(),
              client.getClient().getClientId(),
              super.restTemplate,
              ORCID) {
            @Override
            protected Map<String, Object> transformMap(
                Map<String, Object> map, String accessToken) {
              val orcid = map.get("sub").toString();
              val restTemplate = getRestTemplate(accessToken);
              return orcidService.getPrimaryEmail(restTemplate, orcid, map);
            }
          });
    }
  }
}
