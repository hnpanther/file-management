package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.service.FileCategoryService;
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
 * Categories as JSON, for the pages' own AJAX.
 *
 * <p>A category is the first real directory under the storage root, so it cannot be deleted while
 * sub-categories still hang off it.
 *
 * <p>Failures are not caught here: 404 for a missing category and 409 for one that still has
 * sub-categories come from the exceptions themselves, via {@code GlobalExceptionHandler}. The
 * success body used to say "file sub category deleted" when a *category* was deleted - the kind of
 * drift a uniform {@link ApiResult} removes.
 */
@RestController
@RequestMapping("/resource/file-categories")
public class FileCategoryResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileCategoryService fileCategoryService;

    public FileCategoryResource(GlobalGeneralLogging globalGeneralLogging,
                                FileCategoryService fileCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileCategoryService = fileCategoryService;
    }

    /** Feeds the dependent sub-category dropdown on the file, sub-category and main-tag forms. */
    //REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY
    @PreAuthorize("hasAuthority('REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY') || hasAuthority('ADMIN')")
    @GetMapping("{id}/sub-categories")
    public GenericListResponse getAllSubCategoriesOfCategory(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                             @PathVariable("id") int categoryId,
                                                             HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileCategoryResource.class,
                "list sub categories of category id=" + categoryId);

        List<FileSubCategoryDTO> subCategories = fileCategoryService.getFileSubCategoryOfCategory(categoryId);

        List<GenericListResponse.GenericResponse> results = subCategories.stream()
                .map(sub -> new GenericListResponse.GenericResponse(
                        sub.getId(), sub.getSubCategoryName() + " - " + sub.getSubCategoryNameDescription()))
                .toList();

        GenericListResponse response = new GenericListResponse();
        response.results = results;
        return response;
    }

    //REST_DELETE_FILE_CATEGORY
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_CATEGORY') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ApiResult deleteFileCategory(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @PathVariable("id") int fileCategoryId,
                                        HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, FileCategoryResource.class,
                "delete file category id=" + fileCategoryId);

        fileCategoryService.deleteFileCategory(fileCategoryId, userDetails.getId());

        return ApiResult.deleted("fileCategory", fileCategoryId);
    }
}
