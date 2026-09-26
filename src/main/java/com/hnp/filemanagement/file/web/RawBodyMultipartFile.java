package com.hnp.filemanagement.file.web;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * A request body presented as a {@link MultipartFile}, so that a raw {@code PUT} can go through the
 * same upload path as a form (roadmap 9.3).
 *
 * <p>S3 clients {@code PUT} the bytes themselves, not a multipart form, and the storage layer of
 * this application speaks {@code MultipartFile} everywhere. Adapting at the edge means the v2 write
 * path and the page's upload path stay one path — the validation, the versioning and the audit row
 * are the same code, which is the only way they stay the same behaviour.
 *
 * <p><b>It holds the whole body in memory</b>, which is what the existing upload path does too
 * ({@code docs/issues.md}, issue 44): the multipart resolver buffers, {@code FileService} calls
 * {@code getBytes()}, and the size cap is what stops either from being a problem. When issue 44 is
 * fixed this has to be fixed with it, not after it.
 *
 * <p>{@code contentType} is whatever the caller claimed and is stored, never trusted: the extension
 * comes from the key and the storage layer sniffs. That is the rule for the form upload as well.
 */
public class RawBodyMultipartFile implements MultipartFile {

    private final String name;
    private final String contentType;
    private final byte[] content;

    public RawBodyMultipartFile(String name, String contentType, byte[] content) {
        this.name = name;
        this.contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
        this.content = content == null ? new byte[0] : content;
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
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() {
        return content;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File destination) throws IOException {
        Files.write(destination.toPath(), content);
    }
}
