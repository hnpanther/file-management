package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.DescriptionUpdateRequest;
import com.hnp.filemanagement.dto.StateChangeRequest;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Files as JSON, for the file-info page's own AJAX.
 *
 * <p>Two levels live here. A {@code fileInfo} is the logical file - one name, one tag, one
 * description - and a {@code fileDetails} is one uploaded version of it. Deleting the last version
 * therefore deletes the whole {@code fileInfo}, which is why the page passes the version count in
 * and redirects differently depending on it.
 *
 * <p>Bodies are bound, not parsed. Every mutating endpoint here used to take the raw body as a
 * {@code String} and dig the field out with {@code JsonParserFactory}; a payload without the
 * expected key threw {@code NullPointerException} and answered 500. See {@link StateChangeRequest}
 * and {@link DescriptionUpdateRequest}.
 *
 * <p>Failures are not caught here: a missing file is 404, a rejected state is 400, and a version
 * that cannot be removed is 417 - all from the exceptions themselves, via
 * {@code GlobalExceptionHandler}. The methods used to catch those and answer 400 with the string
 * "invalid data" for every one of them.
 *
 * <p>The page only ever branches on {@code xhr.status === 200}, so success must stay 200; failure
 * statuses are free to be accurate.
 */
@RestController
@RequestMapping("/resource/files")
public class FileResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileService fileService;

    public FileResource(GlobalGeneralLogging globalGeneralLogging, FileService fileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileService = fileService;
    }

    /** Removes the file, every version of it, and the bytes on disk. */
    //REST_DELETE_FILE_INFO
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_INFO') || hasAuthority('ADMIN')")
    @DeleteMapping("file-info/{fileInfoId}")
    public ApiResult deleteFileInfo(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("fileInfoId") int fileInfoId,
                                    HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileResource.class,
                "delete file info id=" + fileInfoId);

        fileService.deleteCompleteFileById(fileInfoId, userDetails.getId());

        return ApiResult.deleted("fileInfo", fileInfoId);
    }

    //REST_UPDATE_FILE_INFO_DESCRIPTION
    @PreAuthorize("hasAuthority('REST_UPDATE_FILE_INFO_DESCRIPTION') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}")
    public ApiResult updateFileInfoDescription(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                               @PathVariable("fileInfoId") int fileInfoId,
                                               @RequestBody DescriptionUpdateRequest body,
                                               HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileResource.class,
                "update description of file info id=" + fileInfoId);

        if (body == null || body.description() == null) {
            throw new InvalidDataException("description is required");
        }
        fileService.updateFileInfoDescription(fileInfoId, body.description(), userDetails.getId());

        return ApiResult.updated("fileInfo", fileInfoId);
    }

    /** Activates or disables the file as a whole; the service accepts only 0 and -1. */
    //REST_CHANGE_FILE_INFO_STATE
    @PreAuthorize("hasAuthority('REST_CHANGE_FILE_INFO_STATE') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}/change-state")
    public ApiResult changeFileInfoState(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                         @PathVariable("fileInfoId") int fileInfoId,
                                         @RequestBody StateChangeRequest body,
                                         HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileResource.class,
                "change state of file info id=" + fileInfoId);

        if (body == null || body.newState() == null) {
            throw new InvalidDataException("newState is required");
        }
        fileService.changeFileInfoState(fileInfoId, body.newState(), userDetails.getId());

        return ApiResult.stateChanged("fileInfo", fileInfoId);
    }

    /**
     * Removes one version. If it was the only one the whole file goes with it, so the page reloads
     * the list rather than the file page when the count was 1.
     */
    // REST_DELETE_FILE_DETAILS
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    public ApiResult deleteFileDetails(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("fileInfoId") int fileInfoId,
                                       @PathVariable("fileDetailsId") int fileDetailsId,
                                       HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileResource.class,
                "delete file details id=" + fileDetailsId + " of file info id=" + fileInfoId);

        fileService.deleteFileDetails(fileInfoId, fileDetailsId, userDetails.getId());

        return ApiResult.deleted("fileDetails", fileDetailsId);
    }

    /**
     * The new state is in the path here rather than the body. The page was written that way; the
     * inconsistency is noted rather than fixed, because the URL is what the page already calls.
     */
    // REST_CHANGE_STATE_FILE_DETAILS
    @PreAuthorize("hasAuthority('REST_CHANGE_STATE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/change-state/{newState}")
    public ApiResult changeFileDetailsState(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                            @PathVariable("fileInfoId") int fileInfoId,
                                            @PathVariable("fileDetailsId") int fileDetailsId,
                                            @PathVariable("newState") int newState,
                                            HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileResource.class,
                "change state of file details id=" + fileDetailsId + " to " + newState);

        fileService.changeFileDetailsState(fileDetailsId, newState, userDetails.getId());

        return ApiResult.stateChanged("fileDetails", fileDetailsId);
    }
}
