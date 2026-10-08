package com.hnp.filemanagement.content;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP side of reading contents: what reaches Tika, and every way Tika can answer told apart -
 * unreachable and busy (no attempt spent), unable to parse (failed at once), anything else (an
 * attempt) - against a server of the test's own.
 */
class HttpTikaClientTest {

    private HttpServer server;
    private URI base;
    private final AtomicReference<Answer> answer = new AtomicReference<>();
    private final Map<String, String> received = new ConcurrentHashMap<>();

    private record Answer(int status, String body, long delayMillis) {
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] sent = exchange.getRequestBody().readAllBytes();
            received.put("method", exchange.getRequestMethod());
            received.put("path", exchange.getRequestURI().getPath());
            received.put("body", new String(sent, StandardCharsets.UTF_8));
            received.put("length", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Length")));
            received.put("type", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")));
            received.put("disposition", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Disposition")));
            Answer a = answer.get();
            try {
                Thread.sleep(a.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(a.status(), body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpTikaClient client(Duration timeout) {
        return new HttpTikaClient(base, base, Duration.ofSeconds(2), timeout, timeout);
    }

    private static Supplier<InputStream> bytes(String text) {
        return () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String xhtml(String body) {
        return "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><meta name=\"Content-Type\" content=\"text/plain\"/></head><body>"
                + body + "</body></html>";
    }

    @Test
    @DisplayName("the document is PUT with its length, a type hint and a name of nothing but its extension; the answer is read")
    void aReading() throws Exception {
        answer.set(new Answer(200, xhtml("<p>متن خوانده شده</p>"), 0));
        TikaReading reading = client(Duration.ofSeconds(5)).read(TikaClient.Lane.TEXT, null, bytes("hello"), 5,
                "text/plain", "TXT", 1000);
        assertThat(reading.units()).singleElement().satisfies(unit -> assertThat(unit.layer()).isEqualTo("متن خوانده شده"));
        assertThat(received).containsEntry("method", "PUT").containsEntry("path", "/tika/xml").containsEntry("body", "hello")
                .containsEntry("length", "5").containsEntry("type", "text/plain")
                .containsEntry("disposition", "attachment; filename=\"document.txt\"");
    }

    @Test
    @DisplayName("a preset is in the path; a type hint that is not a media type, or an odd extension, is not sent")
    void presetAndHints() throws Exception {
        answer.set(new Answer(200, xhtml("<p>x</p>"), 0));
        client(Duration.ofSeconds(5)).read(TikaClient.Lane.OCR, "every-page", bytes("x"), 1,
                "text/plain\r\nX-Evil: 1", "p\"df", 1000);
        assertThat(received).containsEntry("path", "/tika/preset/every-page/xml").containsEntry("type", "null")
                .containsEntry("disposition", "null");
    }

    @Test
    @DisplayName("nobody listening: Unavailable - Tika's absence, not the document's fault")
    void unreachable() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        HttpTikaClient nowhere = new HttpTikaClient(URI.create("http://127.0.0.1:" + closed), null,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThatThrownBy(() -> nowhere.read(TikaClient.Lane.TEXT, null, bytes("x"), 1, "text/plain", "txt", 100))
                .isInstanceOf(TikaClient.Unavailable.class);
        assertThatThrownBy(() -> nowhere.read(TikaClient.Lane.OCR, null, bytes("x"), 1, "application/pdf", "pdf", 100))
                .as("no OCR container configured").isInstanceOf(TikaClient.Unavailable.class);
    }

    @Test
    @DisplayName("429 busy: Unavailable; 422 cannot parse: Refused; 503 and 500: an attempt; not XHTML: an attempt")
    void statuses() {
        HttpTikaClient client = client(Duration.ofSeconds(5));
        answer.set(new Answer(429, "", 0));
        assertThatThrownBy(() -> client.read(TikaClient.Lane.TEXT, null, bytes("x"), 1, "text/plain", "txt", 100))
                .isInstanceOf(TikaClient.Unavailable.class);
        answer.set(new Answer(422, "{\"status\":\"PARSE_EXCEPTION\"}", 0));
        assertThatThrownBy(() -> client.read(TikaClient.Lane.TEXT, null, bytes("x"), 1, "text/plain", "txt", 100))
                .isInstanceOf(TikaClient.Refused.class).hasMessageContaining("422").hasMessageContaining("PARSE_EXCEPTION");
        for (int status : new int[]{500, 503}) {
            answer.set(new Answer(status, "{\"status\":\"TIMEOUT\"}", 0));
            assertThatThrownBy(() -> client.read(TikaClient.Lane.TEXT, null, bytes("x"), 1, "text/plain", "txt", 100))
                    .isInstanceOf(IOException.class)
                    .isNotInstanceOfAny(TikaClient.Unavailable.class, TikaClient.Refused.class)
                    .hasMessageContaining(String.valueOf(status));
        }
        answer.set(new Answer(200, "this is not xhtml", 0));
        assertThatThrownBy(() -> client.read(TikaClient.Lane.TEXT, null, bytes("x"), 1, "text/plain", "txt", 100))
                .isInstanceOf(TikaReading.MalformedAnswer.class);
    }

    @Test
    @DisplayName("an answer slower than the lane's limit: an attempt spent, not Tika lost")
    void tooSlow() {
        answer.set(new Answer(200, xhtml("<p>late</p>"), 3_000));
        assertThatThrownBy(() -> client(Duration.ofSeconds(1)).read(TikaClient.Lane.TEXT, null, bytes("x"), 1,
                "text/plain", "txt", 100))
                .isInstanceOf(IOException.class).isNotInstanceOf(TikaClient.Unavailable.class);
    }
}
