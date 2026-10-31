package com.hnp.filemanagement.webhook;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A receiver for the webhook tests: an HTTP server on 127.0.0.1, a free port, that keeps every request it is sent -
 * path, headers, body - and answers each path as told (200 unless told otherwise, after a delay if told one).
 */
final class TestReceiver implements AutoCloseable {

    /** One request as it arrived. */
    record Received(String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    /** How a path answers. */
    record Answer(int status, Duration delay, String location) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    TestReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(path, Map.copyOf(exchange.getRequestHeaders()), body));
            Answer answer = answers.getOrDefault(path, new Answer(200, Duration.ZERO, null));
            try {
                Thread.sleep(answer.delay());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (answer.location() != null) {
                exchange.getResponseHeaders().add("Location", answer.location());
            }
            byte[] said = ("answered " + answer.status()).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(answer.status(), said.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(said);
            } catch (IOException ignored) {
                // the client gave up waiting
            }
        });
        server.start();
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void answer(String path, int status) {
        answers.put(path, new Answer(status, Duration.ZERO, null));
    }

    void answer(String path, int status, Duration delay, String location) {
        answers.put(path, new Answer(status, delay, location));
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    List<Received> receivedAt(String path) {
        return received.stream().filter(r -> r.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
