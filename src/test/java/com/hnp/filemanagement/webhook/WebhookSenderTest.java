package com.hnp.filemanagement.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sender against a receiver that drops a connection without a word - as a receiver does to a kept-alive
 * connection it closed while it sat idle (found in the real test of 2026-10-31: three deliveries in 1,400 waited a
 * minute for their retry). Sent again at once on a new connection, not a minute later; a receiver that drops every
 * one is unreachable after the second try, not retried for ever.
 */
class WebhookSenderTest {

    private static final WebhookProperties PROPERTIES = new WebhookProperties(true, List.of("127.0.0.1"), 30, 0, 2,
            60, 2, 20);

    @Test
    @DisplayName("a connection dropped without an answer: sent again at once, on a new one")
    void droppedOnceThenAnswered() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> serve(server, connections, 1));
            WebhookSender.Outcome outcome = new WebhookSender(PROPERTIES).send(target(server), delivery(), Instant.now());
            assertThat(outcome.delivered()).isTrue();
            assertThat(outcome.status()).isEqualTo(200);
            assertThat(connections).hasValue(2);
        }
    }

    @Test
    @DisplayName("a receiver that drops every connection: unreachable after the second try - the schedule takes it from there")
    void droppedAlways() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            Thread.ofVirtual().start(() -> serve(server, connections, Integer.MAX_VALUE));
            WebhookSender.Outcome outcome = new WebhookSender(PROPERTIES).send(target(server), delivery(), Instant.now());
            assertThat(outcome.delivered()).isFalse();
            assertThat(outcome.error()).startsWith("unreachable");
            assertThat(connections).hasValue(2);
        }
    }

    /** Reads each request's head; drops the first {@code drop} connections unanswered, answers the rest 200. */
    private static void serve(ServerSocket server, AtomicInteger connections, int drop) {
        try {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    int n = connections.incrementAndGet();
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    int length = 0;
                    while ((line = in.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            length = Integer.parseInt(line.substring(15).strip());
                        }
                    }
                    // The body read whole: a socket closed on unread bytes is reset, not closed.
                    for (int i = 0; i < length; i++) {
                        in.read();
                    }
                    if (n > drop) {
                        OutputStream out = socket.getOutputStream();
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                }
            }
        } catch (Exception ignored) {
            // closed by the test
        }
    }

    private static WebhookSender.Target target(ServerSocket server) {
        return new WebhookSender.Target(1, "http://127.0.0.1:" + server.getLocalPort() + "/hook", "whsec_test", null,
                "NONE", null, null);
    }

    private static WebhookDeliveryRepository.ToSend delivery() {
        return new WebhookDeliveryRepository.ToSend(1, 1, UUID.randomUUID(), "FILE_CREATED", "{\"Records\":[]}", 1,
                Instant.now());
    }
}
