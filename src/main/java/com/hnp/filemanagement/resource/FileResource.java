package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController()
@RequestMapping("/resource/files")
public class FileResource {

    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileService fileService;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public FileResource(GlobalGeneralLogging globalGeneralLogging, FileService fileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileService = fileService;
    }


    //REST_DELETE_FILE_INFO
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_INFO') || hasAuthority('ADMIN')")
    @DeleteMapping("file-info/{fileInfoId}")
    public ResponseEntity<String> deleteFileInfo(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to delete file-info with id=" + fileInfoId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileResource.class", logMessage);


        fileService.deleteCompleteFileById(fileInfoId, principalId);

        return new ResponseEntity<>(("file info with id =" + fileInfoId + " deleted"), HttpStatus.OK);
    }


    //REST_UPDATE_FILE_INFO_DESCRIPTION
    @PreAuthorize("hasAuthority('REST_UPDATE_FILE_INFO_DESCRIPTION') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}")
    public ResponseEntity<String> updateFileInfo(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId, @RequestBody() String description, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to update file-info with id=" + fileInfoId + " and description=" + description;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileResource.class", logMessage);

        JsonParser springParser = JsonParserFactory.getJsonParser();
        Map<String, Object> map = springParser.parseMap(description);
        String updatedDesc = map.get("description").toString();
        fileService.updateFileInfoDescription(fileInfoId, updatedDesc, principalId);

        return new ResponseEntity<>("file info description updated", HttpStatus.OK);
    }

    //REST_CHANGE_FILE_INFO_STATE
    @PreAuthorize("hasAuthority('REST_CHANGE_FILE_INFO_STATE') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}/change-state")
    public ResponseEntity<String> changeFileInfoState(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId, @RequestBody() String body, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to change state file-info state with id=" + fileInfoId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileResource.class", logMessage);

        JsonParser springParser = JsonParserFactory.getJsonParser();
        Map<String, Object> map = springParser.parseMap(body);

        try {

            int newState = Integer.parseInt(map.get("newState").toString());

            if(newState != 0 && newState != -1) {
                return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
            }
            fileService.changeFileInfoState(fileInfoId, newState, principalId);
        } catch (NumberFormatException | InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileResource.class",
                    "NumberFormatException | InvalidDataException:" + e.getMessage());
            return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
        }


        return new ResponseEntity<>("file info state changed", HttpStatus.OK);
    }


    // REST_DELETE_FILE_DETAILS
    @PreAuthorize("hasAuthority('REST_DELETE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @DeleteMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}")
    public ResponseEntity<String> deleteFileDetails(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                    @PathVariable("fileInfoId") int fileInfoId,
                                                    @PathVariable("fileDetailsId") int fileDetailsId,
                                                    HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to delete file details with id==" + fileDetailsId + " and fileInfoId=" + fileInfoId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileResource.class", logMessage);

        try {
            fileService.deleteFileDetails(fileInfoId, fileDetailsId, principalId);
        } catch (BusinessException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileResource.class",
                    "BusinessException" + e.getMessage());
            return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileResource.class",
                    "ResourceNotFoundException" + e.getMessage());
            return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>("file details deleted", HttpStatus.OK);

    }


    // REST_CHANGE_STATE_FILE_DETAILS
    @PreAuthorize("hasAuthority('REST_CHANGE_STATE_FILE_DETAILS') || hasAuthority('ADMIN')")
    @PutMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/change-state/{newState}")
    public ResponseEntity<String> changeFileDetailsState(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                    @PathVariable("fileInfoId") int fileInfoId,
                                                    @PathVariable("fileDetailsId") int fileDetailsId,
                                                    @PathVariable("newState") int newState,
                                                    HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to change file details state with id==" + fileDetailsId + " and fileInfoId=" + fileInfoId + " and newState=" + newState;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileResource.class", logMessage);

        if(newState != 0 && newState != -1) {
            return new ResponseEntity<>("invalid state, state=" + newState, HttpStatus.BAD_REQUEST);
        }

        try {
            fileService.changeFileDetailsState(fileDetailsId, newState, principalId);
        } catch (InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileResource.class",
                    "InvalidDataException" + e.getMessage());
            return new ResponseEntity<>("invalid state, state=" + newState, HttpStatus.BAD_REQUEST);
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileResource.class",
                    "ResourceNotFoundException" + e.getMessage());
            return new ResponseEntity<>("fileDetails not exists=", HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>("file details state changed", HttpStatus.OK);

    }







}
