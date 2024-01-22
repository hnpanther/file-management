package com.hnp.filemanagement.resource;


import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FileCategoryService;
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
@RequestMapping("/resource/file-categories")
public class FileCategoryResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileCategoryService fileCategoryService;
    private final FileSubCategoryService fileSubCategoryService;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public FileCategoryResource(GlobalGeneralLogging globalGeneralLogging, FileCategoryService fileCategoryService, FileSubCategoryService fileSubCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileCategoryService = fileCategoryService;
        this.fileSubCategoryService = fileSubCategoryService;
    }

    //REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY
    @PreAuthorize("hasAuthority('REST_GET_ALL_SUB_CATEGORY_OF_CATEGORY') || hasAuthority('ADMIN')")
    @GetMapping("{id}/sub-categories")
    public GenericListResponse getAllSubCategoryOfCategory(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to get sub category of file category with id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryResource.class", logMessage);

        List<FileSubCategoryDTO> fileSubCategoryOfCategory = fileCategoryService.getFileSubCategoryOfCategory(id);

        List<GenericListResponse.GenericResponse> list = fileSubCategoryOfCategory.stream()
                .map(fsc -> new GenericListResponse.GenericResponse(fsc.getId(), fsc.getSubCategoryName() + " - " + fsc.getSubCategoryNameDescription())).toList();

        GenericListResponse genericListResponse = new GenericListResponse();
        genericListResponse.results = list;
        return genericListResponse;
    }


    //REST_DELETE_FILE_CATEGORY
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_CATEGORY') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteFileCategory(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                     @PathVariable("id") int fileCategoryId,
                                                     HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to delete file category with id==" + fileCategoryId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryResource.class", logMessage);

        try {
            fileCategoryService.deleteFileCategory(fileCategoryId);
        } catch (DependencyResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileCategoryResource.class",
                    "DependencyResourceException" + e.getMessage());
            return new ResponseEntity<>("can not delete, first delete all related sub category," + fileCategoryId, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileCategoryResource.class",
                    "ResourceNotFoundException" + e.getMessage());
            return new ResponseEntity<>("file category not exists=" + fileCategoryId, HttpStatus.BAD_REQUEST);
        }


        return new ResponseEntity<>("file sub category deleted", HttpStatus.OK);

    }
}
