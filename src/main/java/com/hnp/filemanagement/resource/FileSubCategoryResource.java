package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController()
@RequestMapping("/resource/file-sub-categories")
public class FileSubCategoryResource {


    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileSubCategoryService fileSubCategoryService;

    public FileSubCategoryResource(GlobalGeneralLogging globalGeneralLogging, FileSubCategoryService fileSubCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileSubCategoryService = fileSubCategoryService;
    }


    @GetMapping("{id}/main-tags")
    public GenericListResponse getAllMainTagsOfSubCategory(@PathVariable("id") int id, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "rest request to get main tags of sub category with id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryResource.class", logMessage);

        List<MainTagFileDTO> mainTagsOfSubCategory = fileSubCategoryService.getMainTagsOfSubCategory(id);
        List<GenericListResponse.GenericResponse> list = mainTagsOfSubCategory.stream().map(mainTagFileDTO -> new GenericListResponse.GenericResponse(
                mainTagFileDTO.getId(), mainTagFileDTO.getTagName() + "-" + mainTagFileDTO.getDescription()
        )).toList();

        GenericListResponse genericListResponse = new GenericListResponse();
        genericListResponse.results = list;
        return genericListResponse;
    }





}
