package com.hnp.filemanagement.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How the v1 API names a file or a revision in a path: by its external id, a UUID in any case
 * (issue 7, V2.16), and since 2.4.0 by nothing else.
 *
 * <p>From 1.8.0 to 2.3.0 a segment could also be the row's number ({@code IdReference}), so that
 * the PL/SQL clients could move over one at a time ({@code docs/api-v1.md}). They have moved, and
 * the numbers are refused: they are guessable, they belong to this database's numbering, and a
 * number whose row was deleted can be handed to a new row (issue 98) - a client still sending one
 * would reach somebody else's file.
 *
 * <p>A path variable of this type is converted by {@link FromText}, so a segment that is not a
 * UUID - a number included - fails the way a non-numeric {@code int} always did: Spring's type
 * mismatch, answered 400 {@code InvalidParameter} naming the parameter and not echoing the value -
 * the contract APEX relies on ({@code RestContractTest}). For this type the answer also says that
 * the parameter takes the external id ({@code GlobalExceptionHandler.typeMismatch}).
 *
 * @param value the external id, lower-cased - as every stored one is
 */
@Schema(type = "string", format = "uuid", description = "the external id (a UUID); numeric ids are refused since 2.4.0",
        example = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d")
public record ExternalId(String value) {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Reads a path segment: a UUID, in either case.
     *
     * @throws IllegalArgumentException for anything else, a number included
     */
    public static ExternalId parse(String text) {
        if (text != null && UUID.matcher(text).matches()) {
            return new ExternalId(text.toLowerCase(Locale.ROOT));
        }
        throw new IllegalArgumentException("not an external id");
    }

    @Override
    public String toString() {
        return value;
    }

    /** Registered with MVC as a bean, so {@code @PathVariable ExternalId} converts through it. */
    @Component
    public static class FromText implements Converter<String, ExternalId> {
        @Override
        public ExternalId convert(String source) {
            return parse(source);
        }
    }
}
