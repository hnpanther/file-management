package com.hnp.filemanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Optional;

/**
 * The request cannot be carried out as it stands - a 400.
 *
 * <p><b>What went wrong is said twice when a person could fix it.</b> {@link #getMessage()} is for
 * the log and for machines: the API answers it as the detail, in English, as it always has. A
 * {@linkplain #InvalidDataException(String, String, Object...) message code}, when there is one,
 * names a {@code messages.properties} entry that says the same thing to a person - which the pages
 * show in place of "enter the information correctly", a sentence that told nobody which field, which
 * file or which rule. The upload form answered every refusal with it: a {@code .vsdx} nobody may
 * upload, a name with a colon in it and a missing folder all read the same.
 */
@ResponseStatus(code = HttpStatus.BAD_REQUEST)
public class InvalidDataException extends RuntimeException {

    private final String messageCode;
    private final Object[] messageArguments;

    public InvalidDataException(String message) {
        this(message, null);
    }

    /**
     * @param message          for the log and the API, in English
     * @param messageCode      the {@code messages.properties} entry that tells a person what to change
     * @param messageArguments its {@code {0}}, {@code {1}}, ...
     */
    public InvalidDataException(String message, String messageCode, Object... messageArguments) {
        super(message);
        this.messageCode = messageCode;
        this.messageArguments = messageArguments == null ? new Object[0] : messageArguments.clone();
    }

    /** The entry that says this to a person, when there is one. */
    public Optional<String> getMessageCode() {
        return Optional.ofNullable(messageCode);
    }

    public Object[] getMessageArguments() {
        return messageArguments.clone();
    }
}
