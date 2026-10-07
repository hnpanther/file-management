package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The S3 surface's XML beyond its errors ({@link S3Errors}): the listings ({@code ListObjectsV2},
 * {@code ListObjects}) as S3 writes them, and the {@code DeleteObjects} request read - with no
 * DTD, no external entity, no XInclude: a body a client sends is never a way to make this server
 * read a file or a URL (XXE).
 */
final class S3Xml {

    static final String NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";
    static final String DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** The most objects one {@code DeleteObjects} names, as in S3. */
    static final int MAX_DELETE_OBJECTS = 1000;

    private S3Xml() {
    }

    /** How a listing was asked for, echoed in its answer. */
    record ListRequest(String bucket, String prefix, String delimiter, int maxKeys, boolean urlEncoded, boolean owner,
                       String continuationToken, String startAfter, String marker) {
    }

    // ---------------------------------------------------------------- listings

    /** {@code ListObjectsV2}'s answer; the next continuation token is the last key returned, opaque. */
    static String listV2(ListRequest asked, S3ObjectService.Listing page) {
        StringBuilder xml = new StringBuilder(DECLARATION).append("<ListBucketResult xmlns=\"").append(NAMESPACE).append("\">");
        element(xml, "Name", asked.bucket());
        element(xml, "Prefix", encoded(asked.prefix(), asked));
        if (asked.delimiter() != null && !asked.delimiter().isEmpty()) {
            element(xml, "Delimiter", encoded(asked.delimiter(), asked));
        }
        element(xml, "MaxKeys", String.valueOf(asked.maxKeys()));
        element(xml, "KeyCount", String.valueOf(page.entries().size()));
        element(xml, "IsTruncated", String.valueOf(page.truncated()));
        if (asked.continuationToken() != null) {
            element(xml, "ContinuationToken", asked.continuationToken());
        }
        if (page.truncated()) {
            element(xml, "NextContinuationToken", tokenOf(page.entries().getLast().key()));
        }
        if (asked.startAfter() != null) {
            element(xml, "StartAfter", encoded(asked.startAfter(), asked));
        }
        if (asked.urlEncoded()) {
            element(xml, "EncodingType", "url");
        }
        entries(xml, page, asked);
        return xml.append("</ListBucketResult>").toString();
    }

    /** {@code ListObjects} (version 1): a marker instead of a token, the owner always. */
    static String listV1(ListRequest asked, S3ObjectService.Listing page) {
        StringBuilder xml = new StringBuilder(DECLARATION).append("<ListBucketResult xmlns=\"").append(NAMESPACE).append("\">");
        element(xml, "Name", asked.bucket());
        element(xml, "Prefix", encoded(asked.prefix(), asked));
        element(xml, "Marker", encoded(asked.marker() == null ? "" : asked.marker(), asked));
        if (page.truncated() && asked.delimiter() != null && !asked.delimiter().isEmpty()) {
            // S3 names the next marker only with a delimiter; without one the client takes the last key.
            element(xml, "NextMarker", encoded(page.entries().getLast().key(), asked));
        }
        element(xml, "MaxKeys", String.valueOf(asked.maxKeys()));
        if (asked.delimiter() != null && !asked.delimiter().isEmpty()) {
            element(xml, "Delimiter", encoded(asked.delimiter(), asked));
        }
        element(xml, "IsTruncated", String.valueOf(page.truncated()));
        if (asked.urlEncoded()) {
            element(xml, "EncodingType", "url");
        }
        entries(xml, page, asked);
        return xml.append("</ListBucketResult>").toString();
    }

    private static void entries(StringBuilder xml, S3ObjectService.Listing page, ListRequest asked) {
        for (S3ListingRepository.Row row : page.entries()) {
            if (row.prefix()) {
                xml.append("<CommonPrefixes>");
                element(xml, "Prefix", encoded(row.key(), asked));
                xml.append("</CommonPrefixes>");
                continue;
            }
            xml.append("<Contents>");
            element(xml, "Key", encoded(row.key(), asked));
            element(xml, "LastModified", row.lastModified() == null ? "" : ISO_MILLIS.format(row.lastModified()));
            element(xml, "ETag", S3Controller.eTagOf(row.checksumSha256()));
            element(xml, "Size", String.valueOf(row.size()));
            element(xml, "StorageClass", "STANDARD");
            if (asked.owner()) {
                xml.append("<Owner><ID>file-management</ID><DisplayName>file-management</DisplayName></Owner>");
            }
            xml.append("</Contents>");
        }
    }

