package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.file.domain.ContentTypes;
import com.hnp.filemanagement.file.domain.DownloadChannel;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.file.web.DownloadAudit;
import com.hnp.filemanagement.file.web.SpooledRequestBody;
import com.hnp.filemanagement.file.web.UploadTempDirectory;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * API v2, S3-compatible (roadmap 9.10): {@code PutObject}, {@code GetObject}, {@code HeadObject},
 * {@code DeleteObject}, the bucket requests clients ask first, and a folder
 * created or deleted by its {@code key/} - at {@code /s3/{bucket}/{key}}, path-style, authenticated
 * by {@link S3AuthenticationFilter} with an S3 key's Signature V4. What each does with the tree is
 * {@link S3ObjectService}'s; this reads the request and writes S3's answer.
 */
@RestController
@RequestMapping(S3Controller.MOUNT)
public class S3Controller {

    /** Where the surface is served; a host of its own maps onto it (roadmap 9.10.3). */
    public static final String MOUNT = "/s3";

    /** The one region the surface answers as; a signature's own region is accepted whatever it is. */
    static final String REGION = "us-east-1";
    private static final String NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/";
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final S3ObjectService objectService;
    private final UploadPolicyService uploadPolicyService;
    private final UploadTempDirectory uploadTempDirectory;
    private final DownloadAudit downloadAudit;
    private final GlobalGeneralLogging globalGeneralLogging;

    public S3Controller(S3ObjectService objectService, UploadPolicyService uploadPolicyService,
                        UploadTempDirectory uploadTempDirectory, DownloadAudit downloadAudit,
                        GlobalGeneralLogging globalGeneralLogging) {
        this.objectService = objectService;
        this.uploadPolicyService = uploadPolicyService;
        this.uploadTempDirectory = uploadTempDirectory;
        this.downloadAudit = downloadAudit;
        this.globalGeneralLogging = globalGeneralLogging;
    }

    /** {@code PutObject}, or a folder's {@code key/} with an empty body. */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @PutMapping("/{bucket}/{*key}")
    public ResponseEntity<?> put(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("bucket") String bucket,
                                    @PathVariable("key") String rawKey,
                                    @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                    HttpServletRequest request) throws IOException {
        S3RequestContext context = context(request);
        String key = keyOf(rawKey);
        if (key.isEmpty()) {
            // CreateBucket: a bucket is a top-level folder, made on the web.
            return notImplemented(request);
        }
        if (key.endsWith("/")) {
            globalGeneralLogging.detail("s3 create folder bucket=" + bucket + ", key=" + key);
            objectService.createFolder(bucket, key, context.apiKey(), userDetails.getId());
            return ResponseEntity.ok().eTag("\"d41d8cd98f00b204e9800998ecf8427e\"").build();
        }

        long declared = AwsChunkedInputStream.isChunked(context.payloadHash())
                ? headerLong(request, "x-amz-decoded-content-length") : request.getContentLengthLong();
        globalGeneralLogging.detail("s3 put bucket=" + bucket + ", key=" + key + ", bytes=" + declared);
        String objectName = key.substring(key.lastIndexOf('/') + 1);
        S3ObjectService.Stored stored;
        try (SpooledRequestBody body = SpooledRequestBody.spool(objectName, contentType, payload(request, context),
                declared, uploadPolicyService.serverCapBytes(), uploadTempDirectory.path())) {
            stored = objectService.put(bucket, key, body, ifNoneMatch, context.apiKey(), userDetails.getId());
        }
        return ResponseEntity.ok()
                .eTag(eTagOf(stored.checksumSha256()))
                .header("x-amz-version-id", stored.versionId())
                .build();
    }

