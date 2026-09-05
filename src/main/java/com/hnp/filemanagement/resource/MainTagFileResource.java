package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.service.MainTagFileService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Main tags as JSON, for the pages' own AJAX.
 *
 * <p>A main tag creates no directory today - it is metadata scoped to a sub-category - so deleting
 * one is blocked only by the files filed under it. That check moved from hand-written SQL into a
 * derived repository query; see {@code MainTagFileService.deleteMainTagFile}.
 *
 * <p>Failures are not caught here: 404 for a missing tag and 409 for one that still has files come
 * from the exceptions themselves, via {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/resource/main-tags")
public class MainTagFileResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final MainTagFileService mainTagFileService;

    public MainTagFileResource(GlobalGeneralLogging globalGeneralLogging, MainTagFileService mainTagFileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.mainTagFileService = mainTagFileService;
    }

    // REST_DELETE_MAIN_TAG_FILE
    @PreAuthorize("hasAuthority('REST_DELETE_MAIN_TAG_FILE') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ApiResult deleteMainTagFile(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                       @PathVariable("id") int mainTagFileId,
                                       HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, MainTagFileResource.class,
                "delete main tag id=" + mainTagFileId);

        mainTagFileService.deleteMainTagFile(mainTagFileId, userDetails.getId());

        return ApiResult.deleted("mainTag", mainTagFileId);
    }
}