    /** A page's continuation: the last key returned, as base64url - opaque to the client, as S3's is. */
    static String tokenOf(String lastKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(lastKey.getBytes(StandardCharsets.UTF_8));
    }

    /** The key a continuation token stands for; an {@link IllegalArgumentException} for one this did not write. */
    static String keyOfToken(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }

    /**
     * A key as the answer carries it: as stored, or percent-encoded when the client asked for
     * {@code encoding-type=url} - the AWS CLI does, by default - with a space as {@code %20}, never
     * {@code +}, which the clients decode back to a space; the {@code /} between names kept, as S3
     * keeps it.
     */
    private static String encoded(String value, ListRequest asked) {
        if (!asked.urlEncoded()) {
            return value;
        }
        StringBuilder out = new StringBuilder();
        String[] segments = value.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(SigV4.uriEncode(segments[i]));
        }
        return out.toString();
    }

    private static void element(StringBuilder xml, String name, String value) {
        if (value != null && !S3Errors.carriable(value)) {
            // What S3 asks a client to do for such a key; never an answer the client cannot parse.
            throw new InvalidDataException("a key or prefix holds a character XML 1.0 cannot carry: list with encoding-type=url");
        }
        xml.append('<').append(name).append('>').append(S3Errors.xml(value)).append("</").append(name).append('>');
    }

    // ---------------------------------------------------------------- DeleteObjects

    /** One object a {@code DeleteObjects} names. */
    record ToDelete(String key, String versionId) {
    }

    /** A {@code DeleteObjects} request: the objects, and whether only the failures are answered. */
    record DeleteRequest(List<ToDelete> objects, boolean quiet) {
    }

    /** Thrown for a body that is not a {@code Delete} S3 would take - S3's {@code MalformedXML}. */
    static final class MalformedXml extends RuntimeException {
        MalformedXml(String message) {
            super(message);
        }
    }

    static DeleteRequest readDelete(byte[] body) {
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            document = builder.parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            throw new MalformedXml("the body is not XML S3 would take: " + e.getMessage());
        }
        Element root = document.getDocumentElement();
        if (!"Delete".equals(root.getLocalName())) {
            throw new MalformedXml("the root element is not Delete");
        }
        boolean quiet = false;
        List<ToDelete> objects = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            if ("Quiet".equals(child.getLocalName())) {
                quiet = "true".equalsIgnoreCase(child.getTextContent().trim());
            } else if ("Object".equals(child.getLocalName())) {
                String key = text(child, "Key");
                if (key == null || key.isEmpty()) {
                    throw new MalformedXml("an Object without its Key");
                }
                String versionId = text(child, "VersionId");
                objects.add(new ToDelete(key, versionId == null || versionId.isEmpty() ? null : versionId));
            }
        }
        if (objects.isEmpty() || objects.size() > MAX_DELETE_OBJECTS) {
            throw new MalformedXml("a Delete names 1 to " + MAX_DELETE_OBJECTS + " objects, this one " + objects.size());
        }
        return new DeleteRequest(objects, quiet);
    }

    private static String text(Element parent, String name) {
        NodeList found = parent.getElementsByTagNameNS("*", name);
        return found.getLength() == 0 ? null : found.item(0).getTextContent();
    }

    /** One outcome of a {@code DeleteObjects}: deleted, or why not. */
    record DeleteOutcome(ToDelete object, S3Errors.Error error, String message) {
    }

    static String deleteResult(List<DeleteOutcome> outcomes, boolean quiet) {
        StringBuilder xml = new StringBuilder(DECLARATION).append("<DeleteResult xmlns=\"").append(NAMESPACE).append("\">");
        for (DeleteOutcome outcome : outcomes) {
            if (outcome.error() == null) {
                if (quiet) {
                    continue;
                }
                xml.append("<Deleted>");
                element(xml, "Key", outcome.object().key());
                if (outcome.object().versionId() != null) {
                    element(xml, "VersionId", outcome.object().versionId());
                }
                xml.append("</Deleted>");
            } else {
                xml.append("<Error>");
                element(xml, "Key", outcome.object().key());
                if (outcome.object().versionId() != null) {
                    element(xml, "VersionId", outcome.object().versionId());
                }
                element(xml, "Code", outcome.error().code());
                element(xml, "Message", outcome.message());
                xml.append("</Error>");
            }
        }
        return xml.append("</DeleteResult>").toString();
    }
}
