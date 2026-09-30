package com.hnp.filemanagement.file.web;

import jakarta.servlet.ServletContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where an upload is written while it arrives - a form's file part by Tomcat, a v2 {@code PUT} by
 * {@link SpooledRequestBody} - so that no upload is ever held in memory.
 *
 * <p>{@code FILEMANAGEMENT_UPLOAD_TEMP_DIR} ({@code spring.servlet.multipart.location}) when it is
 * set: created here if it does not exist, and the start refused if it cannot be written - Tomcat
 * would otherwise refuse the first upload into a missing directory, long after the deployment.
 * Unset, it is Tomcat's own temporary directory for this application, which Spring Boot creates
 * under {@code java.io.tmpdir} ({@code tomcat.8122.…/work/Tomcat/localhost/ROOT}) and removes on a
 * clean stop - on the system drive, and for a Windows service somewhere under {@code C:\Windows}.
 * Either way the start says where, once, so nobody has to guess.
 */
@Component
public class UploadTempDirectory {

    private static final Logger logger = LoggerFactory.getLogger(UploadTempDirectory.class);

    private final Path path;
    private final String source;

    public UploadTempDirectory(@Value("${spring.servlet.multipart.location:}") String location,
                               ServletContext servletContext) throws IOException {
        if (location != null && !location.isBlank()) {
            this.path = Path.of(location).toAbsolutePath().normalize();
            Files.createDirectories(path);
            if (!Files.isWritable(path)) {
                throw new IllegalStateException("the upload temporary directory cannot be written: " + path
                        + " (FILEMANAGEMENT_UPLOAD_TEMP_DIR)");
            }
            this.source = "FILEMANAGEMENT_UPLOAD_TEMP_DIR";
        } else if (servletContext.getAttribute(ServletContext.TEMPDIR) instanceof File tomcatOwn) {
            this.path = tomcatOwn.toPath();
            this.source = "Tomcat's own directory, because FILEMANAGEMENT_UPLOAD_TEMP_DIR is not set";
        } else {
            // No container of its own - a test's mock servlet context.
            this.path = Path.of(System.getProperty("java.io.tmpdir"));
            this.source = "java.io.tmpdir, because FILEMANAGEMENT_UPLOAD_TEMP_DIR is not set";
        }
    }

    /** The directory an upload in flight is written to. */
    public Path path() {
        return path;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        logger.info("uploads are written to {} while they arrive ({})", path, source);
    }
}
