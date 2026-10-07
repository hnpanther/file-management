package com.hnp.filemanagement.s3api;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * S3's errors, as S3 writes them: a status and an XML body whose {@code Code} a client branches on
 * (roadmap 9.10.4). The message is for a person; it never carries a secret, a signature or a path
 * on disk.
 */
public final class S3Errors {

    private S3Errors() {
    }

    public enum Error {
        ACCESS_DENIED(403, "AccessDenied", "Access Denied"),
        INVALID_ACCESS_KEY_ID(403, "InvalidAccessKeyId", "The access key id you provided does not exist in our records."),
        SIGNATURE_DOES_NOT_MATCH(403, "SignatureDoesNotMatch",
                "The request signature we calculated does not match the signature you provided."),
        REQUEST_TIME_TOO_SKEWED(403, "RequestTimeTooSkewed",
                "The difference between the request time and the server's time is too large."),
        REQUEST_EXPIRED(403, "AccessDenied", "Request has expired"),
        AUTHORIZATION_HEADER_MALFORMED(400, "AuthorizationHeaderMalformed", "The authorization header is malformed."),
        AUTHORIZATION_QUERY_MALFORMED(400, "AuthorizationQueryParametersError", "The pre-signed URL is malformed."),
        CONTENT_SHA256_MISMATCH(400, "XAmzContentSHA256Mismatch",
                "The provided 'x-amz-content-sha256' header does not match what was computed."),
        INCOMPLETE_BODY(400, "IncompleteBody", "The request body is incomplete or its chunks are malformed."),
        INVALID_ARGUMENT(400, "InvalidArgument", "Invalid argument."),
        METADATA_TOO_LARGE(400, "MetadataTooLarge", "Your metadata headers exceed the maximum allowed metadata size."),
        MALFORMED_XML(400, "MalformedXML", "The XML you provided was not well-formed or did not validate against our published schema."),
        ENTITY_TOO_LARGE(400, "EntityTooLarge", "Your proposed upload exceeds the maximum allowed size."),
        NO_SUCH_BUCKET(404, "NoSuchBucket", "The specified bucket does not exist."),
        NO_SUCH_KEY(404, "NoSuchKey", "The specified key does not exist."),
        PRECONDITION_FAILED(412, "PreconditionFailed", "At least one of the preconditions you specified did not hold."),
        FOLDER_NOT_EMPTY(409, "FolderNotEmpty", "The folder you tried to delete is not empty."),
        NOT_IMPLEMENTED(501, "NotImplemented", "A header or a parameter you provided implies functionality that is not implemented."),
        SERVICE_UNAVAILABLE(503, "ServiceUnavailable", "The storage is not available; try again."),
        INTERNAL_ERROR(500, "InternalError", "We encountered an internal error. Please try again.");

        private final int status;
        private final String code;
        private final String message;

        Error(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String message() {
            return message;
        }
    }

    /** Written straight to the response - from the filter, before any handler. */
    public static void write(HttpServletResponse response, Error error, String resource) throws IOException {
        write(response, error, error.message(), resource);
    }

    public static void write(HttpServletResponse response, Error error, String message, String resource) throws IOException {
        response.setStatus(error.status());
        response.setContentType("application/xml");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body(error, message, resource));
    }

    public static String body(Error error, String message, String resource) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Error><Code>" + error.code() + "</Code><Message>"
                + xml(message) + "</Message><Resource>" + xml(resource) + "</Resource><RequestId>"
                + UUID.randomUUID() + "</RequestId></Error>";
    }

    static String xml(String value) {
        if (value == null) {
            return "";
        }
        if (!carriable(value)) {
            // An error's message echoes what the client sent; a character XML 1.0 cannot carry would
            // make the whole answer unreadable, so it is marked rather than written.
            StringBuilder replaced = new StringBuilder();
            value.codePoints().forEach(c -> replaced.appendCodePoint(carriable(c) ? c : 0xFFFD));
            value = replaced.toString();
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /**
     * Whether XML 1.0 can carry every character of the value: not a control character other than
     * tab, line feed and carriage return, not U+FFFE or U+FFFF, not half of a surrogate pair. A name
     * here holds no control character ({@code ValidationUtil}), but may hold the others, and a client
     * may send anything as a prefix.
     */
    static boolean carriable(String value) {
        return value.codePoints().allMatch(S3Errors::carriable);
    }

    private static boolean carriable(int c) {
        return c == 0x9 || c == 0xA || c == 0xD || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xE000 && c <= 0xFFFD)
                || (c >= 0x10000 && c <= 0x10FFFF);
    }
}
