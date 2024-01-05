package com.hnp.filemanagement.resource;


import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("{id}/sub-categories")
    public GenericListResponse getAllSubCategoryOfCategory(@PathVariable("id") int id, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
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
}
