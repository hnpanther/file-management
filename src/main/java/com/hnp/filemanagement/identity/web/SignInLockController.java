package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.domain.SignInLockService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The locked sign-ins (roadmap 12.1): the usernames wrong passwords have locked, or have failures
 * counted against, with the addresses the attempts came from - and a button to lift one lock
 * early, which otherwise only a restart does, for everyone at once.
 */
@Controller
@RequestMapping("/settings/locked-sign-ins")
public class SignInLockController {

    private static final String VIEW = "settings/locked-sign-ins.html";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final SignInLockService signInLockService;
    private final UiMessages messages;

    public SignInLockController(GlobalGeneralLogging globalGeneralLogging, SignInLockService signInLockService,
                                UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.signInLockService = signInLockService;
        this.messages = messages;
    }

    //LOCKED_SIGN_INS_PAGE
    @PreAuthorize("hasAuthority('LOCKED_SIGN_INS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String lockedSignInsPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @RequestParam(value = "page", required = false) Integer page,
                                    Model model) {

        int pageNumber = PageRequests.number(page);
        globalGeneralLogging.detail("locked sign-ins page=" + pageNumber);

        fill(model, pageNumber, false, false, "");
        return VIEW;
    }

    //UNLOCK_SIGN_IN
    @PreAuthorize("hasAuthority('UNLOCK_SIGN_IN') || hasAuthority('ADMIN')")
    @PostMapping("/unlock")
    public String unlockSignIn(@AuthenticationPrincipal UserDetailsImpl userDetails,
                               @RequestParam(value = "username", required = false) String username,
                               Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("unlock sign-in, username=" + username);

        try {
            if (signInLockService.unlock(username, principalId)) {
                fill(model, 0, true, true, messages.get("lockedSignIns.unlocked", username));
            } else {
                fill(model, 0, true, false, messages.get("lockedSignIns.notLocked", username));
            }
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, 0, true, false, messages.get("lockedSignIns.adminOnly"));
        }
        return VIEW;
    }

    private void fill(Model model, int page, boolean showMessage, boolean valid, String message) {
        model.addAttribute("locks", signInLockService.page(page));
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
    }
}
