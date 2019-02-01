package bio.overture.ego.model.entity;

import bio.overture.ego.model.enums.LombokFields;
import bio.overture.ego.model.enums.SqlFields;
import bio.overture.ego.model.enums.Tables;
import bio.overture.ego.view.Views;
import com.fasterxml.jackson.annotation.JsonView;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = Tables.USER_PERMISSION)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonView(Views.REST.class)
@ToString(callSuper = true)
@EqualsAndHashCode(
    callSuper = true,
    of = {LombokFields.id})
public class UserPermission extends AbstractPermission {

  @NotNull
  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = SqlFields.USERID_JOIN, nullable = false)
  private User owner;
}