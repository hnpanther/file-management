package com.hnp.filemanagement.s3api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * AWS Signature Version 4, as S3 computes it (roadmap 9.10.2): the canonical request, the string to
 * sign, the signing key and the signature - what every S3 client sends, recomputed here from the
 * secret, which never travels. Pure functions; {@code S3AuthenticationFilter} applies them.
 *
 * <p>S3's own rules where SigV4 differs by service: the path is URI-encoded once (never twice), and
 * the payload's hash is the {@code x-amz-content-sha256} header's value as sent - a hex SHA-256,
 * {@code UNSIGNED-PAYLOAD}, or one of the {@code STREAMING-*} markers of a chunked body.
 */
public final class SigV4 {

    public static final String ALGORITHM = "AWS4-HMAC-SHA256";
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    public static final String STREAMING_SIGNED = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";
    public static final String STREAMING_SIGNED_TRAILER = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
    public static final String STREAMING_UNSIGNED_TRAILER = "STREAMING-UNSIGNED-PAYLOAD-TRAILER";
    /** The SHA-256 of nothing - a chunk's string to sign carries it. */
    public static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private SigV4() {
    }

    /** The parts of {@code Credential=AKID/20261005/us-east-1/s3/aws4_request} and the rest of the header. */
    public record Authorization(String accessKeyId, String date, String region, String service,
                                List<String> signedHeaders, String signature) {

        public String scope() {
            return date + "/" + region + "/" + service + "/aws4_request";
        }
    }

    /**
     * {@code AWS4-HMAC-SHA256 Credential=…, SignedHeaders=…, Signature=…}, or empty for anything else
     * - another scheme, a part missing, a scope not of S3.
     */
    public static Optional<Authorization> parse(String header) {
        if (header == null || !header.startsWith(ALGORITHM + " ")) {
            return Optional.empty();
        }
        Map<String, String> parts = new TreeMap<>();
        for (String part : header.substring(ALGORITHM.length()).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                parts.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return credential(parts.get("Credential"), parts.get("SignedHeaders"), parts.get("Signature"));
    }

    /** The same three parts as a pre-signed URL carries them, in its query. */
    public static Optional<Authorization> credential(String credential, String signedHeaders, String signature) {
        if (credential == null || signedHeaders == null || signature == null) {
            return Optional.empty();
        }
        String[] scope = credential.split("/");
        if (scope.length != 5 || !"aws4_request".equals(scope[4]) || !"s3".equals(scope[3])
                || !scope[1].matches("\\d{8}") || scope[0].isBlank() || !signature.matches("[0-9a-f]{64}")) {
            return Optional.empty();
        }
        List<String> headers = List.of(signedHeaders.toLowerCase(Locale.ROOT).split(";"));
        return Optional.of(new Authorization(scope[0], scope[1], scope[2], scope[3], headers, signature));
    }

    /**
     * The canonical request.
     *
     * @param rawPath   the path as it arrived, still percent-encoded
     * @param rawQuery  the query string as it arrived, or null
     * @param headers   every header of the request, by lower-case name; only the signed ones are used
     * @param excludedQueryParameter left out of the canonical query - a pre-signed URL's own
     *                  {@code X-Amz-Signature}; null for none
     */
    public static String canonicalRequest(String method, String rawPath, String rawQuery,
                                          Map<String, List<String>> headers, List<String> signedHeaders,
                                          String payloadHash, String excludedQueryParameter) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(method).append('\n')
                .append(canonicalPath(rawPath)).append('\n')
                .append(canonicalQuery(rawQuery, excludedQueryParameter)).append('\n');
        for (String name : signedHeaders) {
            List<String> values = headers.getOrDefault(name, List.of());
            canonical.append(name).append(':')
                    .append(String.join(",", values.stream().map(SigV4::trimHeaderValue).toList()))
                    .append('\n');
        }
        canonical.append('\n').append(String.join(";", signedHeaders)).append('\n').append(payloadHash);
        return canonical.toString();
    }

    public static String stringToSign(String amzDate, String scope, String canonicalRequest) {
        return ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] signingKey(String secret, String date, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    public static String signature(byte[] signingKey, String stringToSign) {
        return HexFormat.of().formatHex(hmac(signingKey, stringToSign));
    }

    /** A streamed chunk's signature, chained to the one before it. */
    public static String chunkSignature(byte[] signingKey, String amzDate, String scope, String previousSignature,
                                        String chunkSha256Hex) {
        return signature(signingKey, "AWS4-HMAC-SHA256-PAYLOAD\n" + amzDate + "\n" + scope + "\n" + previousSignature
                + "\n" + EMPTY_SHA256 + "\n" + chunkSha256Hex);
    }

    /** The trailer's signature, after the last chunk's. */
    public static String trailerSignature(byte[] signingKey, String amzDate, String scope, String previousSignature,
                                          String trailerSha256Hex) {
        return signature(signingKey, "AWS4-HMAC-SHA256-TRAILER\n" + amzDate + "\n" + scope + "\n" + previousSignature
                + "\n" + trailerSha256Hex);
    }

    /** Constant time: how long a comparison takes says nothing about how much of a guess was right. */
    public static boolean equal(String expected, String presented) {
        return presented != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
    }

    // ---------------------------------------------------------------- canonical forms

    /** Each segment decoded and encoded again as S3 does, {@code /} kept; an empty path is {@code /}. */
    static String canonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        StringBuilder out = new StringBuilder();
        String[] segments = rawPath.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(uriEncode(percentDecode(segments[i])));
        }
        return out.toString();
    }

    /** Names and values decoded, encoded again, sorted by name then value, a missing value as empty. */
    static String canonicalQuery(String rawQuery, String excluded) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = percentDecode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : percentDecode(pair.substring(eq + 1));
            if (name.equals(excluded)) {
                continue;
            }
            pairs.add(new String[]{uriEncode(name), uriEncode(value)});
        }
        pairs.sort(Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        List<String> joined = new ArrayList<>(pairs.size());
        for (String[] pair : pairs) {
            joined.add(pair[0] + "=" + pair[1]);
        }
        return String.join("&", joined);
    }

    /** RFC 3986's unreserved characters kept, everything else as {@code %XX} of its UTF-8 bytes. */
    static String uriEncode(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    /** {@code %XX} sequences to bytes, as UTF-8; a {@code +} stays a {@code +} - S3 sends a space as {@code %20}. */
    static String percentDecode(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && isHex(value.charAt(i + 1)) && isHex(value.charAt(i + 2))) {
                bytes.write(Integer.parseInt(value.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Spaces at the ends removed, runs of spaces inside folded to one. */
    private static String trimHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll(" +", " ");
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
