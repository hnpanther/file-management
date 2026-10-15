package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.domain.OnlineUserService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Who is signed in (2.16.0): each person with a session on this instance and their sessions - and a
 * button to end one, or all of a person's. A session is named on the page and in the form by its
 * handle, never by its id ({@code ActiveUserSessions}).
 */
@Controller
@RequestMapping("/settings/online-users")
public class OnlineUserController {

    private static final String VIEW = "settings/online-users.html";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final OnlineUserService onlineUserService;
    private final UiMessages messages;

    public OnlineUserController(GlobalGeneralLogging globalGeneralLogging, OnlineUserService onlineUserService,
                                UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.onlineUserService = onlineUserService;
        this.messages = messages;
    }

    //ONLINE_USERS_PAGE
    @PreAuthorize("hasAuthority('ONLINE_USERS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String onlineUsersPage(@AuthenticationPrincipal UserDetailsImpl userDetails, HttpServletRequest request,
                                  Model model) {
        globalGeneralLogging.detail("online users page");
        fill(model, request, false, false, "");
        return VIEW;
    }

    //END_USER_SESSION
    @PreAuthorize("hasAuthority('END_USER_SESSION') || hasAuthority('ADMIN')")
    @PostMapping("/end")
    public String endSession(@AuthenticationPrincipal UserDetailsImpl userDetails, HttpServletRequest request,
                             @RequestParam(value = "session", required = false) String handle, Model model) {
        globalGeneralLogging.detail("end a session, handle=" + handle);
        try {
            String username = onlineUserService.endSession(handle, userDetails.getId(), currentSessionId(request));
            fill(model, request, true, true, messages.get("onlineUsers.ended", username));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, request, true, false, messages.get(e.getMessageCode().orElse("onlineUsers.gone")));
        }
        return VIEW;
    }

    //END_USER_SESSION
    @PreAuthorize("hasAuthority('END_USER_SESSION') || hasAuthority('ADMIN')")
    @PostMapping("/end-all")
    public String endSessionsOf(@AuthenticationPrincipal UserDetailsImpl userDetails, HttpServletRequest request,
                                @RequestParam("userId") int userId, Model model) {
        globalGeneralLogging.detail("end every session of user id=" + userId);
        try {
            int ended = onlineUserService.endSessionsOf(userId, userDetails.getId(), currentSessionId(request));
            fill(model, request, true, ended > 0,
                    ended > 0 ? messages.get("onlineUsers.endedAll", ended) : messages.get("onlineUsers.noneToEnd"));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, request, true, false, messages.get(e.getMessageCode().orElse("onlineUsers.gone")));
        }
        return VIEW;
    }

    private void fill(Model model, HttpServletRequest request, boolean showMessage, boolean valid, String message) {
        model.addAttribute("online", onlineUserService.page(currentSessionId(request)));
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
    }

    private static String currentSessionId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getId();
    }
}
