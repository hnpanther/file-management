package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.service.GeneralTagService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * General tags as JSON, for the pages' own AJAX.
 *
 * <p>A general tag is only a label: unlike a category or a sub-category it creates no directory,
 * so deleting one is blocked purely by the categories that point at it.
 *
 * <p>Failures are not caught here. The domain exceptions carry their own status - a missing tag is
 * 404, a tag still in use is 409 - and {@code GlobalExceptionHandler} turns them into RFC 9457
 * problem documents. Catching them locally, as this class used to, flattened every failure to 400
 * with a hand-written English sentence.
 */
@RestController
@RequestMapping("/resource/general-tags")
public class GeneralTagResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final GeneralTagService generalTagService;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public GeneralTagResource(GlobalGeneralLogging globalGeneralLogging, GeneralTagService generalTagService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.generalTagService = generalTagService;
    }

    /** Feeds the tag picker on the category form; shaped for Select2's {@code results} contract. */
    //REST_GET_ALL_GENERAL_TAG
    @PreAuthorize("hasAuthority('REST_GET_ALL_GENERAL_TAG') || hasAuthority('ADMIN')")
    @GetMapping
    public GenericListResponse getAllGeneralTags(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                 HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, GeneralTagResource.class,
                "list all general tags");

        GeneralTagPageDTO page = generalTagService.getGeneralTagPage(defaultElementSize, 0, null);

        List<GenericListResponse.GenericResponse> results = page.getGeneralTagDTOList().stream()
                .map(tag -> new GenericListResponse.GenericResponse(
                        tag.getId(), tag.getTagName() + " - " + tag.getTagNameDescription()))
                .toList();

        GenericListResponse response = new GenericListResponse();
        response.results = results;
        return response;
    }

    //REST_DELETE_GENERAL_TAG
    @PreAuthorize("hasAuthority('REST_DELETE_GENERAL_TAG') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ApiResult deleteGeneralTag(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                      @PathVariable("id") int generalTagId,
                                      HttpServletRequest request) {

        globalGeneralLogging.controllerLogging(userDetails, request, GeneralTagResource.class,
                "delete general tag id=" + generalTagId);

        generalTagService.deleteGeneralTag(generalTagId, userDetails.getId());

        return ApiResult.deleted("generalTag", generalTagId);
    }
}
