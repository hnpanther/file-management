package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController()
@RequestMapping("/resource/file-sub-categories")
public class FileSubCategoryResource {


    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileSubCategoryService fileSubCategoryService;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public FileSubCategoryResource(GlobalGeneralLogging globalGeneralLogging, FileSubCategoryService fileSubCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileSubCategoryService = fileSubCategoryService;
    }


    //REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE
    @PreAuthorize("hasAuthority('REST_GET_ALL_MAIN_TAGS_OF_SUB_CATEGORY_FILE') || hasAuthority('ADMIN')")
    @GetMapping("{id}/main-tags")
    public GenericListResponse getAllMainTagsOfSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to get main tags of sub category with id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryResource.class", logMessage);

        List<MainTagFileDTO> mainTagsOfSubCategory = fileSubCategoryService.getMainTagsOfSubCategory(id);
        List<GenericListResponse.GenericResponse> list = mainTagsOfSubCategory.stream().map(mainTagFileDTO -> new GenericListResponse.GenericResponse(
                mainTagFileDTO.getId(), mainTagFileDTO.getTagName() + "-" + mainTagFileDTO.getTagNameDescription()
        )).toList();

        GenericListResponse genericListResponse = new GenericListResponse();
        genericListResponse.results = list;
        return genericListResponse;
    }


    //REST_DELETE_FILE_SUB_CATEGORY
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_SUB_CATEGORY') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteFileSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                        @PathVariable("id") int fileSubCategoryId,
                                                        HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to delete file sub category with id==" + fileSubCategoryId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryResource.class", logMessage);

        try {
            fileSubCategoryService.deleteSubCategory(fileSubCategoryId);
        } catch (DependencyResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileSubCategoryResource.class",
                    "DependencyResourceException" + e.getMessage());
            return new ResponseEntity<>("can not delete, first delete all related main tag," + fileSubCategoryId, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileSubCategoryResource.class",
                    "ResourceNotFoundException" + e.getMessage());
            return new ResponseEntity<>("file sub category not exists=" + fileSubCategoryId, HttpStatus.BAD_REQUEST);
        }


        return new ResponseEntity<>("file sub category deleted", HttpStatus.OK);
    }




}
