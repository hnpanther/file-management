package com.hnp.filemanagement.s3api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code aws-chunked} decoder on the example AWS publishes for a streamed, signed upload
 * ("Signature Calculations for the Authorization Header: Transferring Payload in Multiple Chunks"):
 * its signatures, computed here, must be the ones AWS printed - and a body changed anywhere is refused.
 */
class AwsChunkedInputStreamTest {

    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
    private static final String DATE = "20130524T000000Z";
    private static final String SCOPE = "20130524/us-east-1/s3/aws4_request";
    private static final String SEED = "4f232c4386841ef735655705268965c44a0e4690baa4adea153f7db9fa80a0a9";
    private static final byte[] KEY = SigV4.signingKey(SECRET, "20130524", "us-east-1", "s3");

    @Test
    @DisplayName("AWS's own example: 64 KB and 1 KB of 'a', the signatures AWS printed, decoded whole")
    void theAwsExample() throws IOException {
        byte[] first = filled(65536);
        byte[] second = filled(1024);
        String s1 = SigV4.chunkSignature(KEY, DATE, SCOPE, SEED, sha256(first));
        String s2 = SigV4.chunkSignature(KEY, DATE, SCOPE, s1, sha256(second));
        String s3 = SigV4.chunkSignature(KEY, DATE, SCOPE, s2, sha256(new byte[0]));
        assertThat(s1).isEqualTo("ad80c730a21e5b8d04586a2213dd63b9a0e99e0e2307b0ade35a65485a288648");
        assertThat(s2).isEqualTo("0055627c9e194cb4542bae2aa5492e3c1575bbb81b612b7d234b86a503ef5497");
        assertThat(s3).isEqualTo("b6c6ea8a5354eaf15b3cb7646744f4275b71ea724fed81ceb9323e279d449df9");

        byte[] body = signedBody(new byte[][]{first, second}, null);
        assertThat(read(body, SigV4.STREAMING_SIGNED)).isEqualTo(concat(first, second));
    }

    @Test
    @DisplayName("signed: a changed byte, a changed signature, a chunk without one, a body cut short - all refused")
    void signedRefusals() {
        byte[] good = signedBody(new byte[][]{filled(100), filled(50)}, null);

        byte[] changedByte = good.clone();
        changedByte[indexOf(good, "\r\n") + 2 + 10] = 'b';
        assertThatThrownBy(() -> read(changedByte, SigV4.STREAMING_SIGNED)).isInstanceOf(IOException.class)
                .hasMessageContaining("signature");

        String text = new String(good, StandardCharsets.ISO_8859_1);
        byte[] changedSignature = text.replaceFirst("chunk-signature=[0-9a-f]", "chunk-signature=x")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> read(changedSignature, SigV4.STREAMING_SIGNED)).isInstanceOf(IOException.class);

