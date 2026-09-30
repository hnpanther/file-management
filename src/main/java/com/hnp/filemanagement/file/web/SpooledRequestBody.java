package com.hnp.filemanagement.file.web;

import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A raw request body - a v2 {@code PUT} - written to a temporary file as it arrives, and presented
 * as a {@link MultipartFile} so that it goes through the same upload path as a form (roadmap 9.3):
 * the validation, the versioning and the audit row are the same code, which is the only way they
 * stay the same behaviour.
 *
 * <p><b>Streamed, never held in memory</b> (issue 44). The body used to be bound as a
 * {@code byte[]}, so a 1 GB {@code PUT} was a 1 GB array on the heap - and nothing bounded it,
 * since the multipart cap applies to forms only. The same cap bounds this one: a declared
 * {@code Content-Length} above it is refused before a byte is read, and a body without one is cut
 * off as soon as it passes the cap. A form upload is spooled the same way, by Tomcat.
 *
 * <p><b>Close it when done</b> - that deletes the file. {@code contentType} is whatever the caller
 * claimed and is stored, never trusted: the extension comes from the key and the storage layer
 * sniffs, as for a form.
 */
public final class SpooledRequestBody implements MultipartFile, AutoCloseable {

    private static final int BUFFER_BYTES = 64 * 1024;

    private final String name;
    private final String contentType;
    private final Path file;
    private final long size;

    private SpooledRequestBody(String name, String contentType, Path file, long size) {
        this.name = name;
        this.contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        this.file = file;
        this.size = size;
    }

    /**
     * Writes the body to a new temporary file in {@code directory}.
     *
     * @param declaredLength the request's {@code Content-Length}, or -1 when it has none
     * @param maxBytes       the server's upload cap
     * @throws MaxUploadSizeExceededException the body is, or says it is, larger than the cap; the
     *                                        partial file is deleted
     */
    public static SpooledRequestBody spool(String name, String contentType, InputStream body,
                                           long declaredLength, long maxBytes, Path directory) throws IOException {
        if (declaredLength > maxBytes) {
            throw new MaxUploadSizeExceededException(maxBytes);
        }
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "v2-put-", ".body");
        try {
            long total = 0;
            if (body != null) {
                try (OutputStream out = Files.newOutputStream(file)) {
                    byte[] buffer = new byte[BUFFER_BYTES];
                    int read;
                    while ((read = body.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) {
                            throw new MaxUploadSizeExceededException(maxBytes);
                        }
                        out.write(buffer, 0, read);
                    }
                }
            }
            return new SpooledRequestBody(name, contentType, file, total);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file);
            throw e;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return name;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public long getSize() {
        return size;
    }

    /** The whole body in memory - part of the interface, and not used by the upload path. */
    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(file);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(file);
    }

    @Override
    public void transferTo(File destination) throws IOException {
        Files.copy(file, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Deletes the temporary file. */
    @Override
    public void close() throws IOException {
        Files.deleteIfExists(file);
    }
}
