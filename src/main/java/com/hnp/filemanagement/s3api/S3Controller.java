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
import com.hnp.filemanagement.shared.metadata.MetadataRules;
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
import org.springframework.web.bind.annotation.PostMapping;
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
import java.util.List;
import java.util.Set;

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
    private final MetadataRules metadataRules;
    private final S3MultipartService multipartService;

    public S3Controller(S3ObjectService objectService, UploadPolicyService uploadPolicyService,
                        UploadTempDirectory uploadTempDirectory, DownloadAudit downloadAudit,
                        GlobalGeneralLogging globalGeneralLogging, MetadataRules metadataRules,
                        S3MultipartService multipartService) {
        this.objectService = objectService;
        this.uploadPolicyService = uploadPolicyService;
        this.uploadTempDirectory = uploadTempDirectory;
        this.downloadAudit = downloadAudit;
        this.globalGeneralLogging = globalGeneralLogging;
        this.metadataRules = metadataRules;
        this.multipartService = multipartService;
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
        if (request.getParameter("uploadId") != null) {
            if (asksWhatIsNotServed(request, Set.of("uploadId", "partNumber")) || request.getParameter("partNumber") == null
                    || request.getHeader("x-amz-copy-source") != null) {
                // UploadPartCopy, or a part without its number: not served.
                return notImplemented(request);
            }
            return uploadPart(bucket, key, request, context);
        }
        if (asksWhatIsNotServed(request, Set.of()) || request.getHeader("x-amz-copy-source") != null) {
            // PutObjectTagging, PutObjectAcl, a part without its upload, CopyObject, ...: never an upload of the body.
            return notImplemented(request);
        }
        if (key.endsWith("/")) {
            globalGeneralLogging.detail("s3 create folder bucket=" + bucket + ", key=" + key);
            objectService.createFolder(bucket, key, context.apiKey(), userDetails.getId());
            return ResponseEntity.ok().eTag("\"d41d8cd98f00b204e9800998ecf8427e\"").build();
        }

        // Read and checked before the body is: a document the rules refuse costs no upload.
        String metadata = S3Metadata.ofRequest(request, metadataRules).orElse(null);
        long declared = AwsChunkedInputStream.isChunked(context.payloadHash())
                ? headerLong(request, "x-amz-decoded-content-length") : request.getContentLengthLong();
        globalGeneralLogging.detail("s3 put bucket=" + bucket + ", key=" + key + ", bytes=" + declared);
        String objectName = key.substring(key.lastIndexOf('/') + 1);
        S3ObjectService.Stored stored;
        try (SpooledRequestBody body = SpooledRequestBody.spool(objectName, contentType, payload(request, context),
                declared, uploadPolicyService.serverCapBytes(), uploadTempDirectory.path())) {
            stored = objectService.put(bucket, key, body, ifNoneMatch, metadata, context.apiKey(), userDetails.getId());
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
        if (request.getParameter("uploadId") != null) {
            if (asksWhatIsNotServed(request, Set.of("uploadId", "max-parts", "part-number-marker"))) {
                return notImplemented(request);
            }
            return listParts(bucket, key, request);
        }
        if (asksWhatIsNotServed(request, READ_PARAMETERS)) {
            // GetObjectTagging, GetObjectAcl, a part, ...: never the object's bytes in their place.
            return notImplemented(request);
        }
        globalGeneralLogging.detail("s3 get bucket=" + bucket + ", key=" + key);
        FileDownloadDTO download = objectService.get(bucket, key, versionId, userDetails.getId());
        FileDetails revision = objectService.revision(download.getFileDetailsId());
        downloadAudit.served(download, DownloadChannel.S3);
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .eTag(eTagOf(revision.getChecksumSha256()))
                .lastModified(revision.getCreatedAt())
                .header("x-amz-version-id", revision.getExternalId())
                .header("Accept-Ranges", "bytes");
        S3Metadata.answer(answer, revision.getMetadata());
        return answer.body(download.getResource());
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
        if (asksWhatIsNotServed(request, READ_PARAMETERS)) {
            return notImplemented(request);
        }
        globalGeneralLogging.detail("s3 head bucket=" + bucket + ", key=" + key);
        FileDetails revision = objectService.head(bucket, key, versionId, userDetails.getId());
        ResponseEntity.BodyBuilder answer = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(ContentTypes.servedTypeFor(revision.getFileExtension())
                        .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)))
                .contentLength(revision.getFileSize())
                .eTag(eTagOf(revision.getChecksumSha256()))
                .lastModified(revision.getCreatedAt())
                .header("x-amz-version-id", revision.getExternalId())
                .header("Accept-Ranges", "bytes");
        S3Metadata.answer(answer, revision.getMetadata());
        return answer.build();
    }

    /**
     * {@code DeleteObject}: every version of the key's object, or the one {@code versionId} names,
     * or an empty folder's {@code key/} ({@link S3ObjectService#delete(String, String, String,
     * com.hnp.filemanagement.identity.domain.ApiKey, int)}).
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @DeleteMapping("/{bucket}/{*key}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("bucket") String bucket,
                                    @PathVariable("key") String rawKey,
                                    @RequestParam(value = "versionId", required = false) String versionId,
                                    HttpServletRequest request) {
        String key = keyOf(rawKey);
        if (key.isEmpty()) {
            // DeleteBucket: never through a key.
            return notImplemented(request);
        }
        if (request.getParameter("uploadId") != null) {
            if (asksWhatIsNotServed(request, Set.of("uploadId"))) {
                return notImplemented(request);
            }
            globalGeneralLogging.detail("s3 abort multipart upload bucket=" + bucket + ", key=" + key);
            multipartService.abort(bucket, key, request.getParameter("uploadId"), context(request).apiKey());
            return ResponseEntity.noContent().build();
        }
        if (asksWhatIsNotServed(request, Set.of("versionId"))) {
            // DeleteObjectTagging, AbortMultipartUpload, ...: never a deletion of the object itself.
            return notImplemented(request);
        }
        globalGeneralLogging.detail("s3 delete bucket=" + bucket + ", key=" + key
                + (versionId == null ? "" : ", versionId=" + versionId));
        objectService.delete(bucket, key, versionId, context(request).apiKey(), userDetails.getId());
        ResponseEntity.HeadersBuilder<?> answer = ResponseEntity.noContent();
        if (versionId != null) {
            answer.header("x-amz-version-id", versionId);
        }
        return answer.build();
    }

    /**
     * A {@code POST} to an object: {@code CreateMultipartUpload} ({@code ?uploads}) and
     * {@code CompleteMultipartUpload} ({@code ?uploadId}) - {@link S3MultipartService}. Anything else -
     * a restore, a select, a browser's form upload, a {@code POST} to a bucket without {@code ?delete}
     * - is not served, and said so with S3's 501 rather than a bare 405 the clients do not explain.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @PostMapping("/{bucket}/{*key}")
    public ResponseEntity<String> post(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("bucket") String bucket,
                                       @PathVariable("key") String rawKey,
                                       @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                       HttpServletRequest request) throws IOException {
        String key = keyOf(rawKey);
        if (key.isEmpty() || key.endsWith("/")) {
            return notImplemented(request);
        }
        S3RequestContext context = context(request);
        if (request.getParameter("uploads") != null && !asksWhatIsNotServed(request, Set.of("uploads"))) {
            // Read and checked now, as S3 takes an object's metadata: with the upload's beginning.
            String metadata = S3Metadata.ofRequest(request, metadataRules).orElse(null);
            globalGeneralLogging.detail("s3 create multipart upload bucket=" + bucket + ", key=" + key);
            String uploadId = multipartService.create(bucket, key, contentType, metadata, context.apiKey(), userDetails.getId());
            return xmlOk(S3Xml.initiated(bucket, key, uploadId));
        }
        if (request.getParameter("uploadId") != null && !asksWhatIsNotServed(request, Set.of("uploadId"))) {
            byte[] body = readBounded(request, context, MAX_COMPLETE_BODY);
            List<S3Xml.CompletedPart> parts = S3Xml.readComplete(body);
            globalGeneralLogging.detail("s3 complete multipart upload bucket=" + bucket + ", key=" + key + ", parts=" + parts.size());
            S3ObjectService.Stored stored = multipartService.complete(bucket, key, request.getParameter("uploadId"), parts,
                    ifNoneMatch, context.apiKey(), userDetails.getId());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .header("x-amz-version-id", stored.versionId())
                    .body(S3Xml.completed(request.getRequestURL().toString(), bucket, key, eTagOf(stored.checksumSha256())));
        }
        return notImplemented(request);
    }

    /** Ten thousand parts and their tags, and the markup: what S3 itself takes. */
    private static final int MAX_COMPLETE_BODY = 2 * 1024 * 1024;

    /** {@code UploadPart}: the part's bytes as they were signed, to its own file - its MD5 is its entity tag. */
    private ResponseEntity<String> uploadPart(String bucket, String key, HttpServletRequest request, S3RequestContext context)
            throws IOException {
        int partNumber;
        try {
            partNumber = Integer.parseInt(request.getParameter("partNumber"));
        } catch (NumberFormatException e) {
            throw new com.hnp.filemanagement.shared.exception.InvalidDataException("partNumber is a number");
        }
        long declared = AwsChunkedInputStream.isChunked(context.payloadHash())
                ? headerLong(request, "x-amz-decoded-content-length") : request.getContentLengthLong();
        globalGeneralLogging.detail("s3 upload part bucket=" + bucket + ", key=" + key + ", part=" + partNumber
                + ", bytes=" + declared);
        String etag;
        try (InputStream in = payload(request, context)) {
            etag = multipartService.uploadPart(bucket, key, request.getParameter("uploadId"), partNumber, in, declared,
                    request.getHeader("Content-MD5"), context.apiKey());
        }
        return ResponseEntity.ok().eTag(etag).build();
    }

    /** {@code ListParts}: a page of an upload's parts, up to 1,000 - S3's default and most. */
    private ResponseEntity<String> listParts(String bucket, String key, HttpServletRequest request) {
        int maxParts = Math.max(1, Math.min(1000, intParameter(request, "max-parts", 1000)));
        int marker = Math.max(0, intParameter(request, "part-number-marker", 0));
        globalGeneralLogging.detail("s3 list parts bucket=" + bucket + ", key=" + key);
        S3MultipartService.PartsPage page = multipartService.listParts(bucket, key, request.getParameter("uploadId"),
                marker, maxParts, context(request).apiKey());
        return xmlOk(S3Xml.listParts(bucket, key, page.upload().uploadId(), marker, maxParts, page.parts(), page.truncated()));
    }

    private static int intParameter(HttpServletRequest request, String name, int fallback) {
        String value = request.getParameter(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new com.hnp.filemanagement.shared.exception.InvalidDataException(name + " is a number");
        }
    }

    /** A request body read whole, at most {@code max} bytes, and to its end - so a signed body's hash is checked. */
    private static byte[] readBounded(HttpServletRequest request, S3RequestContext context, int max) throws IOException {
        try (InputStream in = payload(request, context)) {
            byte[] body = in.readNBytes(max + 1);
            if (body.length > max) {
                throw new S3Xml.MalformedXml("a body of more than " + max + " bytes");
            }
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return body;
        }
    }

    // ---------------------------------------------------------------- a bucket

    /**
     * What a request naming only a bucket may ask: {@code HeadBucket} (a {@code HEAD}),
     * {@code GetBucketLocation} ({@code ?location} - n8n's S3 node asks it before every operation),
     * {@code GetBucketVersioning} ({@code ?versioning} - always on: a title written twice is a new
     * version), and a listing ({@link #list}, 2.12.0). Any other sub-resource - uploads, versions,
     * acl, policy, tagging, ... - says it is not served with S3's 501, rather than a misleading 400.
     * A bucket the key cannot see is a 404, as for an object.
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
        if (request.getParameter("uploads") != null) {
            if (asksWhatIsNotServed(request, Set.of("uploads", "prefix", "key-marker", "upload-id-marker", "max-uploads",
                    "encoding-type")) || request.getParameter("delimiter") != null) {
                return notImplemented(request);
            }
            return listUploads(userDetails, bucket, request);
        }
        if (asksWhatIsNotServed(request, LISTING_PARAMETERS)) {
            // A sub-resource - uploads, versions, acl, policy, tagging, ... - not served here.
            return notImplemented(request);
        }
        return list(userDetails, bucket, request);
    }

    /** {@code ListMultipartUploads}: this key's uploads in progress in the bucket, up to 1,000 a page. */
    private ResponseEntity<String> listUploads(UserDetailsImpl userDetails, String bucket, HttpServletRequest request) {
        int maxUploads = Math.max(1, Math.min(1000, intParameter(request, "max-uploads", 1000)));
        String prefix = nullToEmpty(request.getParameter("prefix"));
        String keyMarker = nullToEmpty(request.getParameter("key-marker"));
        String uploadIdMarker = nullToEmpty(request.getParameter("upload-id-marker"));
        S3MultipartService.UploadsPage page = multipartService.listUploads(bucket, prefix, keyMarker, uploadIdMarker,
                maxUploads, context(request).apiKey(), userDetails.getId());
        return xmlOk(S3Xml.listUploads(bucket, prefix, keyMarker, uploadIdMarker, maxUploads, page.uploads(), page.truncated()));
    }

    /** What a listing may be asked with; any other parameter names a sub-resource this does not serve. */
    private static final Set<String> LISTING_PARAMETERS = Set.of("list-type", "prefix", "delimiter",
            "max-keys", "continuation-token", "start-after", "fetch-owner", "encoding-type", "marker");

    /**
     * {@code ListObjectsV2} ({@code list-type=2}) and {@code ListObjects}: one page of the keys
     * under a prefix, in S3's order, with {@code delimiter=/} one folder's children as common
     * prefixes ({@link S3ObjectService#list}).
     */
    private ResponseEntity<String> list(UserDetailsImpl userDetails, String bucket, HttpServletRequest request) {
        boolean v2 = "2".equals(request.getParameter("list-type"));
        String encodingType = request.getParameter("encoding-type");
        if (encodingType != null && !encodingType.equals("url")) {
            throw new com.hnp.filemanagement.shared.exception.InvalidDataException("encoding-type is url or nothing: " + encodingType);
        }
        int maxKeys = S3ObjectService.MAX_KEYS;
        if (request.getParameter("max-keys") != null) {
            try {
                maxKeys = Integer.parseInt(request.getParameter("max-keys"));
            } catch (NumberFormatException e) {
                throw new com.hnp.filemanagement.shared.exception.InvalidDataException("max-keys is a number");
            }
            if (maxKeys < 0) {
                throw new com.hnp.filemanagement.shared.exception.InvalidDataException("max-keys is not negative");
            }
        }
        String token = v2 ? request.getParameter("continuation-token") : null;
        String startAfter = v2 ? request.getParameter("start-after") : null;
        String marker = v2 ? null : request.getParameter("marker");
        String after;
        if (token != null) {
            try {
                after = S3Xml.keyOfToken(token);
            } catch (IllegalArgumentException e) {
                throw new com.hnp.filemanagement.shared.exception.InvalidDataException("the continuation token is not one this wrote");
            }
        } else {
            after = v2 ? startAfter : marker;
        }
        S3Xml.ListRequest asked = new S3Xml.ListRequest(bucket, nullToEmpty(request.getParameter("prefix")),
                request.getParameter("delimiter"), Math.min(maxKeys, S3ObjectService.MAX_KEYS), encodingType != null,
                !v2 || "true".equals(request.getParameter("fetch-owner")), token, startAfter, marker);
        globalGeneralLogging.detail("s3 list bucket=" + bucket + (v2 ? " (v2)" : " (v1)") + ", prefix=" + asked.prefix()
                + ", delimiter=" + asked.delimiter());

        S3ObjectService.Listing page = objectService.list(bucket, asked.prefix(), asked.delimiter(), after,
                asked.maxKeys(), userDetails.getId());
        return xmlOk(v2 ? S3Xml.listV2(asked, page) : S3Xml.listV1(asked, page));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * {@code DeleteObjects}: up to {@value S3Xml#MAX_DELETE_OBJECTS} keys in one request, each
     * deleted as {@code DeleteObject} deletes it - in a transaction of its own, so one refused
     * (no capability, no grant, a folder not empty) does not keep the others; each outcome is
     * answered, or with {@code Quiet} the failures only. The body's hash is checked against its
     * signature as an upload's is.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @PostMapping(value = {"/{bucket}", "/{bucket}/"}, params = "delete")
    public ResponseEntity<String> deleteObjects(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                @PathVariable("bucket") String bucket,
                                                HttpServletRequest request) throws IOException {
        S3RequestContext context = context(request);
        byte[] body;
        try (InputStream in = payload(request, context)) {
            body = in.readNBytes(MAX_DELETE_BODY + 1);
            // Read to its end, so a signed body's hash is checked (SignedBodyInputStream).
            if (body.length <= MAX_DELETE_BODY) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
        }
        if (body.length > MAX_DELETE_BODY) {
            throw new S3Xml.MalformedXml("a Delete body is at most " + MAX_DELETE_BODY + " bytes");
        }
        S3Xml.DeleteRequest asked = S3Xml.readDelete(body);
        objectService.requireBucket(bucket, userDetails.getId());
        globalGeneralLogging.detail("s3 delete objects bucket=" + bucket + ", count=" + asked.objects().size());

        java.util.List<S3Xml.DeleteOutcome> outcomes = new java.util.ArrayList<>();
        for (S3Xml.ToDelete object : asked.objects()) {
            outcomes.add(deleteOne(bucket, object, context, userDetails.getId()));
        }
        return xmlOk(S3Xml.deleteResult(outcomes, asked.quiet()));
    }

    /** A thousand keys of a thousand bytes, and their markup: what S3 itself takes. */
    private static final int MAX_DELETE_BODY = 2 * 1024 * 1024;

    private S3Xml.DeleteOutcome deleteOne(String bucket, S3Xml.ToDelete object, S3RequestContext context, int principalId) {
        try {
            objectService.delete(bucket, object.key(), object.versionId(), context.apiKey(), principalId);
            return new S3Xml.DeleteOutcome(object, null, null);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return new S3Xml.DeleteOutcome(object, S3Errors.Error.ACCESS_DENIED, S3Errors.Error.ACCESS_DENIED.message());
        } catch (S3ObjectService.FolderNotEmpty e) {
            return new S3Xml.DeleteOutcome(object, S3Errors.Error.FOLDER_NOT_EMPTY, S3Errors.Error.FOLDER_NOT_EMPTY.message());
        } catch (com.hnp.filemanagement.shared.exception.InvalidDataException e) {
            return new S3Xml.DeleteOutcome(object, S3Errors.Error.INVALID_ARGUMENT, e.getMessage());
        } catch (com.hnp.filemanagement.shared.exception.StorageUnavailableException e) {
            return new S3Xml.DeleteOutcome(object, S3Errors.Error.SERVICE_UNAVAILABLE, S3Errors.Error.SERVICE_UNAVAILABLE.message());
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(S3Controller.class).error("s3 delete objects: key " + object.key() + " failed", e);
            return new S3Xml.DeleteOutcome(object, S3Errors.Error.INTERNAL_ERROR, S3Errors.Error.INTERNAL_ERROR.message());
        }
    }

    /**
     * What a read of an object may be asked with. {@code response-*} asks S3 to answer other headers;
     * here they are ignored, which changes no byte.
     */
    private static final Set<String> READ_PARAMETERS = Set.of("versionId", "response-content-type",
            "response-content-language", "response-expires", "response-cache-control", "response-content-disposition",
            "response-content-encoding");

    /**
     * Whether the request names a parameter its handler does not understand - in S3, a sub-resource
     * ({@code ?tagging}, {@code ?acl}, {@code ?uploadId}, ...), an operation other than the one the
     * method alone names. Taken for the plain one it would be a deletion of the object for
     * {@code DeleteObjectTagging}, or a body of XML stored as a version for {@code PutObjectTagging};
     * so each handler takes only what it knows, and everything else is a 501. A pre-signed URL's own
     * parameters ({@code X-Amz-*}) and the {@code x-id} some SDKs add are not requests of anything.
     */
    static boolean asksWhatIsNotServed(HttpServletRequest request, Set<String> understood) {
        for (String name : request.getParameterMap().keySet()) {
            if (!understood.contains(name) && !name.regionMatches(true, 0, "x-amz-", 0, 6) && !name.equals("x-id")) {
                return true;
            }
        }
        return false;
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
