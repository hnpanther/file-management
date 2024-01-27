package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.UserService;
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
@RequestMapping("/resource/users")
public class UserResource {

    private final GlobalGeneralLogging globalGeneralLogging;

    private final UserService userService;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;


    public UserResource(GlobalGeneralLogging globalGeneralLogging, UserService userService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userService = userService;
    }

    //REST_CHANGE_USER_ENABLED
    @PreAuthorize("hasAuthority('REST_CHANGE_USER_ENABLED') || hasAuthority('ADMIN')")
    @PutMapping("{userId}/change-enabled")
    public ResponseEntity<String> changeUserEnabled(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, @RequestBody() String body, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to change enabled user with id=" + userId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserResource.class", logMessage);

        JsonParser springParser = JsonParserFactory.getJsonParser();
        Map<String, Object> map = springParser.parseMap(body);

        try {

            int enabled = Integer.parseInt(map.get("enabled").toString());

            if(enabled != 0 && enabled != 1) {
                return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
            }
            userService.changeEnabled(userId, enabled, principalId);
        } catch (NumberFormatException | InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserResource.class",
                    "NumberFormatException | InvalidDataException:" + e.getMessage());
            return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
        }

        return new ResponseEntity<>("user enabled changed", HttpStatus.OK);
    }

    //REST_CHANGE_USER_LOGIN_TYPE
    @PreAuthorize("hasAuthority('REST_CHANGE_USER_LOGIN_TYPE') || hasAuthority('ADMIN')")
    @PutMapping("{userId}/change-login-type/{type}")
    public ResponseEntity<String> changeLoginType(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                                  @PathVariable("userId") int userId,
                                                  @PathVariable("type") int type,
                                                  HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "rest request to change user login type with id=" + userId + " and new type=" + type;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserResource.class", logMessage);


        try {


            if(type != 0 && type != 1 && type != 2) {
                return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
            }
            userService.changeLoginType(userId, type, principalId);
        } catch (NumberFormatException | InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserResource.class",
                    "NumberFormatException | InvalidDataException:" + e.getMessage());
            return new ResponseEntity<>("invalid data", HttpStatus.BAD_REQUEST);
        }


        return new ResponseEntity<>("user login type changed", HttpStatus.OK);

    }


}
