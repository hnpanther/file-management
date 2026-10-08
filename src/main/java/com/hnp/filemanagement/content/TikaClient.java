package com.hnp.filemanagement.content;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.function.Supplier;

/**
 * The two Tika containers (deploy/tika) over HTTP: a document sent, its XHTML read back as a stream
 * ({@link TikaReading}). What can go wrong is told apart, because each is handled differently:
 *
 * <ul>
 *   <li>{@link Unavailable} - Tika not there, or busy: not the document's fault. The reading is put
 *       back as it was, with no attempt spent, and the worker waits before it asks Tika again.</li>
 *   <li>{@link Refused} - Tika read the document and could not parse it ({@code 422}): reading it again
 *       would answer the same, so it is {@code FAILED} at once, with the reason.</li>
 *   <li>any other {@link IOException} - a time limit passed, a parser that took its process down
 *       ({@code 503}), an answer that is not XHTML: one attempt spent, tried again later.</li>
 * </ul>
 */
public interface TikaClient {

    /** Which container: the text one for Office documents and text, the OCR one for PDFs and images. */
    enum Lane { TEXT, OCR }

    /**
     * Sends a document and reads what Tika makes of it.
     *
     * @param preset        a preset of the container's configuration ({@code every-page}), or null for its default
     * @param body          opens the document's bytes; called once
     * @param size          their length
     * @param contentType   what the application judged them to be, as a hint
     * @param extension     the file's extension, as a hint for kinds told apart by name (text, CSV)
     * @param maxCharacters the text kept; beyond it the reading is partial
     */
    TikaReading read(Lane lane, String preset, Supplier<InputStream> body, long size, String contentType,
                     String extension, long maxCharacters) throws IOException, InterruptedException;

    /** Tika could not be reached, or was too busy to take the document. */
    final class Unavailable extends IOException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Tika read the document and could not parse it - the document's fault, the same every time. */
    final class Refused extends IOException {
        public Refused(String message) {
            super(message);
        }
    }

    /** Where a lane's requests go: {@code {base}/tika/xml}, or {@code {base}/tika/preset/{preset}/xml}. */
    static URI endpoint(URI base, String preset) {
        String root = base.toString();
        return URI.create(root + (preset == null ? "/tika/xml" : "/tika/preset/" + preset + "/xml"));
    }
}
