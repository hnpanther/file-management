package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.MainTagFileService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController()
@RequestMapping("/resource/main-tags")
public class MainTagFileResource {


    private final GlobalGeneralLogging globalGeneralLogging;
    private final MainTagFileService mainTagFileService;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public MainTagFileResource(GlobalGeneralLogging globalGeneralLogging, MainTagFileService mainTagFileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.mainTagFileService = mainTagFileService;
    }


    // REST_DELETE_MAIN_TAG_FILE
    @PreAuthorize("hasAuthority('REST_DELETE_MAIN_TAG_FILE') || hasAuthority('ADMIN')")
    @DeleteMapping("{id}")
    public ResponseEntity<String> deleteMainTagFile(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                    @PathVariable("id") int mainTagFileId,
                                                    HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to delete main tag file with id==" + mainTagFileId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "MainTagFileResource.class", logMessage);


        try {
            mainTagFileService.deleteMainTagFile(mainTagFileId);
        } catch (DependencyResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "MainTagFileResource.class",
                    "DependencyResourceException" + e.getMessage());
            return new ResponseEntity<>("can not delete, first delete all related files" + mainTagFileId, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "MainTagFileResource.class",
                    "ResourceNotFoundException" + e.getMessage());
            return new ResponseEntity<>("main tag file not exists=" + mainTagFileId, HttpStatus.BAD_REQUEST);
        }



        return new ResponseEntity<>("main tag file deleted", HttpStatus.OK);
    }



}
