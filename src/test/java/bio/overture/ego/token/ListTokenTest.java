package bio.overture.ego.token;

import static bio.overture.ego.utils.CollectionUtils.mapToSet;
import static org.junit.Assert.assertTrue;

import bio.overture.ego.model.dto.TokenResponse;
import bio.overture.ego.model.entity.Application;
import bio.overture.ego.model.entity.Token;
import bio.overture.ego.model.exceptions.NotFoundException;
import bio.overture.ego.service.TokenService;
import bio.overture.ego.utils.EntityGenerator;
import bio.overture.ego.utils.TestData;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@SpringBootTest
@RunWith(SpringRunner.class)
@Transactional
@ActiveProfiles("test")
public class ListTokenTest {

  public static TestData test = null;
  @Autowired private EntityGenerator entityGenerator;
  @Autowired private TokenService tokenService;
  @Rule public ExpectedException exception = ExpectedException.none();

  @Before
  public void setUp() {
    test = new TestData(entityGenerator);
  }

  @Test
  public void testListToken() {
    val tokenString1 = "791044a1-3ffd-4164-a6a0-0e1e666b28dc";
    val tokenString2 = "891044a1-3ffd-4164-a6a0-0e1e666b28dc";

    val scopes1 = test.getScopes("song.WRITE", "id.WRITE");
    val scopes2 = test.getScopes("song.READ", "id.READ");

    Set<String> scopeString1 = mapToSet(scopes1, scope -> scope.toString());
    Set<String> scopeString2 = mapToSet(scopes2, scope -> scope.toString());

    val applications = new HashSet<Application>();
    applications.add(test.score);

    val userToken1 =
        entityGenerator.setupToken(test.regularUser, tokenString1, 1000, scopes1, applications);
    val userToken2 =
        entityGenerator.setupToken(test.regularUser, tokenString2, 1000, scopes2, applications);

    Set<Token> tokens = new HashSet<>();
    tokens.add(userToken1);
    tokens.add(userToken2);
    test.regularUser.setTokens(tokens);

    val responseList = tokenService.listToken(test.regularUser.getId());

    List<TokenResponse> expected = new ArrayList<>();
    expected.add(new TokenResponse(tokenString1, scopeString1, userToken1.getSecondsUntilExpiry()));
    expected.add(new TokenResponse(tokenString2, scopeString2, userToken2.getSecondsUntilExpiry()));

    assertTrue((responseList.stream().allMatch(response -> expected.contains(response))));
  }

  @Test
  public void testEmptyTokenList() {
    exception.expect(NotFoundException.class);
    exception.expectMessage("User is not associated with any token.");
    tokenService.listToken(test.regularUser.getId());
  }
}
