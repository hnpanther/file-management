package com.hnp.filemanagement.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Sends one delivery (roadmap 13.18): a {@code POST} of its body, signed, to the subscription's address - and answers
 * what came of it. Never throws for anything the receiver or the network did; never follows a redirect (a receiver
 * cannot send it anywhere the allowlist did not name); never reads more of an answer than a line for the log of
 * deliveries.
 *
 * <p>The signature: {@code X-FM-Signature: t=<seconds>,v1=<hex>}, the hex an HMAC-SHA256, under the subscription's
 * secret, of {@code <seconds>.<body>} - and while a rotated secret is still honoured, a second {@code v1} under the old
 * one. docs/webhooks.md shows a receiver checking it.
 */
@Component
public class WebhookSender {

    static final String USER_AGENT = "file-management-webhook/1";

    /** Where and how to send: the address, the secrets decrypted, the receiver's own authentication. */
    public record Target(int webhookId, String url, String signingSecret, String previousSigningSecret,
                         String authKind, String authName, String authSecret) {

        @Override
        public String toString() {
            return "Target[webhook id=" + webhookId + "]";
        }
    }

    /** What came of one attempt: delivered (a 2xx), and if not, the status (null: no answer) and why. */
    public record Outcome(boolean delivered, Integer status, String error) {
    }

    private final HttpClient client;
    private final Duration timeout;
    private final WebhookProperties properties;

    public WebhookSender(WebhookProperties properties) {
        this.properties = properties;
        this.timeout = Duration.ofSeconds(properties.timeoutSeconds());
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Outcome send(Target target, WebhookDeliveryRepository.ToSend delivery, Instant now) {
        URI uri;
        try {
            uri = URI.create(target.url());
        } catch (IllegalArgumentException e) {
            return new Outcome(false, null, "not an address");
        }
        if (!WebhookHosts.allowed(uri, properties.allowedHosts())) {
            return new Outcome(false, null, "the host is not on filemanagement.webhooks.allowed-hosts");
        }
        String body = delivery.payload();
        long seconds = now.getEpochSecond();
        try {
            return post(target, delivery, uri, body, seconds);
        } catch (RuntimeException e) {
            // A header HttpClient will not send, say: the delivery's failure, not the worker's.
            return new Outcome(false, null, "not sent: " + oneLine(e.toString()));
        }
    }

    private Outcome post(Target target, WebhookDeliveryRepository.ToSend delivery, URI uri, String body, long seconds) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("X-FM-Event", delivery.event())
                .header("X-FM-Event-Id", delivery.eventId().toString())
                .header("X-FM-Delivery-Id", Long.toString(delivery.id()))
                .header("X-FM-Signature", signature(seconds, body, target.signingSecret(), target.previousSigningSecret()))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        switch (target.authKind()) {
            case "HEADER" -> request.header(target.authName(), target.authSecret());
            case "BASIC" -> request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (target.authName() + ":" + target.authSecret()).getBytes(StandardCharsets.UTF_8)));
            default -> {
                // none
            }
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return new Outcome(true, status, null);
            }
            String said = oneLine(response.body());
            if (status >= 300 && status < 400) {
                return new Outcome(false, status, "a redirect, not followed");
            }
            return new Outcome(false, status, "HTTP " + status + (said.isEmpty() ? "" : ": " + said));
        } catch (HttpConnectTimeoutException e) {
            return new Outcome(false, null, "no connection within " + timeout.toSeconds() + " s");
        } catch (HttpTimeoutException e) {
            return new Outcome(false, null, "no answer within " + timeout.toSeconds() + " s");
        } catch (IOException e) {
            return new Outcome(false, null, "unreachable: " + oneLine(e.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(false, null, "interrupted: the application is stopping");
        }
    }

    /** {@code t=<seconds>,v1=<hex>[,v1=<hex under the previous secret>]}. */
    static String signature(long seconds, String body, String secret, String previousSecret) {
        String signed = seconds + "." + body;
        StringBuilder header = new StringBuilder("t=").append(seconds).append(",v1=").append(hmac(secret, signed));
        if (previousSecret != null) {
            header.append(",v1=").append(hmac(previousSecret, signed));
        }
        return header.toString();
    }

    static String hmac(String secret, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    /** The first 200 characters of what a receiver said, on one line - never more of it is kept. */
    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        return line.length() <= 200 ? line : line.substring(0, 200);
    }
}
