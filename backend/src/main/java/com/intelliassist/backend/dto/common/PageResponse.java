package com.intelliassist.backend.dto.common;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A page of results, in our own shape.
 *
 * <p>Spring's {@code Page} serialises with an unstable structure that leaks
 * internals such as {@code pageable} and {@code sort}, and it warns about
 * exactly this. Mapping to a small record keeps the public contract stable
 * regardless of Spring Data versions.
 */
public record PageResponse<T>(
        List<T> items, int page, int size, long totalItems, int totalPages, boolean hasNext) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
