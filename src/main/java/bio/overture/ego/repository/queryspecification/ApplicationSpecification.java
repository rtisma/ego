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

package bio.overture.ego.repository.queryspecification;

import bio.overture.ego.model.entity.Application;
import bio.overture.ego.model.entity.Group;
import bio.overture.ego.model.entity.User;
import bio.overture.ego.utils.QueryUtils;
import java.util.UUID;
import javax.persistence.criteria.Join;
import lombok.NonNull;
import lombok.val;
import org.springframework.data.jpa.domain.Specification;

public class ApplicationSpecification extends SpecificationBase<Application> {
  public static Specification<Application> containsText(@NonNull String text) {
    val finalText = QueryUtils.prepareForQuery(text);
    return (root, query, builder) -> {
      query.distinct(true);
      return builder.or(
          getQueryPredicates(
              builder,
              root,
              finalText,
              "name",
              "clientId",
              "clientSecret",
              "description",
              "status"));
    };
  }

  public static Specification<Application> inGroup(@NonNull UUID groupId) {
    return (root, query, builder) -> {
      query.distinct(true);
      Join<Application, Group> groupJoin = root.join("groups");
      return builder.equal(groupJoin.<Integer>get("id"), groupId);
    };
  }

  public static Specification<Application> usedBy(@NonNull UUID userId) {
    return (root, query, builder) -> {
      query.distinct(true);
      Join<Application, User> applicationUserJoin = root.join("users");
      return builder.equal(applicationUserJoin.<Integer>get("id"), userId);
    };
  }
}