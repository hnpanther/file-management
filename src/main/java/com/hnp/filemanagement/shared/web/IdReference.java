package com.hnp.filemanagement.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the v1 API names a file or a revision in a path, since 1.8.0: by its number, as it always
 * has, or by its external id - a UUID, in any case (issue 7, V2.16).
 *
 * <p>A path variable of this type is converted by {@link FromText}, so a segment that is neither
 * fails the way a non-numeric {@code int} always did: Spring's type mismatch, answered 400
 * {@code InvalidParameter} naming the parameter and not echoing the value - the contract APEX
 * relies on ({@code RestContractTest}).
 *
 * @param number     the id, when the segment was one
 * @param externalId the external id, lower-cased, when the segment was one
 */
@Schema(type = "string", description = "the id (a number) or the external id (a UUID)",
        example = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
public record IdReference(Integer number, String externalId) {

    private static final Pattern NUMBER = Pattern.compile("[0-9]{1,9}");
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Reads a path segment: ASCII digits (at most nine, so any {@code int} an id can be) are a
     * number, a UUID is an external id.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static IdReference parse(String text) {
        if (text != null && NUMBER.matcher(text).matches()) {
            return new IdReference(Integer.valueOf(text), null);
        }
        if (text != null && UUID.matcher(text).matches()) {
            return new IdReference(null, text.toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("neither an id nor an external id");
    }

    public boolean isNumber() {
        return number != null;
    }

    @Override
    public String toString() {
        return isNumber() ? number.toString() : externalId;
    }

    /** Registered with MVC as a bean, so {@code @PathVariable IdReference} converts through it. */
    @Component
    public static class FromText implements Converter<String, IdReference> {
        @Override
        public IdReference convert(String source) {
            return parse(source);
        }
    }
}
