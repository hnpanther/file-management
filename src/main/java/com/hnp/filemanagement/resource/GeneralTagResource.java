package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.dto.GenericListResponse;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.GeneralTagService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController()
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


    //REST_GET_ALL_GENERAL_TAG
    @PreAuthorize("hasAuthority('REST_GET_ALL_GENERAL_TAG') || hasAuthority('ADMIN')")
    @GetMapping
    public GenericListResponse getAllGeneralTAg(@AuthenticationPrincipal UserDetailsImpl userDetails, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to get all general tags";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagResource.class", logMessage);


        GeneralTagPageDTO generalTagPage = generalTagService.getGeneralTagPage(defaultElementSize, 0, null);

        List<GenericListResponse.GenericResponse> list = generalTagPage.getGeneralTagDTOList().stream().map(generalTagDTO ->
                new GenericListResponse.GenericResponse(generalTagDTO.getId(), generalTagDTO.getTagName() + " - " + generalTagDTO.getTagNameDescription())).toList();

        GenericListResponse genericListResponse = new GenericListResponse();
        genericListResponse.results = list;
        return genericListResponse;

    }


    //REST_DELETE_GENERAL_TAG
    @PreAuthorize("hasAuthority('REST_DELETE_GENERAL_TAG') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteGeneralTag(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                   @PathVariable("id") int generalTagId,
                                                   HttpServletRequest request) {


        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to delete general tag with id==" + generalTagId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagResource.class", logMessage);

        try {
            generalTagService.deleteGeneralTag(generalTagId);
        } catch (DependencyResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "GeneralTagResource.class",
                    "DependencyResourceException" + e.getMessage());
            return new ResponseEntity<>("can not delete, first delete all related category" + generalTagId, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "GeneralTagResource.class",
                    "ResourceNotFoundException" + e.getMessage());
            return new ResponseEntity<>("general tag file not exists=" + generalTagId, HttpStatus.BAD_REQUEST);
        }



        return new ResponseEntity<>("general tag deleted", HttpStatus.OK);

    }
}
