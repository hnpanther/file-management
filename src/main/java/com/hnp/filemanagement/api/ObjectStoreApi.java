package com.hnp.filemanagement.api;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.dto.ObjectListingDTO;
import com.hnp.filemanagement.dto.ObjectMetadataDTO;
import com.hnp.filemanagement.service.ObjectStoreService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.config.OpenApiConfig;
import com.hnp.filemanagement.util.RawBodyMultipartFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API v2: buckets and objects, shaped after S3 so that an integrator recognises it (roadmap 9.3).
 *
 * <pre>
 *   GET    /api/v2/{bucket}?prefix=&amp;delimiter=/&amp;max-keys=&amp;continuation-token=
 *   GET    /api/v2/{bucket}/{key}
 *   HEAD   /api/v2/{bucket}/{key}
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
            @ApiResponse(responseCode = "400", description = "No such bucket", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "The bucket is outside this caller's folders", content = @io.swagger.v3.oas.annotations.media.Content)})
    @GetMapping("{bucket}")
    public ObjectListingDTO listObjects(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @Parameter(description = "A top-level folder") @PathVariable("bucket") String bucket,
                                        @RequestParam(value = "prefix", required = false) String prefix,
                                        @RequestParam(value = "delimiter", required = false) String delimiter,
                                        @RequestParam(value = "max-keys", required = false) Integer maxKeys,
                                        @RequestParam(value = "continuation-token", required = false) String token,
                                        HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, ObjectStoreApi.class,
                "list objects in bucket=" + bucket + ", prefix=" + prefix);

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
                    + "`SubCategory/Tag/report/v2/report.pdf`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The bytes, with `ETag` and `x-fm-version`"),
            @ApiResponse(responseCode = "404", description = "No such object", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Outside this caller's folders", content = @io.swagger.v3.oas.annotations.media.Content)})
    @GetMapping("{bucket}/**")
    public ResponseEntity<?> getObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("bucket") String bucket,
                                       HttpServletRequest request) {

        String key = keyOf(bucket, request);
        globalGeneralLogging.controllerLogging(userDetails, request, ObjectStoreApi.class,
                "get object bucket=" + bucket + ", key=" + key);

        ObjectMetadataDTO metadata = objectStoreService.head(bucket, key, userDetails.getId());
        FileDownloadDTO download = objectStoreService.get(bucket, key, userDetails.getId());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + download.getFileName() + "\"")
                .header(HttpHeaders.ETAG, metadata.eTag())
                .header("x-fm-version", String.valueOf(metadata.version()))
                .body(download.getResource());
    }

    /**
     * The object's metadata without its bytes.
     *
     * <p>Mapped as {@code GET} with {@code HEAD} left to Spring, which answers a {@code HEAD} by
     * running the {@code GET} and dropping the body — so this exists as its own path only to give a
     * caller the metadata as a readable body when they ask for it.
     */
    //API_KEY
    @PreAuthorize("hasAuthority('API_KEY') || hasAuthority('ADMIN')")
    @Operation(summary = "An object's metadata, without its bytes",
            description = "Add `?metadata` to the object's URL. A plain `HEAD` on the download URL "
                    + "works too and returns the same headers with no body.")
    @GetMapping(value = "{bucket}/**", params = "metadata")
    public ObjectMetadataDTO headObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @PathVariable("bucket") String bucket,
                                        HttpServletRequest request) {

        String key = keyOf(bucket, request);
        globalGeneralLogging.controllerLogging(userDetails, request, ObjectStoreApi.class,
                "head object bucket=" + bucket + ", key=" + key);

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
            @ApiResponse(responseCode = "400", description = "The key names a version, or the bucket does not exist", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "No write access to that folder", content = @io.swagger.v3.oas.annotations.media.Content)})
    @PutMapping("{bucket}/**")
    public ResponseEntity<ObjectMetadataDTO> putObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                       @PathVariable("bucket") String bucket,
                                                       @RequestBody(required = false) byte[] body,
                                                       @RequestHeader(value = HttpHeaders.CONTENT_TYPE,
                                                               required = false) String contentType,
                                                       HttpServletRequest request) {

        String key = keyOf(bucket, request);
        globalGeneralLogging.controllerLogging(userDetails, request, ObjectStoreApi.class,
                "put object bucket=" + bucket + ", key=" + key + ", bytes=" + (body == null ? 0 : body.length));

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
            @ApiResponse(responseCode = "404", description = "No such object", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "No write access to that folder", content = @io.swagger.v3.oas.annotations.media.Content)})
    @DeleteMapping("{bucket}/**")
    public ResponseEntity<Void> deleteObject(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                             @PathVariable("bucket") String bucket,
                                             HttpServletRequest request) {

        String key = keyOf(bucket, request);
        globalGeneralLogging.controllerLogging(userDetails, request, ObjectStoreApi.class,
                "delete object bucket=" + bucket + ", key=" + key);

        objectStoreService.delete(bucket, key, userDetails.getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * The key, taken from the path rather than from a variable.
     *
     * <p>A key contains slashes and is one string, so it cannot be a path variable; Spring exposes
     * the part that matched {@code **} but only as the whole path, which the prefix has to be cut
     * off. Doing it here rather than in five handlers keeps the rule in one place.
     */
    private static String keyOf(String bucket, HttpServletRequest request) {
        String path = request.getRequestURI();
        String prefix = request.getContextPath() + "/api/v2/" + bucket + "/";
        return path.length() <= prefix.length() ? "" : path.substring(prefix.length());
    }
}
