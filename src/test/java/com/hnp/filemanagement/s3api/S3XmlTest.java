package com.hnp.filemanagement.s3api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The S3 surface's own XML: a {@code Delete} body read as S3 reads it and never as a way into the
 * server (no DTD, no entity, no XInclude), and the listings written as the SDKs parse them.
 */
class S3XmlTest {

    private static S3Xml.DeleteRequest read(String xml) {
        return S3Xml.readDelete(xml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a Delete read with or without its namespace, Quiet, VersionId, and its keys exactly as written")
    void readsADelete() {
        S3Xml.DeleteRequest asked = read("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Delete xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <Quiet>true</Quiet>
                  <Object><Key>a/with space &amp; more.pdf</Key></Object>
                  <Object><Key>a/زرد.pdf</Key><VersionId>abc</VersionId></Object>
                </Delete>""");
        assertThat(asked.quiet()).isTrue();
        assertThat(asked.objects()).containsExactly(
                new S3Xml.ToDelete("a/with space & more.pdf", null), new S3Xml.ToDelete("a/زرد.pdf", "abc"));

        S3Xml.DeleteRequest plain = read("<Delete><Object><Key>k.pdf</Key><VersionId></VersionId></Object></Delete>");
        assertThat(plain.quiet()).isFalse();
        assertThat(plain.objects()).containsExactly(new S3Xml.ToDelete("k.pdf", null));
    }

    @Test
    @DisplayName("a DOCTYPE is refused outright: no external entity reads a file, no entity expands a billion times")
    void noDoctype() {
        assertThatThrownBy(() -> read("""
                <?xml version="1.0"?>
                <!DOCTYPE Delete [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <Delete><Object><Key>&xxe;</Key></Object></Delete>""")).isInstanceOf(S3Xml.MalformedXml.class);
        assertThatThrownBy(() -> read("""
                <?xml version="1.0"?>
                <!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">]>
                <Delete><Object><Key>&lol2;</Key></Object></Delete>""")).isInstanceOf(S3Xml.MalformedXml.class);
        assertThatThrownBy(() -> read("""
                <Delete xmlns:xi="http://www.w3.org/2001/XInclude">
                  <Object><Key><xi:include href="file:///etc/passwd" parse="text"/></Key></Object></Delete>"""))
                .as("XInclude is not processed: the Key is empty").isInstanceOf(S3Xml.MalformedXml.class);
    }

    @Test
    @DisplayName("what is not a Delete S3 would take is MalformedXML")
    void malformed() {
        for (String body : List.of("", "not xml", "<Delete>", "<Remove><Object><Key>k</Key></Object></Remove>",
                "<Delete></Delete>", "<Delete><Object><VersionId>v</VersionId></Object></Delete>",
                "<Delete><Object><Key></Key></Object></Delete>")) {
            assertThatThrownBy(() -> read(body)).as(body).isInstanceOf(S3Xml.MalformedXml.class);
        }
        StringBuilder many = new StringBuilder("<Delete>");
        for (int i = 0; i <= S3Xml.MAX_DELETE_OBJECTS; i++) {
            many.append("<Object><Key>k").append(i).append("</Key></Object>");
        }
        assertThatThrownBy(() -> read(many + "</Delete>")).isInstanceOf(S3Xml.MalformedXml.class);
        assertThat(read(many.toString().replace("<Object><Key>k1000</Key></Object>", "") + "</Delete>").objects())
                .hasSize(S3Xml.MAX_DELETE_OBJECTS);
    }

    @Test
    @DisplayName("a listing escapes what XML must, and with encoding-type=url percent-encodes keys - a space as %20")
    void listingEncodes() {
        S3ObjectService.Listing page = new S3ObjectService.Listing(List.of(
                new S3ListingRepository.Row("a/x & <y>/", true, 0, null, null, null),
                new S3ListingRepository.Row("a/with space.pdf", false, 12, "ab".repeat(32), Instant.parse("2026-01-02T03:04:05.678Z"), "v")),
                true);
        S3Xml.ListRequest plain = new S3Xml.ListRequest("bk", "a/", "/", 2, false, false, null, null, null);
        String xml = S3Xml.listV2(plain, page);
        assertThat(xml).contains("<Prefix>a/x &amp; &lt;y&gt;/</Prefix>", "<Key>a/with space.pdf</Key>",
                "<LastModified>2026-01-02T03:04:05.678Z</LastModified>", "<Size>12</Size>", "<KeyCount>2</KeyCount>",
                "<IsTruncated>true</IsTruncated>", "<NextContinuationToken>" + S3Xml.tokenOf("a/with space.pdf") + "</NextContinuationToken>");
        assertThat(xml).doesNotContain("<Owner>", "<EncodingType>");

        S3Xml.ListRequest encoded = new S3Xml.ListRequest("bk", "a/", "/", 2, true, true, null, null, null);
        assertThat(S3Xml.listV2(encoded, page)).contains("<Prefix>a/</Prefix>", "<Delimiter>/</Delimiter>",
                "<Key>a/with%20space.pdf</Key>", "<Prefix>a/x%20%26%20%3Cy%3E/</Prefix>",
                "<EncodingType>url</EncodingType>", "<Owner>");

        assertThat(S3Xml.listV1(new S3Xml.ListRequest("bk", "", "/", 2, false, true, null, null, "m"), page))
                .contains("<Marker>m</Marker>", "<NextMarker>a/with space.pdf</NextMarker>");
        assertThat(S3Xml.listV1(new S3Xml.ListRequest("bk", "", null, 2, false, true, null, null, null), page))
                .contains("<Marker></Marker>").doesNotContain("NextMarker");
    }

    @Test
    @DisplayName("a continuation token is the last key, opaque and round-tripping any key")
    void tokens() {
        for (String key : List.of("a", "a/with space.pdf", "a/زرد ۱۴۰۴.pdf", "x+y=z/?.pdf")) {
            String token = S3Xml.tokenOf(key);
            assertThat(token).matches("[A-Za-z0-9_-]+");
            assertThat(S3Xml.keyOfToken(token)).isEqualTo(key);
        }
        assertThatThrownBy(() -> S3Xml.keyOfToken("not*base64!")).isInstanceOf(IllegalArgumentException.class);
    }
}
