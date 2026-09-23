package com.hnp.filemanagement.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * One page of anything ({@code docs/issues.md} issue 46, roadmap 2.1).
 *
 * <p>There used to be a hand-rolled page class per entity - {@code FileInfoPageDTO},
 * {@code PublicFileDetailsPageDTO} - each a mutable bean with the same four fields, filled by the
 * same four setters at every call site, and each one a place for the four to drift apart. This is
 * the one shape, built from Spring Data's {@link Page} in a line, and it carries what a pager
 * actually needs.
 *
 * @param content        the rows of this page, already converted
 * @param page           which page this is, zero-based
 * @param size           rows per page, as asked for
 * @param numberOfElements rows on this page - fewer than {@code size} on the last one
 * @param totalElements  rows in the whole result
 * @param totalPages     pages in the whole result
 */
public record PageResponse<T>(List<T> content, int page, int size, int numberOfElements,
                              long totalElements, int totalPages) {

    /** A page of entities as a page of whatever the converter makes of them. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> converter) {
        return new PageResponse<>(
                page.getContent().stream().map(converter).toList(),
                page.getNumber(),
                page.getSize(),
                page.getNumberOfElements(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    /** A page whose rows are converted in one go - when each row needs the others for context. */
    public static <E, T> PageResponse<T> of(Page<E> page, List<T> converted) {
        return new PageResponse<>(converted, page.getNumber(), page.getSize(),
                page.getNumberOfElements(), page.getTotalElements(), page.getTotalPages());
    }

    /** The name the Thymeleaf pages use; kept so a template reads what it always read. */
    public List<T> getContent() {
        return content;
    }
}
