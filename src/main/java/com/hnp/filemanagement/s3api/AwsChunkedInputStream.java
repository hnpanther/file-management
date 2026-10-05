package com.hnp.filemanagement.s3api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/**
 * An {@code aws-chunked} request body, decoded - what S3 clients send a streamed upload as
 * ({@code Content-Encoding: aws-chunked}): chunks of {@code <hex size>[;chunk-signature=<sig>]\r\n
 * <bytes>\r\n}, a last chunk of size 0, then for the {@code -TRAILER} forms trailing headers (a
 * checksum, and in the signed form its signature) and an empty line.
 *
 * <p>In the signed forms every chunk's signature is checked against the one before it - the seed
 * being the request's own - and a trailer's against the last chunk's; a checksum trailer
 * ({@code x-amz-checksum-crc32}, {@code -crc32c}, {@code -sha1}, {@code -sha256}) is checked against
 * the bytes. Any mismatch, or a malformed chunk, is an {@link IOException} before the end of the
 * body is reached - the upload is spooled before it is stored, so a body that fails here stores
 * nothing.
 */
public class AwsChunkedInputStream extends InputStream {

    /** A chunk claiming more than this is refused - no S3 client sends one. */
    static final long MAX_CHUNK = 64L * 1024 * 1024;

    private final InputStream in;
    private final boolean signed;
    private final boolean trailer;
    private final S3RequestContext context;

    private String previousSignature;
    private long remaining;
    private boolean finished;
    private MessageDigest chunkDigest;
    private String chunkSignature;
    private final Map<String, Checksum> checksums = new LinkedHashMap<>();
    private final MessageDigest sha1 = digest("SHA-1");
    private final MessageDigest sha256 = digest("SHA-256");

    public AwsChunkedInputStream(InputStream in, S3RequestContext context) {
        this.in = in;
        this.context = context;
        String mode = context.payloadHash();
        this.signed = SigV4.STREAMING_SIGNED.equals(mode) || SigV4.STREAMING_SIGNED_TRAILER.equals(mode);
        this.trailer = SigV4.STREAMING_SIGNED_TRAILER.equals(mode) || SigV4.STREAMING_UNSIGNED_TRAILER.equals(mode);
        this.previousSignature = context.seedSignature();
        checksums.put("x-amz-checksum-crc32", new CRC32());
        checksums.put("x-amz-checksum-crc32c", new CRC32C());
    }

    /** Whether this payload declaration is one of the chunked forms this stream reads. */
    public static boolean isChunked(String payloadHash) {
        return SigV4.STREAMING_SIGNED.equals(payloadHash) || SigV4.STREAMING_SIGNED_TRAILER.equals(payloadHash)
                || SigV4.STREAMING_UNSIGNED_TRAILER.equals(payloadHash);
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (finished) {
            return -1;
        }
        if (remaining == 0) {
            nextChunk();
            if (finished) {
                return -1;
            }
        }
        int n = in.read(buffer, offset, (int) Math.min(length, remaining));
        if (n == -1) {
            throw new IOException("the body ended inside a chunk");
        }
        chunkDigest.update(buffer, offset, n);
        for (Checksum checksum : checksums.values()) {
            checksum.update(buffer, offset, n);
        }
        sha1.update(buffer, offset, n);
        sha256.update(buffer, offset, n);
        remaining -= n;
        if (remaining == 0) {
            endChunk();
        }
        return n;
    }

    /** Reads a chunk's header; a chunk of size 0 ends the data, and the trailer follows it. */
    private void nextChunk() throws IOException {
        String header = line();
        String sizePart = header;
        chunkSignature = null;
        int semicolon = header.indexOf(';');
        if (semicolon >= 0) {
            sizePart = header.substring(0, semicolon);
            String extension = header.substring(semicolon + 1).trim();
            if (extension.startsWith("chunk-signature=")) {
                chunkSignature = extension.substring("chunk-signature=".length());
            }
        }
        long size;
        try {
            size = Long.parseLong(sizePart.trim(), 16);
        } catch (NumberFormatException e) {
            throw new IOException("a chunk's size is not hexadecimal");
        }
        if (size < 0 || size > MAX_CHUNK) {
            throw new IOException("a chunk of " + size + " bytes");
        }
        if (signed && chunkSignature == null) {
            throw new IOException("a chunk without its signature");
        }
        chunkDigest = digest("SHA-256");
        remaining = size;
        if (size == 0) {
            verifyChunk();
            readTrailer();
            finished = true;
        }
    }

    private void endChunk() throws IOException {
        verifyChunk();
        if (!line().isEmpty()) {
            throw new IOException("a chunk not followed by CRLF");
        }
    }

    private void verifyChunk() throws IOException {
        if (!signed) {
            return;
        }
        String expected = SigV4.chunkSignature(context.signingKey(), context.amzDate(), context.scope(), previousSignature,
                hex(chunkDigest.digest()));
        if (!SigV4.equal(expected, chunkSignature)) {
            throw new IOException("a chunk's signature does not match");
        }
        previousSignature = chunkSignature;
    }

    private void readTrailer() throws IOException {
        if (!trailer) {
            // The signed, trailer-less form ends with the empty last chunk and its CRLF.
            line();
            return;
        }
        Map<String, String> trailers = new LinkedHashMap<>();
        StringBuilder canonical = new StringBuilder();
        String trailerSignature = null;
        for (String line = line(); !line.isEmpty(); line = line()) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("a malformed trailing header");
            }
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("x-amz-trailer-signature")) {
                trailerSignature = value;
            } else {
                trailers.put(name, value);
                canonical.append(name).append(':').append(value).append('\n');
            }
        }
        if (signed) {
            String expected = SigV4.trailerSignature(context.signingKey(), context.amzDate(), context.scope(),
                    previousSignature, SigV4.sha256Hex(canonical.toString().getBytes(StandardCharsets.UTF_8)));
            if (!SigV4.equal(expected, trailerSignature)) {
                throw new IOException("the trailer's signature does not match");
            }
        }
        for (Map.Entry<String, String> entry : trailers.entrySet()) {
            String actual = switch (entry.getKey()) {
                case "x-amz-checksum-crc32", "x-amz-checksum-crc32c" -> base64OfInt(checksums.get(entry.getKey()).getValue());
                case "x-amz-checksum-sha1" -> Base64.getEncoder().encodeToString(sha1.digest());
                case "x-amz-checksum-sha256" -> Base64.getEncoder().encodeToString(sha256.digest());
                default -> null;
            };
            if (actual != null && !actual.equals(entry.getValue())) {
                throw new IOException("the body does not match its " + entry.getKey());
            }
        }
    }

    /** One line up to CRLF, without it - bounded, since a header line is short. */
    private String line() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new IOException("the body ended inside a chunk header");
            }
            if (previous == '\r' && b == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            line.write(b);
            if (line.size() > 4096) {
                throw new IOException("a chunk header longer than 4 KB");
            }
            previous = b;
        }
    }

    private static String base64OfInt(long value) {
        byte[] bytes = {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " is not available", e);
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
