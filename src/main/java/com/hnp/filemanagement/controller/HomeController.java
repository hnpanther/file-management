package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.service.UserHomeService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

/**
 * The application root. Signed in or not, {@code /} lands on the public file list, which is the
 * only page that renders without a permission.
 */
@Controller
public class HomeController {


    private final GlobalGeneralLogging globalGeneralLogging;
    private final UserHomeService userHomeService;

    public HomeController(GlobalGeneralLogging globalGeneralLogging, UserHomeService userHomeService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userHomeService = userHomeService;
    }


    //ACCESS_HOME
//    @PreAuthorize("hasAuthority('ACCESS_HOME') || hasAuthority('ADMIN')")
    @GetMapping
    public String home(@AuthenticationPrincipal UserDetailsImpl userDetails, HttpServletRequest request) {
        int principalId = 0;
        String principalUsername = "None";
        if(userDetails != null) {
            principalId = userDetails.getId();
            principalUsername = userDetails.getUsername();
        }
        String logMessage = "request to get home page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "HomeController.class", logMessage);

        if(userDetails == null) {
            return "redirect:/files/public-files";
        }

        // A user with a personal folder lands in it (roadmap 10.4) - provided they may open the
        // explorer, or the redirect would only be a 403 with extra steps.
        if (holds(userDetails, PermissionEnum.FILE_EXPLORER_PAGE)) {
            Optional<Folder> home = userHomeService.homeOf(principalId);
            if (home.isPresent()) {
                return "redirect:/files/explorer?folder=" + home.get().getId();
            }
        }

        // Signed-in staff land on the working screen; everyone else on the public library. Without
        // this check a user who only holds PUBLIC_FILE_PAGE would be redirected straight into a
        // 403 after logging in.
        boolean canBrowseAllFiles = holds(userDetails, PermissionEnum.GET_ALL_FILE_INFO_PAGE);

        return canBrowseAllFiles ? "redirect:/files/file-info" : "redirect:/files/public-files";
    }

    private static boolean holds(UserDetailsImpl userDetails, PermissionEnum permission) {
        return userDetails.getPermissions() != null && userDetails.getPermissions().stream()
                .anyMatch(held -> held == PermissionEnum.ADMIN || held == permission);
    }
}
