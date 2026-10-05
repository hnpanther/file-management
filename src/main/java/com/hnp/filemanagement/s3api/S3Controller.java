package com.hnp.filemanagement.s3api;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API v2, S3-compatible (roadmap 9.10): {@code PutObject}, {@code GetObject} (and {@code HeadObject},
 * which Spring serves from the same handler without the body), {@code DeleteObject}, and a folder
 * created or deleted by its {@code key/} - at {@code /s3/{bucket}/{key}}, path-style, authenticated
 * by {@link S3AuthenticationFilter} with an S3 key's Signature V4. What each does with the tree is
 * {@link S3ObjectService}'s; this reads the request and writes S3's answer.
 */
@RestController
@RequestMapping(S3Controller.MOUNT)
public class S3Controller {

    /** Where the surface is served; a host of its own maps onto it (roadmap 9.10.3). */
    public static final String MOUNT = "/s3";

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
    public ResponseEntity<Void> put(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("bucket") String bucket,
                                    @PathVariable("key") String rawKey,
                                    @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                    HttpServletRequest request) throws IOException {
        S3RequestContext context = context(request);
        String key = keyOf(rawKey);
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

    /** {@code GetObject}; {@code HeadObject} is this without the body. {@code Range} is Spring's. */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @GetMapping("/{bucket}/{*key}")
    public ResponseEntity<Resource> get(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @PathVariable("bucket") String bucket,
                                        @PathVariable("key") String rawKey,
                                        @RequestParam(value = "versionId", required = false) String versionId) {
        String key = keyOf(rawKey);
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

    /** {@code DeleteObject}: a file with every version, or an empty folder's {@code key/}. */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @DeleteMapping("/{bucket}/{*key}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("bucket") String bucket,
                                       @PathVariable("key") String rawKey,
                                       HttpServletRequest request) {
        String key = keyOf(rawKey);
        globalGeneralLogging.detail("s3 delete bucket=" + bucket + ", key=" + key);
        objectService.delete(bucket, key, context(request).apiKey(), userDetails.getId());
        return ResponseEntity.noContent().build();
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
