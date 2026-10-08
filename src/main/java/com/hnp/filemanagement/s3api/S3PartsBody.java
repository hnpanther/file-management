package com.hnp.filemanagement.s3api;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;

/**
 * A completed multipart upload's bytes - its parts' files, in order - presented as the one
 * {@link MultipartFile} the upload path takes (roadmap 9.10 step 5), so that a file sent in parts is
 * checked, stored and recorded by the same code as one sent whole. Read as one stream, each part
 * opened as the one before it ends: no copy, no part in memory.
 */
final class S3PartsBody implements MultipartFile {

    private final String name;
    private final String contentType;
    private final List<Path> parts;
    private final long size;

    S3PartsBody(String name, String contentType, List<Path> parts, long size) {
        this.name = name;
        this.contentType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        this.parts = List.copyOf(parts);
        this.size = size;
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
        try (InputStream in = getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** The parts one after another, each opened as the one before it is read to its end. */
    @Override
    public InputStream getInputStream() {
        Iterator<Path> each = parts.iterator();
        return new SequenceInputStream(new Enumeration<>() {
            @Override
            public boolean hasMoreElements() {
                return each.hasNext();
            }

            @Override
            public InputStream nextElement() {
                try {
                    return Files.newInputStream(each.next());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
    }

    @Override
    public void transferTo(File destination) throws IOException {
        try (InputStream in = getInputStream(); OutputStream out = Files.newOutputStream(destination.toPath())) {
            in.transferTo(out);
        }
    }
}
