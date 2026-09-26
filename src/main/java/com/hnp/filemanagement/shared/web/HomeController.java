package com.hnp.filemanagement.shared.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;


/**
 * The application root, open to everyone: it only decides where a visitor belongs. Anonymous
 * visitors and people who may not browse the file list land on the public file list; everyone
 * else on the file list.
 */
@Controller
public class HomeController {

    private final GlobalGeneralLogging globalGeneralLogging;

    public HomeController(GlobalGeneralLogging globalGeneralLogging) {
        this.globalGeneralLogging = globalGeneralLogging;
    }

    @GetMapping
    public String home(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        globalGeneralLogging.detail("home page");

        if(userDetails == null) {
            return "redirect:/files/public-files";
        }

        // A personal folder does not change where anyone lands (roadmap 10.4): it is a link in
        // the navigation, offered to whoever has one (UserHomeAdvice), not a redirect.
        // Signed-in staff land on the working screen; everyone else on the public library. Without
        // this check a user who may not list the files would be redirected straight into a 403
        // after logging in.
        boolean canBrowseAllFiles = holds(userDetails, PermissionEnum.GET_ALL_FILE_INFO_PAGE);

        return canBrowseAllFiles ? "redirect:/files/file-info" : "redirect:/files/public-files";
    }

    private static boolean holds(UserDetailsImpl userDetails, PermissionEnum permission) {
        return userDetails.getPermissions() != null && userDetails.getPermissions().stream()
                .anyMatch(held -> held == PermissionEnum.ADMIN || held == permission);
    }
}
