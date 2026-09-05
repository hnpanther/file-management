package com.hnp.filemanagement.dto;

/**
 * Uniform success body for the REST layer.
 *
 * <p>Endpoints used to answer with a bare English sentence - {@code "general tag deleted"},
 * {@code "file info with id =3 deleted"} - which no client can act on, and which differed in
 * wording from one endpoint to the next. This gives every mutation the same shape:
 *
 * <pre>{@code {"outcome":"DELETED","resource":"generalTag","id":5} }</pre>
 *
 * <p>The status code carries the meaning; this body carries the identity of what changed.
 * Failures do not use this type at all - they come back as RFC 9457 {@code ProblemDetail} from
 * {@code GlobalExceptionHandler}.
 *
 * @param outcome  what happened, from {@link Outcome}
 * @param resource the kind of thing that changed, in camelCase
 * @param id       its identifier, or {@code null} when the operation was not about one row
 */
public record ApiResult(String outcome, String resource, Integer id) {

    public enum Outcome {
        CREATED, UPDATED, DELETED, STATE_CHANGED
    }

    public static ApiResult created(String resource, Integer id) {
        return new ApiResult(Outcome.CREATED.name(), resource, id);
    }

    public static ApiResult updated(String resource, Integer id) {
        return new ApiResult(Outcome.UPDATED.name(), resource, id);
    }

    public static ApiResult deleted(String resource, Integer id) {
        return new ApiResult(Outcome.DELETED.name(), resource, id);
    }

    public static ApiResult stateChanged(String resource, Integer id) {
        return new ApiResult(Outcome.STATE_CHANGED.name(), resource, id);
    }
}