    /** {@code ListBuckets}: the top-level folders the key can see. */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @GetMapping({"", "/"})
    public ResponseEntity<String> listBuckets(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        globalGeneralLogging.detail("s3 list buckets");
        StringBuilder xml = new StringBuilder(XML_DECLARATION)
                .append("<ListAllMyBucketsResult xmlns=\"").append(NAMESPACE).append("\">")
                .append("<Owner><ID>").append(userDetails.getId()).append("</ID><DisplayName>")
                .append(S3Errors.xml(userDetails.getUsername())).append("</DisplayName></Owner><Buckets>");
        for (S3ObjectService.Bucket bucket : objectService.buckets(userDetails.getId())) {
            xml.append("<Bucket><Name>").append(S3Errors.xml(bucket.name())).append("</Name><CreationDate>")
                    .append(bucket.createdAt() == null ? "" : ISO_MILLIS.format(bucket.createdAt()))
                    .append("</CreationDate></Bucket>");
        }
        xml.append("</Buckets></ListAllMyBucketsResult>");
        return xmlOk(xml.toString());
    }

    /**
     * {@code GetObject}; {@code Range} is Spring's. A request naming the bucket alone is a bucket's:
     * {@link #bucket}. A {@code HEAD} is {@link #head}'s, never this.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @GetMapping("/{bucket}/{*key}")
    public ResponseEntity<?> get(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                 @PathVariable("bucket") String bucket,
                                 @PathVariable("key") String rawKey,
                                 @RequestParam(value = "versionId", required = false) String versionId,
                                 HttpServletRequest request) {
        String key = keyOf(rawKey);
        if (key.isEmpty()) {
            return bucket(userDetails, bucket, request);
        }
        globalGeneralLogging.detail("s3 get bucket=" + bucket + ", key=" + key);
        FileDownloadDTO download = objectService.get(bucket, key, versionId, userDetails.getId());
        FileDetails revision = objectService.revision(download.getFileDetailsId());
        downloadAudit.served(download, DownloadChannel.S3);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .eTag(eTagOf(revision.getChecksumSha256()))
                .lastModified(revision.getCreatedAt())
                .header("x-amz-version-id", revision.getExternalId())
                .header("Accept-Ranges", "bytes")
                .body(download.getResource());
    }

    /**
     * {@code HeadObject} and {@code HeadBucket}, explicit rather than Spring's {@code HEAD} through the
     * {@code GET}: that would open the bytes to discard them, and record a download that never was.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @RequestMapping(value = "/{bucket}/{*key}", method = RequestMethod.HEAD)
    public ResponseEntity<?> head(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                  @PathVariable("bucket") String bucket,
                                  @PathVariable("key") String rawKey,
                                  @RequestParam(value = "versionId", required = false) String versionId,
                                  HttpServletRequest request) {
        String key = keyOf(rawKey);
        if (key.isEmpty()) {
            return bucket(userDetails, bucket, request);
        }
        globalGeneralLogging.detail("s3 head bucket=" + bucket + ", key=" + key);
        FileDetails revision = objectService.head(bucket, key, versionId, userDetails.getId());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ContentTypes.servedTypeFor(revision.getFileExtension())
                        .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)))
                .contentLength(revision.getFileSize())
                .eTag(eTagOf(revision.getChecksumSha256()))
                .lastModified(revision.getCreatedAt())
                .header("x-amz-version-id", revision.getExternalId())
                .header("Accept-Ranges", "bytes")
                .build();
    }

    /** {@code DeleteObject}: a file with every version, or an empty folder's {@code key/}. */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @DeleteMapping("/{bucket}/{*key}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("bucket") String bucket,
                                       @PathVariable("key") String rawKey,
                                       HttpServletRequest request) {
        String key = keyOf(rawKey);
        if (key.isEmpty()) {
            // DeleteBucket: never through a key.
            return notImplemented(request);
        }
        globalGeneralLogging.detail("s3 delete bucket=" + bucket + ", key=" + key);
        objectService.delete(bucket, key, context(request).apiKey(), userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- a bucket

    /**
     * What a request naming only a bucket may ask: {@code HeadBucket} (a {@code HEAD}),
     * {@code GetBucketLocation} ({@code ?location} - n8n's S3 node asks it before every operation),
     * {@code GetBucketVersioning} ({@code ?versioning} - always on: a title written twice is a new
     * version). The rest - listing first of all - is not served yet, and says so with S3's 501 rather
     * than a misleading 400. A bucket the key cannot see is a 404, as for an object.
     */
    private ResponseEntity<String> bucket(UserDetailsImpl userDetails, String bucket, HttpServletRequest request) {
        globalGeneralLogging.detail("s3 bucket request bucket=" + bucket + ", query=" + request.getQueryString());
        objectService.requireBucket(bucket, userDetails.getId());
        if ("HEAD".equals(request.getMethod())) {
            return ResponseEntity.ok().header("x-amz-bucket-region", REGION).build();
        }
        if (request.getParameter("location") != null) {
            // The region named rather than left empty (S3's form for us-east-1): n8n reads the
            // element's text, and an empty one leaves it without a region.
            return xmlOk(XML_DECLARATION + "<LocationConstraint xmlns=\"" + NAMESPACE + "\">" + REGION
                    + "</LocationConstraint>");
        }
        if (request.getParameter("versioning") != null) {
            return xmlOk(XML_DECLARATION + "<VersioningConfiguration xmlns=\"" + NAMESPACE
                    + "\"><Status>Enabled</Status></VersioningConfiguration>");
        }
        return notImplemented(request);
    }

    private static ResponseEntity<String> notImplemented(HttpServletRequest request) {
        S3Errors.Error error = S3Errors.Error.NOT_IMPLEMENTED;
        return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_XML)
                .body(S3Errors.body(error, error.message(), request.getRequestURI()));
    }

    private static ResponseEntity<String> xmlOk(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(body);
    }

    // ---------------------------------------------------------------- the body

    /**
     * The upload's bytes as they were signed: a chunked body decoded and each chunk's signature
     * checked; a body whose hash was signed checked against it at its end; an unsigned one as it is.
     */
    private static InputStream payload(HttpServletRequest request, S3RequestContext context) throws IOException {
        InputStream raw = request.getInputStream();
        String declared = context.payloadHash();
        if (AwsChunkedInputStream.isChunked(declared)) {
            return new AwsChunkedInputStream(raw, context);
        }
        if (declared != null && declared.matches("[0-9a-f]{64}")) {
            return new SignedBodyInputStream(raw, declared);
        }
        return raw;
    }

    /** The body's SHA-256 compared with the signed {@code x-amz-content-sha256} when it ends. */
    static final class SignedBodyInputStream extends FilterInputStream {

        private final String expected;
        private boolean checked;

        SignedBodyInputStream(InputStream in, String expected) {
            super(new DigestInputStream(in, sha256()));
            this.expected = expected;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b == -1) {
                check();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n == -1) {
                check();
            }
            return n;
        }

        private void check() throws IOException {
            if (checked) {
                return;
            }
            checked = true;
            String actual = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
            if (!actual.equals(expected)) {
                throw new ContentHashMismatch();
            }
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** The body is not the one whose hash was signed. */
    static final class ContentHashMismatch extends IOException {
        ContentHashMismatch() {
            super("the body does not match x-amz-content-sha256");
        }
    }

    // ---------------------------------------------------------------- small things

    private static S3RequestContext context(HttpServletRequest request) {
        return (S3RequestContext) request.getAttribute(S3AuthenticationFilter.CONTEXT);
    }

    /** {@code {*key}} captures the leading slash. */
    private static String keyOf(String rawKey) {
        return rawKey.startsWith("/") ? rawKey.substring(1) : rawKey;
    }

    private static long headerLong(HttpServletRequest request, String name) {
        try {
            return Long.parseLong(request.getHeader(name));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Not an MD5, as S3's is for an object written in one request - taken from the SHA-256 the
     * application keeps for every revision: stable while the object is, different when it changes,
     * and the same in an upload's answer as in a download's (roadmap 9.10.9).
     */
    static String eTagOf(String checksumSha256) {
        return "\"" + (checksumSha256 == null ? "0" : checksumSha256.substring(0, Math.min(32, checksumSha256.length()))) + "\"";
    }
}
