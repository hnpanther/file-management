package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadChannel;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.ApiResult;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileUploadOutputDTO;
import com.hnp.filemanagement.shared.web.ExternalId;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.file.domain.FileMapper;
import com.hnp.filemanagement.shared.validation.InsertValidation;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.file.domain.MetadataSearchService;
import com.hnp.filemanagement.file.domain.FileMetadataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.IOException;
import java.util.OptionalLong;

/**
 * The programmatic API, for callers that are not this application's own pages.
 *
 * <p>It is deliberately small: upload a file, delete a version, download a version. The pages use
 * {@code /resource/**} instead, and the two families now answer the same way - success is an
 * {@link ApiResult} or a payload DTO, failure is an RFC 9457 problem document from
 * {@code GlobalExceptionHandler}.
 *
 * <p>This class used to carry its own {@code @RestControllerAdvice} and its own error strings:
 * validation failures came back as {@code "can not save file: "} followed by a Persian sentence,
 * and a duplicate file was 400 rather than 409. Both are gone. Messages from this layer are
 * English, because its callers are programs; the Persian wording belongs to the pages.
 *
 * <p>The upload still answers 200 rather than 201. It is a published endpoint and the status is
 * part of its contract, so changing it is a Phase 2 decision, not a cleanup.
 *
 * <p><b>External ids only, since 2.4.0.</b> Every path segment that names a file or a revision
 * takes its external id - a UUID, in any case (issue 7, {@link ExternalId}) - where it took the
 * number until 2.3.0 (and either, from 1.8.0, while the PL/SQL clients moved over). The routes,
 * their names, the methods, the answers and the headers are what they were; a number in a
 * segment is now the same 400 {@code InvalidParameter} any malformed id is, and says the parameter
 * takes the external id. A client that keeps only the file's id downloads with
 * {@code file-info/{fileInfoId}/download}: the latest version, or the one {@code ?version=} names,
 * in its only format or the one {@code ?format=} picks. Every download says which revision it
 * served in {@code X-File-*} headers. The upload answers both ids of the file and of the
 * revision, as before; only the external ones can be sent back. The guide is
 * {@code docs/api-v1.md}.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileApi {

    private static final Logger logger = LoggerFactory.getLogger(FileApi.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileService fileService;
    private final DownloadAudit downloadAudit;
    private final FileMetadataService fileMetadataService;
    private final MetadataSearchService metadataSearchService;

    public FileApi(GlobalGeneralLogging globalGeneralLogging, FileService fileService, DownloadAudit downloadAudit,
                   FileMetadataService fileMetadataService, MetadataSearchService metadataSearchService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileService = fileService;
        this.downloadAudit = downloadAudit;
        this.fileMetadataService = fileMetadataService;
        this.metadataSearchService = metadataSearchService;
    }

    /** A liveness probe that also proves the caller's token and permission still work. */
    // API_HEALTH_TEST
    @PreAuthorize("hasAuthority('API_HEALTH_TEST') || hasAuthority('ADMIN')")
    @GetMapping("/health-test")
    public String healthTest() {
        logger.info("request for health test");
        return "hello from endpoint";
    }

    /**
     * Uploads a file, or a new version of one that already exists.
     *
     * <p>Where it goes is a {@code folderId}: the id of a tag folder, the id the explorer and the
     * tree render (since Phase 7 step 4; the category / sub-category / tag triple that preceded
     * it is ignored). A request without one is a 400 that names the parameter.
     *
     * @param publicFile {@code "1"} or {@code "true"} lists the file on the public files page;
     *                   anything else, absent included, keeps it private. Since 1.7.0 - it used to
     *                   be public unless {@code "0"}, so a caller that wants a public file has to
     *                   say so ({@link FileService#visibilityOf}). Private is not "not
     *                   downloadable": this API and every signed-in download ignore it.
     */
    // API_SAVE_NEW_FILE
    @PreAuthorize("hasAuthority('API_SAVE_NEW_FILE') || hasAuthority('ADMIN')")
    @PostMapping
    public FileUploadOutputDTO saveNewFile(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                           @RequestParam(value = "public-file", required = false) String publicFile,
                                           @ModelAttribute @Validated(InsertValidation.class) FileInfoDTO fileInfoDTO,
                                           BindingResult bindingResult) {

        globalGeneralLogging.detail("save new file name=" + fileInfoDTO.getFileName());

        // Checked before touching the multipart: the debug block below dereferences it, and this
        // method used to log it first, so a request without a file answered 500 instead of 400.
        if (bindingResult.hasErrors()) {
            // The field and the reason, one per line - not the binding result's toString, which
            // named the DTO class and the object's hash and said nothing a caller could act on.
            throw new InvalidDataException("invalid file data: " + bindingResult.getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(java.util.stream.Collectors.joining("; ")));
        }

        logger.debug("upload originalName={}, contentType={}, size={}, publicFile={}",
                fileInfoDTO.getMultipartFile().getOriginalFilename(),
                fileInfoDTO.getMultipartFile().getContentType(),
                fileInfoDTO.getMultipartFile().getSize(),
                publicFile);

        FileDetailsDTO fileDetailsDTO = fileService.createNewFile(fileInfoDTO, userDetails.getId(),
                FileService.visibilityOf(publicFile));

        return FileMapper.toUploadOutput(fileDetailsDTO);
    }

    /**
     * Deletes one version. Removing the last version removes the file itself. Both ids are external
     * ids, and must name a revision of that file.
     */
    // API_DELETE_FILE_DETAILS
    @PreAuthorize("hasAuthority('API_DELETE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    public ApiResult deleteFileDetails(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("fileInfoId") ExternalId fileInfoReference,
                                       @PathVariable("fileDetailsId") ExternalId fileDetailsReference) {

        int fileInfoId = fileService.fileInfoIdOf(fileInfoReference);
        int fileDetailsId = fileService.fileDetailsIdOf(fileDetailsReference);
        globalGeneralLogging.detail("delete file details id=" + fileDetailsId + " of file info id=" + fileInfoId);

        fileService.deleteFileDetails(fileInfoId, fileDetailsId, userDetails.getId());

        return ApiResult.deleted("fileDetails", fileDetailsId);
    }

    /**
     * The same delete, by the version's external id alone - the form an integration keeps: the
     * {@code fileDetailsExternalId} it got back from the upload is all it needs, and nothing in the path names the taxonomy that
     * Phase 7 step 4 removes. Same permission as the two-id form; it is the same operation.
     */
    // API_DELETE_FILE_DETAILS (the id-only form of the delete above)
    @PreAuthorize("hasAuthority('API_DELETE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @DeleteMapping("file-details/{fileDetailsId}")
    public ApiResult deleteFileDetailsById(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                           @PathVariable("fileDetailsId") ExternalId fileDetailsReference) {

        int fileDetailsId = fileService.fileDetailsIdOf(fileDetailsReference);
        globalGeneralLogging.detail("delete file details id=" + fileDetailsId);

        fileService.deleteFileDetails(fileDetailsId, userDetails.getId());

        return ApiResult.deleted("fileDetails", fileDetailsId);
    }

    /**
     * Streams the stored bytes. {@code fileInfoId} is not used to look the version up - the id of a
     * {@code fileDetails} is already unique - but it keeps the URL parallel to the delete endpoint.
     * It is still read as an id, so that a malformed one is a 400 as it always was.
     */
    // API_DOWNLOAD_FILE
    @PreAuthorize("hasAuthority('API_DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    public ResponseEntity<Resource> downloadFile(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                 @PathVariable("fileInfoId") ExternalId fileInfoReference,
                                                 @PathVariable("fileDetailsId") ExternalId fileDetailsReference,
                                                 HttpMethod method) {
        return downloadFileById(userDetails, fileDetailsReference, method);
    }

    /** The same download, by the version's id alone. */
    // API_DOWNLOAD_FILE (the id-only form of the download above)
    @PreAuthorize("hasAuthority('API_DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-details/{fileDetailsId}/download")
    public ResponseEntity<Resource> downloadFileById(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                     @PathVariable("fileDetailsId") ExternalId fileDetailsReference,
                                                     HttpMethod method) {

        int fileDetailsId = fileService.fileDetailsIdOf(fileDetailsReference);
        globalGeneralLogging.detail("download file details id=" + fileDetailsId);

        FileDownloadDTO download = fileService.downloadFile(fileDetailsId, userDetails.getId());
        downloadAudit.served(download, DownloadChannel.API_V1);
        return serve(download, method);
    }

    /**
     * A file's bytes by the file's own external id, for a client that
     * keeps that and not a revision's: the latest version unless {@code version} names another,
     * in the version's only format unless {@code format} picks one ({@code pdf}, {@code .PDF}).
     * 404 for a version or format the file does not have (the message lists the formats it does
     * have); 400 when the version has several formats and none is chosen. The headers say which
     * revision was served.
     */
    // API_DOWNLOAD_FILE (a revision chosen by the file's own id)
    @PreAuthorize("hasAuthority('API_DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/download")
    public ResponseEntity<Resource> downloadFileRevision(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                         @PathVariable("fileInfoId") ExternalId fileInfoReference,
                                                         @RequestParam(value = "version", required = false) Integer version,
                                                         @RequestParam(value = "format", required = false) String format,
                                                         HttpMethod method) {

        int fileInfoId = fileService.fileInfoIdOf(fileInfoReference);
        globalGeneralLogging.detail("download file info id=" + fileInfoId + " version=" + (version == null ? "latest" : version)
                + (format == null ? "" : " format=" + format));

        FileDownloadDTO download = fileService.downloadFileRevision(fileInfoId, version, format, userDetails.getId());
        downloadAudit.served(download, DownloadChannel.API_V1);
        return serve(download, method);
    }

    // ------------------------------------------------------------------ metadata (roadmap 12.2 - 2.13.0)

    /**
     * A file's current metadata - its newest revision's - and its entity tag, in the body and the
     * {@code ETag} header. {@code metadata} is {@code null} for none. {@code READ} on the file's folder.
     */
    // API_GET_METADATA
    @PreAuthorize("hasAuthority('API_GET_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/metadata")
    public ResponseEntity<MetadataAnswers.FileMetadata> fileMetadata(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                     @PathVariable("fileInfoId") ExternalId fileInfoReference) {
        int fileInfoId = fileService.fileInfoIdOf(fileInfoReference);
        globalGeneralLogging.detail("get metadata of file info id=" + fileInfoId);
        var answer = MetadataAnswers.FileMetadata.of(fileMetadataService.ofFile(fileInfoId, userDetails.getId()));
        return MetadataAnswers.tagged(answer, answer.etag());
    }

    /**
     * Sets, replaces or clears a file's metadata: every format of its newest version takes the
     * document in the body - a JSON object; {@code {}} clears it. {@code If-None-Match: *} sets it only
     * where there is none (a {@code 412} otherwise, nothing changed) - what an integration filling in
     * what is missing sends; {@code If-Match: "<etag>"} only if it is still the one read. Recorded in
     * the file's history with both documents. {@code WRITE} on the file's folder.
     */
    // API_SET_METADATA
    @PreAuthorize("hasAuthority('API_SET_METADATA') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}/metadata")
    public ResponseEntity<MetadataAnswers.FileMetadata> setFileMetadata(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                        @PathVariable("fileInfoId") ExternalId fileInfoReference,
                                                                        @RequestBody(required = false) String body,
                                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        int fileInfoId = fileService.fileInfoIdOf(fileInfoReference);
        globalGeneralLogging.detail("set metadata of file info id=" + fileInfoId);
        var written = fileMetadataService.replaceOnFile(fileInfoId, requireBody(body),
                new MetadataPrecondition(ifMatch, ifNoneMatch), userDetails.getId());
        var answer = MetadataAnswers.FileMetadata.of(written);
        return MetadataAnswers.tagged(answer, answer.etag());
    }

    /** One revision's metadata. {@code READ} on the file's folder. */
    // API_GET_METADATA (one revision's)
    @PreAuthorize("hasAuthority('API_GET_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("file-details/{fileDetailsId}/metadata")
    public ResponseEntity<MetadataAnswers.FileMetadata> revisionMetadata(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                         @PathVariable("fileDetailsId") ExternalId fileDetailsReference) {
        int fileDetailsId = fileService.fileDetailsIdOf(fileDetailsReference);
        globalGeneralLogging.detail("get metadata of file details id=" + fileDetailsId);
        var answer = MetadataAnswers.FileMetadata.of(fileMetadataService.ofRevision(fileDetailsId, userDetails.getId()));
        return MetadataAnswers.tagged(answer, answer.etag());
    }

    /** Sets, replaces or clears one revision's metadata - as the file's, for that revision alone. */
    // API_SET_METADATA (one revision's)
    @PreAuthorize("hasAuthority('API_SET_METADATA') || hasAuthority('ADMIN')")
    @PutMapping("file-details/{fileDetailsId}/metadata")
    public ResponseEntity<MetadataAnswers.FileMetadata> setRevisionMetadata(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                                            @PathVariable("fileDetailsId") ExternalId fileDetailsReference,
                                                                            @RequestBody(required = false) String body,
                                                                            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        int fileDetailsId = fileService.fileDetailsIdOf(fileDetailsReference);
        globalGeneralLogging.detail("set metadata of file details id=" + fileDetailsId);
        var written = fileMetadataService.replaceOnRevision(fileDetailsId, requireBody(body),
                new MetadataPrecondition(ifMatch, ifNoneMatch), userDetails.getId());
        var answer = MetadataAnswers.FileMetadata.of(written);
        return MetadataAnswers.tagged(answer, answer.etag());
    }

    /**
     * Files by metadata: {@code metadata} - a JSON object the file's current document must hold
     * ({@code @>}: every key it names with that value, nested parts and array members included) -
     * and/or {@code folderMetadata}, one a folder above the file must hold, at any depth. Only files
     * the caller may read; newest first, {@code size} at most 200, a page with {@code hasNext} and no
     * count.
     */
    // API_SEARCH_METADATA
    @PreAuthorize("hasAuthority('API_SEARCH_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("search")
    public MetadataAnswers.Page<MetadataAnswers.FileHit> search(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                               @RequestParam(value = "metadata", required = false) String metadata,
                                                               @RequestParam(value = "folderMetadata", required = false) String folderMetadata,
                                                               @RequestParam(value = "page", required = false) Integer page,
                                                               @RequestParam(value = "size", required = false) Integer size) {
        globalGeneralLogging.detail("search files by metadata" + (metadata == null ? "" : " (file's)")
                + (folderMetadata == null ? "" : " (folder's)") + " page=" + page);
        return MetadataAnswers.Page.of(metadataSearchService.files(metadata, folderMetadata, page, size, userDetails.getId()),
                MetadataAnswers.FileHit::of);
    }

    /** A body is the document: {@code {}} clears it; nothing at all is a mistake, not a clearing. */
    static String requireBody(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidDataException("the body is the metadata, a JSON object - {} to clear it", "metadata.invalid.notObject");
        }
        return body;
    }

    /**
     * Every v1 download answers the same way: the type the extension says, as an attachment under
     * the stored name ({@link ContentDispositions}), {@code nosniff}, and which revision it was -
     * {@code X-File-External-Id}, {@code X-File-Details-Id}, {@code X-File-Details-External-Id},
     * {@code X-File-Version}, and {@code X-Checksum-SHA256} when the checksum is known, so a client
     * can check what it received. A {@code HEAD} to any download answers the headers alone.
     *
     * <p>The {@code HEAD} is answered here, without a body, rather than left to Spring: Spring
     * would run the {@code GET} and discard the body, reading the whole file from disk to throw it
     * away - as {@code ObjectStoreApi}'s explicit {@code HEAD} says too.
     */
    private static ResponseEntity<Resource> serve(FileDownloadDTO download, HttpMethod method) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(download.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .header("X-File-External-Id", download.getFileExternalId())
                .header("X-File-Details-Id", String.valueOf(download.getFileDetailsId()))
                .header("X-File-Details-External-Id", download.getFileDetailsExternalId())
                .header("X-File-Version", String.valueOf(download.getVersion()));
        if (download.getChecksumSha256() != null) {
            response.header("X-Checksum-SHA256", download.getChecksumSha256());
        }
        if (method == HttpMethod.HEAD) {
            contentLengthOf(download.getResource()).ifPresent(response::contentLength);
            return response.build();
        }
        return response.body(download.getResource());
    }

    /** The stored size, read from the file system and not from the bytes; none if it cannot be read. */
    private static OptionalLong contentLengthOf(Resource resource) {
        try {
            return OptionalLong.of(resource.contentLength());
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }
}
