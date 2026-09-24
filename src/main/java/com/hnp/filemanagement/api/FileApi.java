package com.hnp.filemanagement.api;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadOutputDTO;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.util.ContentDispositions;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.validation.InsertValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
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
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileApi {

    private static final Logger logger = LoggerFactory.getLogger(FileApi.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileService fileService;

    public FileApi(GlobalGeneralLogging globalGeneralLogging, FileService fileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileService = fileService;
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

        return ModelConverterUtil.convertFileDetailsDTOToFileUploadOutputDTO(fileDetailsDTO);
    }

    /** Deletes one version. Removing the last version removes the file itself. */
    // API_DELETE_FILE_DETAILS
    @PreAuthorize("hasAuthority('API_DELETE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    public ApiResult deleteFileDetails(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("fileInfoId") int fileInfoId,
                                       @PathVariable("fileDetailsId") int fileDetailsId) {

        globalGeneralLogging.detail("delete file details id=" + fileDetailsId + " of file info id=" + fileInfoId);

        fileService.deleteFileDetails(fileInfoId, fileDetailsId, userDetails.getId());

        return ApiResult.deleted("fileDetails", fileDetailsId);
    }

    /**
     * The same delete, by the version's id alone - the form an integration keeps: the id it got
     * back from the upload is all it needs, and nothing in the path names the taxonomy that
     * Phase 7 step 4 removes. Same permission as the two-id form; it is the same operation.
     */
    // API_DELETE_FILE_DETAILS (the id-only form of the delete above)
    @PreAuthorize("hasAuthority('API_DELETE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @DeleteMapping("file-details/{fileDetailsId}")
    public ApiResult deleteFileDetailsById(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                           @PathVariable("fileDetailsId") int fileDetailsId) {

        globalGeneralLogging.detail("delete file details id=" + fileDetailsId);

        fileService.deleteFileDetails(fileDetailsId, userDetails.getId());

        return ApiResult.deleted("fileDetails", fileDetailsId);
    }

    /**
     * Streams the stored bytes. {@code fileInfoId} is not used to look the version up - the id of a
     * {@code fileDetails} is already unique - but it keeps the URL parallel to the delete endpoint.
     */
    // API_DOWNLOAD_FILE
    @PreAuthorize("hasAuthority('API_DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    public ResponseEntity<Resource> downloadFile(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                 @PathVariable("fileInfoId") int fileInfoId,
                                                 @PathVariable("fileDetailsId") int fileDetailsId) {
        return downloadFileById(userDetails, fileDetailsId);
    }

    /** The same download, by the version's id alone. */
    // API_DOWNLOAD_FILE (the id-only form of the download above)
    @PreAuthorize("hasAuthority('API_DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-details/{fileDetailsId}/download")
    public ResponseEntity<Resource> downloadFileById(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                     @PathVariable("fileDetailsId") int fileDetailsId) {

        globalGeneralLogging.detail("download file details id=" + fileDetailsId);

        FileDownloadDTO fileDownloadDTO = fileService.downloadFile(fileDetailsId, userDetails.getId());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fileDownloadDTO.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(fileDownloadDTO.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .body(fileDownloadDTO.getResource());
    }
}
