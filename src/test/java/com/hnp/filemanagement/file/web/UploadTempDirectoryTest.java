package com.hnp.filemanagement.file.web;

import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockServletContext;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Where an upload waits: the configured directory, made at the start - or Tomcat's own. */
class UploadTempDirectoryTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("a configured directory that does not exist yet is created at the start")
    void aConfiguredDirectoryIsCreated() throws IOException {
        Path configured = root.resolve("upload-tmp").resolve("nested");

        UploadTempDirectory directory = new UploadTempDirectory(configured.toString(), new MockServletContext());

        assertThat(directory.path()).isEqualTo(configured.toAbsolutePath().normalize());
        assertThat(configured).isDirectory();
    }

    @Test
    @DisplayName("unset, it is Tomcat's own temporary directory for the application - where a form part goes too")
    void unsetIsTomcatsOwn() throws IOException {
        MockServletContext servletContext = new MockServletContext();
        servletContext.setAttribute(ServletContext.TEMPDIR, root.toFile());

        assertThat(new UploadTempDirectory("", servletContext).path()).isEqualTo(root);
        assertThat(new UploadTempDirectory(null, servletContext).path()).isEqualTo(root);
    }
}
