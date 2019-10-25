/*
 * Copyright (c) 2017. The Ontario Institute for Cancer Research. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bio.overture.ego.service;

import static bio.overture.ego.model.dto.Scope.effectiveScopes;
import static bio.overture.ego.model.dto.Scope.explicitScopes;
import static bio.overture.ego.model.enums.ApplicationType.ADMIN;
import static bio.overture.ego.service.UserService.extractScopes;
import static bio.overture.ego.utils.CollectionUtils.mapToSet;
import static bio.overture.ego.utils.TypeUtils.convertToAnotherType;
import static java.lang.String.format;
import static java.util.UUID.fromString;
import static org.springframework.util.DigestUtils.md5Digest;

import bio.overture.ego.model.dto.ApiKeyResponse;
import bio.overture.ego.model.dto.ApiKeyScopeResponse;
import bio.overture.ego.model.dto.Scope;
import bio.overture.ego.model.dto.UserScopesResponse;
import bio.overture.ego.model.entity.Application;
import bio.overture.ego.model.entity.Token;
import bio.overture.ego.model.entity.User;
import bio.overture.ego.model.exceptions.ForbiddenException;
import bio.overture.ego.model.params.ScopeName;
import bio.overture.ego.repository.TokenStoreRepository;
import bio.overture.ego.security.BasicAuthToken;
import bio.overture.ego.token.IDToken;
import bio.overture.ego.token.TokenClaims;
import bio.overture.ego.token.app.AppJWTAccessToken;
import bio.overture.ego.token.app.AppTokenClaims;
import bio.overture.ego.token.app.AppTokenContext;
import bio.overture.ego.token.signer.TokenSigner;
import bio.overture.ego.token.user.UserJWTAccessToken;
import bio.overture.ego.token.user.UserTokenClaims;
import bio.overture.ego.token.user.UserTokenContext;
import bio.overture.ego.view.Views;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.common.exceptions.InvalidRequestException;
import org.springframework.security.oauth2.common.exceptions.InvalidScopeException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TokenService extends AbstractNamedService<Token, UUID> {

  /*
   * Constant
   */
  private static final String ISSUER_NAME = "ego";

  /*
   * Dependencies
   */
  private TokenSigner tokenSigner;
  private UserService userService;
  private ApplicationService applicationService;
  private ApiKeyStoreService apiKeyStoreService;
  private PolicyService policyService;

  /** Configuration */
  @Value("${jwt.duration:86400000}")
  private int DURATION;

  @Value("${apitoken.duration:365}")
  private int API_TOKEN_DURATION;

  public TokenService(
      @NonNull TokenSigner tokenSigner,
      @NonNull UserService userService,
      @NonNull ApplicationService applicationService,
      @NonNull ApiKeyStoreService apiKeyStoreService,
      @NonNull PolicyService policyService,
      @NonNull TokenStoreRepository tokenStoreRepository) {
    super(Token.class, tokenStoreRepository);
    this.tokenSigner = tokenSigner;
    this.userService = userService;
    this.applicationService = applicationService;
    this.apiKeyStoreService = apiKeyStoreService;
    this.policyService = policyService;
  }

  @Override
  public Token getWithRelationships(@NonNull UUID id) {
    return apiKeyStoreService.getWithRelationships(id);
  }

  public String generateUserToken(IDToken idToken) {
    val user = userService.getUserByToken(idToken);
    return generateUserToken(user);
  }

  public String updateUserToken(String accessToken) {
    Jws<Claims> decodedToken = validateAndReturn(accessToken);

    val expiration = decodedToken.getBody().getExpiration().getTime();
    val currentTime = Instant.now().toEpochMilli();

    val userId = decodedToken.getBody().getSubject();
    val user = userService.getById(UUID.fromString(userId));

    Set<String> scope = mapToSet(extractScopes(user), Scope::toString);
    val tokenClaims = generateUserTokenClaims(user, scope);
    tokenClaims.setValidDuration((int) (expiration - currentTime));

    return getSignedToken(tokenClaims);
  }

  @SneakyThrows
  public String generateUserToken(User u) {
    Set<String> permissionNames = mapToSet(extractScopes(u), Scope::toString);
    return generateUserToken(u, permissionNames);
  }

  public Set<Scope> getScopes(Set<ScopeName> scopeNames) {
    return mapToSet(scopeNames, this::getScope);
  }

  public Scope getScope(ScopeName name) {
    val policy = policyService.getByName(name.getName());

    return new Scope(policy, name.getAccessLevel());
  }

  public Set<Scope> missingScopes(String userName, Set<ScopeName> scopeNames) {
    val user = userService.getByName(userName);
    val userScopes = extractScopes(user);
    val requestedScopes = getScopes(scopeNames);
    return Scope.missingScopes(userScopes, requestedScopes);
  }

  public String str(Object o) {
    if (o == null) {
      return "null";
    } else {
      return "'" + o.toString() + "'";
    }
  }

  public String strList(Collection collection) {
    if (collection == null) {
      return "null";
    }
    val l = new ArrayList(collection);
    return l.toString();
  }

  @SneakyThrows
  public Token issueApiKey(UUID user_id, List<ScopeName> scopeNames, String description) {
    log.info(format("Looking for user '%s'", str(user_id)));
    log.info(format("Scopes are '%s'", strList(scopeNames)));
    log.info(format("Token description is '%s'", description));

    val u =
        userService
            .findById(user_id)
            .orElseThrow(
                () -> new UsernameNotFoundException(format("Can't find user '%s'", str(user_id))));

    log.info(format("Got user with id '%s'", str(u.getId())));
    val userScopes = extractScopes(u);

    log.info(format("User's scopes are '%s'", str(userScopes)));

    val requestedScopes = getScopes(new HashSet<>(scopeNames));

    val missingScopes = Scope.missingScopes(userScopes, requestedScopes);
    if (!missingScopes.isEmpty()) {
      val msg = format("User %s has no access to scopes [%s]", str(user_id), str(missingScopes));
      log.info(msg);
      throw new InvalidScopeException(msg);
    }

    val tokenString = generateTokenString();
    log.info(format("Generated token string '%s'", str(tokenString)));

    val cal = Calendar.getInstance();
    cal.add(Calendar.DAY_OF_YEAR, API_TOKEN_DURATION);
    val expiryDate = cal.getTime();

    val today = Calendar.getInstance();

    val token = new Token();
    token.setExpiryDate(expiryDate);
    token.setIssueDate(today.getTime());
    token.setRevoked(false);
    token.setName(tokenString);
    token.setOwner(u);
    token.setDescription(description);

    for (Scope requestedScope : requestedScopes) {
      token.addScope(requestedScope);
    }

    log.info("Creating token in token store");
    apiKeyStoreService.create(token);

    log.info(format("Returning '%s'", str(token)));

    return token;
  }

  public Optional<Token> findByTokenString(String token) {
    return apiKeyStoreService.findByTokenName(token);
  }

  public String generateTokenString() {
    return UUID.randomUUID().toString();
  }

  public String generateUserToken(@NonNull User u, @NonNull Set<String> scope) {
    val tokenClaims = generateUserTokenClaims(u, scope);
    return getSignedToken(tokenClaims);
  }

  public UserTokenClaims generateUserTokenClaims(@NonNull User u, @NonNull Set<String> scope) {
    val tokenContext = new UserTokenContext(u);
    tokenContext.setScope(scope);
    val tokenClaims = new UserTokenClaims();
    tokenClaims.setIss(ISSUER_NAME);
    tokenClaims.setValidDuration(DURATION);
    tokenClaims.setContext(tokenContext);

    return tokenClaims;
  }

  @SneakyThrows
  public String generateAppToken(Application application) {
    val tokenContext = new AppTokenContext(application);
    val tokenClaims = new AppTokenClaims();
    tokenClaims.setIss(ISSUER_NAME);
    tokenClaims.setValidDuration(DURATION);
    tokenClaims.setContext(tokenContext);
    return getSignedToken(tokenClaims);
  }

  public boolean isValidToken(String token) {
    Jws<Claims> decodedToken = null;
    try {
      decodedToken = Jwts.parser().setSigningKey(tokenSigner.getKey().get()).parseClaimsJws(token);
    } catch (JwtException e) {
      log.error("JWT token is invalid", e);
    }
    return (decodedToken != null);
  }

  public Jws<Claims> validateAndReturn(String token) {
    Jws<Claims> decodedToken = null;
    try {
      decodedToken = Jwts.parser().setSigningKey(tokenSigner.getKey().get()).parseClaimsJws(token);
    } catch (JwtException e) {
      log.error("JWT token is invalid", e);
      throw new ForbiddenException("Authorization is required for this action.");
    }
    if (decodedToken == null) {
      log.error("JWT token was null when trying to validate and return.");
      throw new ForbiddenException("Authorization is required for this action.");
    }

    return decodedToken;
  }

  public User getTokenUserInfo(String token) {
    try {
      val body = getTokenClaims(token);
      val tokenClaims =
          convertToAnotherType(body, UserTokenClaims.class, Views.JWTAccessToken.class);
      return userService.getById(fromString(tokenClaims.getSub()));
    } catch (JwtException | ClassCastException | IOException e) {
      log.error("Issue handling user token (MD5sum) {}", new String(md5Digest(token.getBytes())));
      return null;
    }
  }

  public Application getTokenAppInfo(String token) {
    try {
      val body = getTokenClaims(token);
      val tokenClaims =
          convertToAnotherType(body, AppTokenClaims.class, Views.JWTAccessToken.class);
      return applicationService.getById(fromString(tokenClaims.getSub()));
    } catch (JwtException | ClassCastException | IOException e) {
      log.error(
          "Issue handling application token (MD5sum) {}", new String(md5Digest(token.getBytes())));
      return null;
    }
  }

  @SneakyThrows
  public Claims getTokenClaims(String token) {
    if (tokenSigner.getKey().isPresent()) {
      return Jwts.parser()
          .setSigningKey(tokenSigner.getKey().get())
          .parseClaimsJws(token)
          .getBody();
    } else {
      throw new InvalidKeyException("Invalid signing key for the token.");
    }
  }

  public UserJWTAccessToken getUserAccessToken(String token) {
    return new UserJWTAccessToken(token, this);
  }

  public AppJWTAccessToken getAppAccessToken(String token) {
    return new AppJWTAccessToken(token, this);
  }

  @SneakyThrows
  private String getSignedToken(TokenClaims claims) {
    if (tokenSigner.getKey().isPresent()) {
      return Jwts.builder()
          .setClaims(convertToAnotherType(claims, Map.class, Views.JWTAccessToken.class))
          .signWith(SignatureAlgorithm.RS256, tokenSigner.getKey().get())
          .compact();
    } else {
      throw new InvalidKeyException("Invalid signing key for the token.");
    }
  }

  @SneakyThrows
  public ApiKeyScopeResponse checkToken(String authToken, String token) {
    if (token == null) {
      log.debug("Null token");
      throw new InvalidTokenException("No token field found in POST request");
    }

    log.debug(format("token ='%s'", token));
    val contents = BasicAuthToken.decode(authToken);

    val clientId = contents.get().getClientId();
    val application = applicationService.findByClientId(clientId);

    val t =
        findByTokenString(token).orElseThrow(() -> new InvalidTokenException("Token not found"));

    if (t.isRevoked())
      throw new InvalidTokenException(
          format("Token \"%s\" has expired or is no longer valid. ", token));

    // We want to limit the scopes listed in the token to those scopes that the user
    // is allowed to access at the time the token is checked -- we don't assume that
    // they have not changed since the token was issued.

    val owner = t.getOwner();
    val scopes = explicitScopes(effectiveScopes(extractScopes(owner), t.scopes()));
    val names = mapToSet(scopes, Scope::toString);

    return new ApiKeyScopeResponse(owner.getName(), clientId, t.getSecondsUntilExpiry(), names);
  }

  public UserScopesResponse userScopes(@NonNull String userName) {
    val user = userService.getByName(userName);
    val scopes = extractScopes(user);
    val names = mapToSet(scopes, Scope::toString);

    return new UserScopesResponse(names);
  }

  public void revokeToken(@NonNull String tokenName) {
    validateTokenName(tokenName);
    val principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

    if (principal instanceof User) {
      revokeTokenAsUser(tokenName, (User) principal);
    } else if (principal instanceof Application) {
      revokeTokenAsApplication(tokenName, (Application) principal);
    } else {
      log.info("Unknown type of authentication, token is not allowed to be revoked.");
      throw new InvalidRequestException("Unknown type of authentication.");
    }
  }

  private void revokeTokenAsUser(String tokenName, User user) {
    if (userService.isAdmin(user) && userService.isActiveUser(user)) {
      revoke(tokenName);
    } else {
      // if it's a regular user, check if the token belongs to the user
      verifyToken(tokenName, user.getId());
      revoke(tokenName);
    }
  }

  private void revokeTokenAsApplication(String tokenName, Application application) {
    if (application.getType() == ADMIN) {
      revoke(tokenName);
    } else {
      throw new InvalidRequestException(
          format("The application does not have permission to revoke token '%s'", tokenName));
    }
  }

  private void verifyToken(String token, UUID userId) {
    val currentToken =
        findByTokenString(token).orElseThrow(() -> new InvalidTokenException("Token not found."));

    if (!currentToken.getOwner().getId().equals(userId)) {
      throw new InvalidTokenException("Users can only revoke tokens that belong to them.");
    }
  }

  private void validateTokenName(@NonNull String tokenName) {
    log.info(format("Validating token: '%s'.", tokenName));

    if (tokenName.isEmpty()) {
      throw new InvalidTokenException("Token cannot be empty.");
    }

    if (tokenName.length() > 2048) {
      throw new InvalidRequestException("Invalid token, the maximum length for a token is 2048.");
    }
  }

  public void revoke(String token) {
    val currentToken =
        findByTokenString(token).orElseThrow(() -> new InvalidTokenException("Token not found."));
    if (currentToken.isRevoked()) {
      throw new InvalidTokenException(format("Token '%s' is already revoked.", token));
    }
    currentToken.setRevoked(true);
    getRepository().save(currentToken);
  }

  public List<ApiKeyResponse> listToken(@NonNull UUID userId) {
    val user =
        userService
            .findById(userId)
            .orElseThrow(
                () -> new UsernameNotFoundException(format("Can't find user '%s'", str(userId))));

    val tokens = user.getTokens();
    if (tokens.isEmpty()) {
      return new ArrayList<>();
    }

    val unrevokedTokens =
        tokens.stream().filter((token -> !token.isRevoked())).collect(Collectors.toSet());
    List<ApiKeyResponse> response = new ArrayList<>();
    unrevokedTokens.forEach(
        token -> {
          createTokenResponse(token, response);
        });

    return response;
  }

  private void createTokenResponse(@NonNull Token token, @NonNull List<ApiKeyResponse> responses) {
    val scopes = mapToSet(token.scopes(), Scope::toString);
    responses.add(
        ApiKeyResponse.builder()
            .apiKey(token.getName())
            .scope(scopes)
            .exp(token.getSecondsUntilExpiry())
            .description(token.getDescription())
            .build());
  }
}
