package com.hnp.filemanagement.file.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The download header, and the reason it is built the way it is (issue 85).
 *
 * <p>The web tests cannot see that reason: MockMvc hands the response back without a servlet
 * container, so a header Tomcat would have thrown away arrives intact. The last test here runs a
 * real Tomcat - the one the application ships with - for exactly that.
 */
class ContentDispositionsTest {

    /** "گزارش مالی.pdf" - a name as the people using this application actually give their files. */
    private static final String PERSIAN = "گزارش مالی.pdf";

    @Test
    @DisplayName("an ASCII name keeps the filename parameter it always had, so an integration reading it is unaffected")
    void anAsciiNameIsUnchanged() {
        String header = ContentDispositions.attachment("report.pdf");

        assertThat(header)
                .startsWith("attachment; filename=\"report.pdf\"")
                .isEqualTo("attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf");
    }

    @Test
    @DisplayName("a Persian name is sent as ASCII only, and the real name comes back out of filename*")
    void aPersianNameIsAsciiOnTheWire() {
        String header = ContentDispositions.attachment(PERSIAN);

        assertThat(header.chars()).as("nothing a servlet container could refuse").allMatch(c -> c >= 0x20 && c < 0x7F);
        assertThat(header).contains("filename*=UTF-8''%DA%AF%D8%B2%D8%A7%D8%B1%D8%B4%20%D9%85%D8%A7%D9%84%DB%8C.pdf");
        assertThat(ContentDisposition.parse(header).getFilename()).as("what a browser saves it as").isEqualTo(PERSIAN);
        assertThat(header).as("the fallback keeps the extension").contains("filename=\"_____ ____.pdf\"");
    }

    @Test
    @DisplayName("inline or attachment, as asked, with the name either way")
    void inlineOrAttachment() {
        assertThat(ContentDispositions.of(true, PERSIAN)).startsWith("inline; ");
        assertThat(ContentDispositions.of(false, PERSIAN)).startsWith("attachment; ");
        assertThat(ContentDisposition.parse(ContentDispositions.of(true, PERSIAN)).getFilename()).isEqualTo(PERSIAN);
    }

    @Test
    @DisplayName("a quote in the name cannot end the parameter early")
    void aQuoteIsEscaped() {
        String name = "a \"quoted\" name.txt";

        assertThat(ContentDispositions.attachment(name)).startsWith("attachment; filename=\"a \\\"quoted\\\" name.txt\"");
        assertThat(ContentDisposition.parse(ContentDispositions.attachment(name)).getFilename()).isEqualTo(name);
    }

    /**
     * The defect itself, in the container the application runs in: the header as it used to be
     * written reaches nobody, and the one built here arrives whole. The first half pins the cause
     * as well as the fix - if a future Tomcat started passing raw UTF-8 through, this test says so.
     */
    @Test
    @DisplayName("through a real Tomcat: the raw Persian header is dropped, the built one arrives and names the file")
    void survivesARealTomcat(@TempDir Path baseDir) throws Exception {
        // Tomcat logs the header it drops as a WARNING with a stack trace; expected here, so muted.
        // The reference is held so the level is not lost to a collected logger mid-test.
        Logger http11 = Logger.getLogger("org.apache.coyote.http11.Http11Processor");
        Level previous = http11.getLevel();
        http11.setLevel(Level.SEVERE);

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(baseDir.toString());
        tomcat.setPort(0);
        Context context = tomcat.addContext("", null);
        Map<String, String> headers = Map.of(
                "/raw/download", "attachment; filename=\"" + PERSIAN + "\"",
                "/built/download", ContentDispositions.attachment(PERSIAN));
        headers.forEach((path, header) -> {
            Tomcat.addServlet(context, path, new SendsHeader(header));
            context.addServletMappingDecoded(path, path, false);
        });
        tomcat.getConnector();
        tomcat.start();
        try {
            int port = tomcat.getConnector().getLocalPort();

            assertThat(dispositionAt(port, "/raw/download")).as("the old header never leaves the server").isEmpty();

            Optional<String> built = dispositionAt(port, "/built/download");
            assertThat(built).isPresent();
            assertThat(ContentDisposition.parse(built.get()).getFilename()).isEqualTo(PERSIAN);
        } finally {
            tomcat.stop();
            tomcat.destroy();
            http11.setLevel(previous);
        }
    }

    private static Optional<String> dispositionAt(int port, String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.headers().firstValue(HttpHeaders.CONTENT_DISPOSITION);
        }
    }

    /** A download reduced to its header. */
    private static final class SendsHeader extends HttpServlet {
        private final String header;

        SendsHeader(String header) {
            this.header = header;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, header);
            response.getWriter().write("bytes");
        }
    }
}
