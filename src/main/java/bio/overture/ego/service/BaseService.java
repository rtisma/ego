package bio.overture.ego.service;

import static bio.overture.ego.model.exceptions.NotFoundException.checkNotFound;
import static bio.overture.ego.utils.Collectors.toImmutableSet;
import static bio.overture.ego.utils.Joiners.COMMA;
import static java.lang.String.format;

import bio.overture.ego.model.exceptions.NotFoundException;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.val;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

public interface BaseService<T, ID> {

  String getEntityTypeName();

  default T getById(@NonNull ID id) {
    val entity = findById(id);
    return entity.orElseThrow(
        () ->
            new NotFoundException(
                format(
                    "The '%s' entity with id '%s' does not exist",
                    getEntityTypeName(), id.toString())));
  }

  Optional<T> findById(ID id);

  boolean isExist(ID id);

  void delete(ID id);

  Page<T> findAll(Specification specification, Pageable pageable);

  Set<T> getMany(Collection<ID> ids);

  T getWithRelationships(ID id);

  default void checkExistence(@NonNull Collection<ID> ids) {
    val missingIds = ids.stream().filter(x -> !isExist(x)).collect(toImmutableSet());
    checkNotFound(
        missingIds.isEmpty(),
        "The following '%s' entity ids do no exist: %s",
        getEntityTypeName(),
        COMMA.join(missingIds));
  }

  default void checkExistence(@NonNull ID id) {
    checkNotFound(
        isExist(id), "The '%s' entity with id '%s' does not exist", getEntityTypeName(), id);
  }
}
