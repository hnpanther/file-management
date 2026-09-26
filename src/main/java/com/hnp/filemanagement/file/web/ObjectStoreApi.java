package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.file.domain.ObjectListingDTO;
import com.hnp.filemanagement.file.domain.ObjectMetadataDTO;
import com.hnp.filemanagement.file.domain.ObjectStoreService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API v2: buckets and objects, shaped after S3 so that an integrator recognises it (roadmap 9.3).
 *
 * <pre>
 *   GET    /api/v2/{bucket}?prefix=&amp;delimiter=/&amp;max-keys=&amp;continuation-token=
 *   GET    /api/v2/{bucket}/{key}            honours Range
 *   GET    /api/v2/{bucket}/{key}?metadata   (or HEAD on the URL above)
 *   PUT    /api/v2/{bucket}/{key}
 *   DELETE /api/v2/{bucket}/{key}
 * </pre>
 *
 * <p><b>S3-style, not S3-compatible, and the difference is not cosmetic.</b> There is no Signature
 * V4, so {@code aws s3} and the AWS SDKs will not talk to this — they sign every request and this
 * authenticates with {@code Authorization: Bearer fmk_…}. Responses are JSON rather than S3's XML
 * for the same reason: there is no client parsing them that expects otherwise. What is borrowed is
 * the shape — bucket, key, prefix, delimiter, continuation token, {@code ETag} — because that is
 * what makes an unfamiliar API readable at a glance (roadmap 9.4).
 *
 * <p><b>Held by API keys, not by people.</b> {@code API_KEY} is an authority every key carries and
 * no user does; an administrator is admitted as well, which is how these can be exercised with Basic
 * credentials from a terminal. Which objects any of them actually reach is decided by folder access
 * inside the service, and for a key that means the key's own scopes — never its creator's.
 */
@RestController
@RequestMapping("/api/v2")
@Tag(name = "Object store",
        description = "Buckets and objects, S3-style. A bucket is a top-level folder; a key is the "
                + "path beneath it and carries the version as a segment. Not S3-compatible: there is "
                + "no Signature V4, so the AWS CLI and SDKs will not connect.")
@SecurityRequirement(name = OpenApiConfig.API_KEY_SCHEME)
public class ObjectStoreApi {

    static final String KEY_DESCRIPTION = "The path beneath the bucket, slashes included, for example "
            + "`SubCategory/Tag/report/v2/report.pdf` to read a stored version or "
            + "`SubCategory/Tag/report/report.pdf` to write the next one. Written into the URL as-is: "
            + "the slashes are separators and must not be percent-encoded, which is why Swagger's "
            + "*Try it out* cannot send one - it encodes them as `%2F`, which is refused. Use curl.";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final ObjectStoreService objectStoreService;

    public ObjectStoreApi(GlobalGeneralLogging globalGeneralLogging, ObjectStoreService objectStoreService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.objectStoreService = objectStoreService;
    }

