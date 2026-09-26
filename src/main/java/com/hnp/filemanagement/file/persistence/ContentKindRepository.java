package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.ContentKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** The custom content kinds, in the order they were added. */
public interface ContentKindRepository extends JpaRepository<ContentKind, Integer> {

    List<ContentKind> findAllByOrderByIdAsc();

    Optional<ContentKind> findByExtension(String extension);

    boolean existsByExtension(String extension);
}
