package com.hnp.filemanagement.content;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * {@link TikaClient} over the JDK's HTTP client: the document streamed to Tika with its length (never
 * held whole), the answer parsed as it arrives ({@link TikaReading#parse}), each lane with its own
 * time limit - a little above the container's own, so that Tika's answer, not the client's, says a
 * document took too long.
 */
public class HttpTikaClient implements TikaClient {

    /** What a {@code Content-Type} hint may be: a media type and nothing else, never a header of its own. */
    private static final Pattern MEDIA_TYPE = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");
    private static final Pattern EXTENSION = Pattern.compile("[a-z0-9]{1,16}");
    private static final int ERROR_BODY_BYTES = 2048;

    private final HttpClient http;
    private final URI textBase;
    private final URI ocrBase;
    private final Duration textTimeout;
    private final Duration ocrTimeout;

    public HttpTikaClient(URI textBase, URI ocrBase, Duration connectTimeout, Duration textTimeout, Duration ocrTimeout) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.textBase = textBase;
        this.ocrBase = ocrBase;
        this.textTimeout = textTimeout;
        this.ocrTimeout = ocrTimeout;
    }

    @Override
    public TikaReading read(Lane lane, String preset, Supplier<InputStream> body, long size, String contentType,
                            String extension, long maxCharacters) throws IOException, InterruptedException {
        URI base = lane == Lane.OCR ? ocrBase : textBase;
        if (base == null) {
            throw new Unavailable("no URL for Tika's " + lane + " container", null);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(TikaClient.endpoint(base, preset))
                .timeout(lane == Lane.OCR ? ocrTimeout : textTimeout)
                .header("Accept", "text/xml")
                .PUT(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(body), size));
        String type = contentType == null ? "" : contentType.trim().toLowerCase(Locale.ROOT);
        if (MEDIA_TYPE.matcher(type).matches() && !"application/octet-stream".equals(type)) {
            request.header("Content-Type", type);
        }
        String ext = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        if (EXTENSION.matcher(ext).matches()) {
            // A name for Tika to tell a text from a CSV by - the file's own never leaves the application.
            request.header("Content-Disposition", "attachment; filename=\"document." + ext + "\"");
        }

        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new Unavailable("Tika's " + lane + " container at " + base + " could not be reached: " + e, e);
        } catch (HttpTimeoutException e) {
            throw new IOException("Tika's " + lane + " container did not answer within its time limit", e);
        } catch (UncheckedIOException e) {
            // The document's own bytes could not be read - the store, not Tika.
            throw e.getCause();
        } catch (IOException e) {
            if (isConnectionFailure(e)) {
                throw new Unavailable("Tika's " + lane + " container at " + base + " could not be reached: " + e, e);
            }
            // The store failing while the document's bytes were sent is the store's absence, not the
            // document's fault: handed on as itself, so the reading is put back with no attempt spent.
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof com.hnp.filemanagement.shared.exception.StorageUnavailableException unavailable) {
                    throw unavailable;
                }
            }
            throw e;
        }

        try (InputStream answer = response.body()) {
            int status = response.statusCode();
            if (status == 200) {
                return TikaReading.parse(answer, maxCharacters);
            }
            String said = said(answer);
            if (status == 429) {
                throw new Unavailable("Tika's " + lane + " container is busy (429)", null);
            }
            if (status == 422 || status == 415) {
                throw new Refused("Tika could not parse the document (" + status + ")" + said);
            }
            throw new IOException("Tika's " + lane + " container answered " + status + said);
        }
    }

    /** The first lines of an error's body, for the reason - Tika's are short JSON. */
    private static String said(InputStream answer) {
        try {
            String text = new String(answer.readNBytes(ERROR_BODY_BYTES), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").strip();
            return text.isEmpty() ? "" : ": " + (text.length() > 200 ? text.substring(0, 200) : text);
        } catch (IOException e) {
            return "";
        }
    }

    /** A connection refused, reset before an answer, or closed by a server going away. */
    private static boolean isConnectionFailure(IOException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof java.nio.channels.UnresolvedAddressException
                    || cause instanceof java.net.UnknownHostException || cause instanceof java.net.NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }
}
