package com.hnp.filemanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

/**
 * An upload the policy does not allow: the kind of file, or its size. A {@link InvalidDataException}
 * (so the JSON layers answer 400 with the message as {@code detail}) that also carries the facts,
 * so the pages can say the same thing in Persian.
 */
@ResponseStatus(code = HttpStatus.BAD_REQUEST)
public class UploadRefusedException extends InvalidDataException {

    public enum Reason { TYPE_NOT_ALLOWED, TOO_LARGE }

    private final Reason reason;
    private final String extension;
    private final long sizeBytes;
    private final long limitBytes;
    private final List<String> allowed;

    private UploadRefusedException(String message, Reason reason, String extension,
                                   long sizeBytes, long limitBytes, List<String> allowed) {
        super(message);
        this.reason = reason;
        this.extension = extension;
        this.sizeBytes = sizeBytes;
        this.limitBytes = limitBytes;
        this.allowed = List.copyOf(allowed);
    }

    public static UploadRefusedException typeNotAllowed(String extension, List<String> allowed) {
        return new UploadRefusedException(
                "file type ." + extension + " is not allowed; allowed: " + String.join(", ", allowed),
                Reason.TYPE_NOT_ALLOWED, extension, 0, 0, allowed);
    }

    public static UploadRefusedException tooLarge(String extension, long sizeBytes, long limitBytes, List<String> allowed) {
        return new UploadRefusedException(
                "file is " + sizeBytes + " bytes; the limit for ." + extension + " is " + limitBytes + " bytes",
                Reason.TOO_LARGE, extension, sizeBytes, limitBytes, allowed);
    }

    public Reason getReason() {
        return reason;
    }

    public String getExtension() {
        return extension;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public long getLimitBytes() {
        return limitBytes;
    }

    public List<String> getAllowed() {
        return allowed;
    }
}