    /**
     * Lists the keys in a bucket.
     *
     * @param delimiter {@code /} stops the listing at each folder boundary and reports the
     *                  boundaries as {@code commonPrefixes}, which is how a flat key space is
     *                  browsed; leaving it out returns every key beneath the prefix
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(summary = "List the keys in a bucket",
            description = "With `delimiter=/` the listing stops at each folder boundary and reports "
                    + "the boundaries as `commonPrefixes`. Without it, every key beneath the prefix "
                    + "is returned. Only what the caller may read is listed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The page of keys"),
            @ApiResponse(responseCode = "403", description = "The bucket is outside this caller's folders", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such bucket", content = @io.swagger.v3.oas.annotations.media.Content)})
    @GetMapping("{bucket}")
    public ObjectListingDTO listObjects(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @Parameter(description = "A top-level folder") @PathVariable("bucket") String bucket,
                                        @RequestParam(value = "prefix", required = false) String prefix,
                                        @RequestParam(value = "delimiter", required = false) String delimiter,
                                        @RequestParam(value = "max-keys", required = false) Integer maxKeys,
                                        @RequestParam(value = "continuation-token", required = false) String token) {

        globalGeneralLogging.detail("list objects in bucket=" + bucket + ", prefix=" + prefix);

        return objectStoreService.list(bucket, prefix, delimiter, maxKeys, token, userDetails.getId());
    }

    /**
     * Downloads one object.
     *
     * <p>The key is everything after the bucket, slashes included, which is why it is bound with
     * {@code **} rather than a path variable — a key is one string that happens to contain
     * separators, exactly as in S3.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(summary = "Download an object",
            description = "The key must name a version, for example "
                    + "`SubCategory/Tag/report/v2/report.pdf`. A `Range` header is honoured and "
                    + "answered with 206. Add `?metadata` to get the object's metadata as a JSON "
                    + "body instead of its bytes (the same fields `HEAD` answers as headers).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The bytes, with `ETag`, `Last-Modified` and `x-fm-version`"),
            @ApiResponse(responseCode = "206", description = "The requested `Range` of the bytes"),
            @ApiResponse(responseCode = "400", description = "The key does not name a version", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such object", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Outside this caller's folders", content = @io.swagger.v3.oas.annotations.media.Content)})
    @GetMapping("{bucket}/{*key}")
    public ResponseEntity<?> getObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("bucket") String bucket,
                                       @Parameter(description = KEY_DESCRIPTION) @PathVariable("key") String rawKey) {

        String key = keyOf(rawKey);
        globalGeneralLogging.detail("get object bucket=" + bucket + ", key=" + key);

        ObjectMetadataDTO metadata = objectStoreService.head(bucket, key, userDetails.getId());
        FileDownloadDTO download = objectStoreService.get(bucket, key, userDetails.getId());

        // Served as the extension's type, as an attachment, with nosniff - the v1 rules (issue 13).
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(download.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.ETAG, metadata.eTag())
                .header("x-fm-version", String.valueOf(metadata.version()))
                .lastModified(metadata.lastModified().atZone(java.time.ZoneId.systemDefault()))
                .body(download.getResource());
    }

    /**
     * {@code HEAD}: the object's headers without its bytes, as S3 answers it.
     *
     * <p>Explicit rather than left to Spring, which would answer a {@code HEAD} by running the
     * {@code GET} and discarding the body — reading the whole file from disk to throw it away. This
     * resolves the row and answers from it.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(summary = "An object's headers, without its bytes",
            description = "The same `ETag`, `Last-Modified`, `Content-Type`, `Content-Length` and "
                    + "`x-fm-version` the download carries, and no body. For the same as a JSON "
                    + "body, `GET` the object's URL with `?metadata`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The headers"),
            @ApiResponse(responseCode = "404", description = "No such object", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Outside this caller's folders", content = @io.swagger.v3.oas.annotations.media.Content)})
    @RequestMapping(value = "{bucket}/{*key}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headObjectHeaders(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                  @PathVariable("bucket") String bucket,
                                                  @Parameter(description = KEY_DESCRIPTION) @PathVariable("key") String rawKey) {

        String key = keyOf(rawKey);
        globalGeneralLogging.detail("head object bucket=" + bucket + ", key=" + key);

        ObjectMetadataDTO metadata = objectStoreService.head(bucket, key, userDetails.getId());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.contentType()))
                .contentLength(metadata.size())
                .header(HttpHeaders.ETAG, metadata.eTag())
                .header("x-fm-version", String.valueOf(metadata.version()))
                .lastModified(metadata.lastModified().atZone(java.time.ZoneId.systemDefault()))
                .build();
    }

    /**
     * The object's metadata as a readable body, for a caller who wants it as JSON rather than as
     * headers.
     *
     * <p>Hidden from the OpenAPI document on purpose: a document has one operation per path and
     * method, and this shares both with the download, so describing it would have replaced the
     * download's entry — which is what happened in the first version. The download's own
     * description points here instead.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(hidden = true)
    @GetMapping(value = "{bucket}/{*key}", params = "metadata")
    public ObjectMetadataDTO headObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @PathVariable("bucket") String bucket,
                                        @Parameter(description = KEY_DESCRIPTION) @PathVariable("key") String rawKey) {

        String key = keyOf(rawKey);
        globalGeneralLogging.detail("head object bucket=" + bucket + ", key=" + key);

        return objectStoreService.head(bucket, key, userDetails.getId());
    }

    /**
     * Stores the next version of a file.
     *
     * <p>The body is the file itself, as an S3 {@code PUT} sends it. The key must <em>not</em> name
     * a version: versions here are immutable, so the server assigns the next one and says which in
     * {@code x-fm-version} and in the canonical key it answers with.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(summary = "Store the next version of a file",
            description = "The body is the file. The key must **not** name a version — versions are "
                    + "immutable, so the server assigns the next one and answers with it in "
                    + "`x-fm-version` and in the canonical key. Requires write access to the folder.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Stored; the body is the new object's metadata"),
            @ApiResponse(responseCode = "400", description = "The key is malformed, or does not end in a tag folder", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "No write access to that folder", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such bucket or folder", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "409", description = "The key names a version (versions are immutable), or the file name is taken by a file under a sibling folder", content = @io.swagger.v3.oas.annotations.media.Content)})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The file's bytes, as-is - not multipart and not JSON",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    @PutMapping("{bucket}/{*key}")
    public ResponseEntity<ObjectMetadataDTO> putObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                       @PathVariable("bucket") String bucket,
                                                       @Parameter(description = KEY_DESCRIPTION) @PathVariable("key") String rawKey,
                                                       @RequestBody(required = false) byte[] body,
                                                       @Parameter(description = "Stored as the object's content type; "
                                                               + "`application/octet-stream` when absent")
                                                       @RequestHeader(value = HttpHeaders.CONTENT_TYPE,
                                                               required = false) String contentType) {

        String key = keyOf(rawKey);
        globalGeneralLogging.detail("put object bucket=" + bucket + ", key=" + key + ", bytes=" + (body == null ? 0 : body.length));

        String objectName = key.substring(key.lastIndexOf('/') + 1);
        ObjectMetadataDTO stored = objectStoreService.put(bucket, key,
                new RawBodyMultipartFile(objectName, contentType, body), userDetails.getId());

        return ResponseEntity.created(java.net.URI.create("/api/v2/" + bucket + "/" + stored.key()))
                .header(HttpHeaders.ETAG, stored.eTag())
                .header("x-fm-version", String.valueOf(stored.version()))
                .body(stored);
    }

    /** Removes one format of one version; removing the last version removes the file. */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(summary = "Delete one version of an object",
            description = "Removing the last version removes the file. Requires write access.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "400", description = "The key does not name a version", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "404", description = "No such object", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "No write access to that folder", content = @io.swagger.v3.oas.annotations.media.Content)})
    @DeleteMapping("{bucket}/{*key}")
    public ResponseEntity<Void> deleteObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                             @PathVariable("bucket") String bucket,
                                             @Parameter(description = KEY_DESCRIPTION) @PathVariable("key") String rawKey) {

        String key = keyOf(rawKey);
        globalGeneralLogging.detail("delete object bucket=" + bucket + ", key=" + key);

        objectStoreService.delete(bucket, key, userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The key as the caller wrote it.
     *
     * <p>A key contains slashes and is one string, which is what {@code {*key}} is for: it captures
     * the rest of the path, slashes included, <em>decoded</em>. The first version of this cut the key
     * out of {@code getRequestURI()}, which is the raw request line — so a Persian file name arrived
     * as {@code %DA%AF%D8%B2…} and was stored under that name, on disk and in {@code file_info}.
     * {@code getRequestURI()} is never decoded; a captured variable always is.
     *
     * <p>The capture keeps its leading slash, which the key does not want.
     */
    private static String keyOf(String captured) {
        return captured == null ? "" : captured.replaceFirst("^/+", "");
    }
}