        byte[] unsigned = text.replaceAll(";chunk-signature=[0-9a-f]{64}", "").getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> read(unsigned, SigV4.STREAMING_SIGNED)).isInstanceOf(IOException.class)
                .hasMessageContaining("without its signature");

        byte[] cut = Arrays.copyOf(good, good.length / 2);
        assertThatThrownBy(() -> read(cut, SigV4.STREAMING_SIGNED)).isInstanceOf(IOException.class);

        // The final chunk dropped: chunks whose signatures held, but no end - not a whole body.
        byte[] noEnd = Arrays.copyOf(good, text.lastIndexOf("0;chunk-signature="));
        assertThatThrownBy(() -> read(noEnd, SigV4.STREAMING_SIGNED)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("signed with a trailer: the checksum and its signature checked; either changed is refused")
    void signedTrailer() throws IOException {
        byte[] data = "the contract's bytes".getBytes(StandardCharsets.UTF_8);
        String crc = crc32(data);
        byte[] body = signedBody(new byte[][]{data}, "x-amz-checksum-crc32:" + crc);
        assertThat(read(body, SigV4.STREAMING_SIGNED_TRAILER)).isEqualTo(data);

        String text = new String(body, StandardCharsets.ISO_8859_1);
        byte[] otherChecksum = signedBody(new byte[][]{data}, "x-amz-checksum-crc32:AAAAAA==");
        assertThatThrownBy(() -> read(otherChecksum, SigV4.STREAMING_SIGNED_TRAILER)).isInstanceOf(IOException.class)
                .hasMessageContaining("crc32");
        byte[] otherSignature = text.replaceFirst("x-amz-trailer-signature:[0-9a-f]", "x-amz-trailer-signature:x")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> read(otherSignature, SigV4.STREAMING_SIGNED_TRAILER)).isInstanceOf(IOException.class)
                .hasMessageContaining("trailer's signature");
        byte[] noSignature = text.replaceFirst("x-amz-trailer-signature:[0-9a-f]{64}\r\n", "")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> read(noSignature, SigV4.STREAMING_SIGNED_TRAILER)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("unsigned with a trailer: crc32, sha256 checked against the bytes")
    void unsignedTrailer() throws IOException {
        byte[] data = filled(3000);
        String body = Integer.toHexString(2000) + "\r\n" + latin1(Arrays.copyOf(data, 2000)) + "\r\n"
                + Integer.toHexString(1000) + "\r\n" + latin1(Arrays.copyOfRange(data, 2000, 3000)) + "\r\n"
                + "0\r\n" + "x-amz-checksum-crc32:" + crc32(data) + "\r\n"
                + "x-amz-checksum-sha256:" + Base64.getEncoder().encodeToString(sha256Bytes(data)) + "\r\n\r\n";
        assertThat(read(body.getBytes(StandardCharsets.ISO_8859_1), SigV4.STREAMING_UNSIGNED_TRAILER)).isEqualTo(data);

        String wrong = body.replace("x-amz-checksum-crc32:" + crc32(data), "x-amz-checksum-crc32:" + crc32(new byte[]{1}));
        assertThatThrownBy(() -> read(wrong.getBytes(StandardCharsets.ISO_8859_1), SigV4.STREAMING_UNSIGNED_TRAILER))
                .isInstanceOf(IOException.class);
        String hugeChunk = "ffffffff\r\n";
        assertThatThrownBy(() -> read(hugeChunk.getBytes(StandardCharsets.ISO_8859_1), SigV4.STREAMING_UNSIGNED_TRAILER))
                .isInstanceOf(IOException.class).hasMessageContaining("bytes");
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] read(byte[] body, String mode) throws IOException {
        S3RequestContext context = new S3RequestContext(null, KEY, DATE, SCOPE, SEED, mode);
        try (InputStream in = new AwsChunkedInputStream(new ByteArrayInputStream(body), context)) {
            return in.readAllBytes();
        }
    }

    /** A signed aws-chunked body as a client sends it, with a signed trailer when one is given. */
    private static byte[] signedBody(byte[][] chunks, String trailer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String previous = SEED;
        for (byte[] chunk : chunks) {
            previous = SigV4.chunkSignature(KEY, DATE, SCOPE, previous, sha256(chunk));
            out.writeBytes((Integer.toHexString(chunk.length) + ";chunk-signature=" + previous + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(chunk);
            out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        previous = SigV4.chunkSignature(KEY, DATE, SCOPE, previous, sha256(new byte[0]));
        out.writeBytes(("0;chunk-signature=" + previous + "\r\n").getBytes(StandardCharsets.US_ASCII));
        if (trailer != null) {
            String signature = SigV4.trailerSignature(KEY, DATE, SCOPE, previous,
                    SigV4.sha256Hex((trailer + "\n").getBytes(StandardCharsets.UTF_8)));
            out.writeBytes((trailer + "\r\n" + "x-amz-trailer-signature:" + signature + "\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        out.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private static byte[] filled(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Bytes(bytes));
    }

    private static byte[] sha256Bytes(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        long value = crc.getValue();
        return Base64.getEncoder().encodeToString(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static int indexOf(byte[] bytes, String text) {
        return new String(bytes, StandardCharsets.ISO_8859_1).indexOf(text);
    }
}
