package bio.overture.ego.model.dto;

import bio.overture.ego.view.Views;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

@AllArgsConstructor
@Getter
@JsonView(Views.REST.class)
public class UserScopesResponse {

  private Set<String> scopes;

}