package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sub-categories as JSON, for the pages' own AJAX.
 *
 * <p>A sub-category is the second real directory level under the storage root, which is why it
 * cannot be deleted while main tags still hang off it - the directory would be orphaned.
 *
 * <p>Failures are not caught here: 404 for a missing sub-category and 409 for one that still has
 * main tags come from the exceptions themselves, via {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/resource/file-sub-categories")
public class FileSubCategoryResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileSubCategoryService fileSubCategoryService;

    public FileSubCategoryResource(GlobalGeneralLogging globalGeneralLogging,
                                   FileSubCategoryService fileSubCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileSubCategoryService = fileSubCategoryService;
    }

    /** Feeds the dependent tag dropdown on the file and main-tag forms. */
    //REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE
    @PreAuthorize("hasAuthority('REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE') || hasAuthority('ADMIN')")
    @GetMapping("{id}/main-tags")
    public GenericListResponse getAllMainTagsOfSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                           @PathVariable("id") int subCategoryId,
                                                           HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileSubCategoryResource.class,
                "list main tags of sub category id=" + subCategoryId);

        List<MainTagFileDTO> mainTags = fileSubCategoryService.getMainTagsOfSubCategory(subCategoryId);

        List<GenericListResponse.GenericResponse> results = mainTags.stream()
                .map(tag -> new GenericListResponse.GenericResponse(
                        tag.getId(), tag.getTagName() + "-" + tag.getTagNameDescription()))
                .toList();

        GenericListResponse response = new GenericListResponse();
        response.results = results;
        return response;
    }

    //REST_DELETE_FILE_SUB_CATEGORY
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_SUB_CATEGORY') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ApiResult deleteFileSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                           @PathVariable("id") int fileSubCategoryId,
                                           HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileSubCategoryResource.class,
                "delete sub category id=" + fileSubCategoryId);

        fileSubCategoryService.deleteSubCategory(fileSubCategoryId, userDetails.getId());

        return ApiResult.deleted("fileSubCategory", fileSubCategoryId);
    }
}
