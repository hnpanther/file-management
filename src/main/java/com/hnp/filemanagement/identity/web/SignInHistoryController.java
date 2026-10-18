package com.hnp.filemanagement.identity.web;

import com.hnp.filemanagement.identity.signin.SignInHistory;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The history of signing in and out (2.17.1): a summary of the last day, then every event newest first, a page at a
 * time, narrowed by a period, a kind and a name - and, from a user's page, by their account.
 */
@Controller
@RequestMapping("/settings/sign-ins")
public class SignInHistoryController {

    private static final String VIEW = "settings/sign-ins.html";

    private final SignInHistory signInHistory;
    private final GlobalGeneralLogging globalGeneralLogging;

    public SignInHistoryController(SignInHistory signInHistory, GlobalGeneralLogging globalGeneralLogging) {
        this.signInHistory = signInHistory;
        this.globalGeneralLogging = globalGeneralLogging;
    }

    //SIGN_IN_HISTORY_PAGE
    @PreAuthorize("hasAuthority('SIGN_IN_HISTORY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String page(@RequestParam(value = "days", required = false) Integer days,
                       @RequestParam(value = "kind", required = false) String kind,
                       @RequestParam(value = "name", required = false) String name,
                       @RequestParam(value = "user", required = false) Integer userId,
                       @RequestParam(value = "before", required = false) String before,
                       Model model) {
        int period = SignInHistory.periodOf(days == null ? 0 : days);
        SignInHistory.Filter filter = SignInHistory.Filter.of(kind);
        String typed = name == null ? "" : name.strip();
        int account = userId == null || userId < 0 ? 0 : userId;
        globalGeneralLogging.detail("sign-in history, days=" + period + " kind=" + filter + " user=" + account
                + (before == null ? "" : " older"));

        model.addAttribute("summary", signInHistory.lastDay());
        model.addAttribute("history", signInHistory.page(period, filter, typed, account, before));
        model.addAttribute("days", period);
        model.addAttribute("periods", SignInHistory.PERIODS);
        model.addAttribute("kind", filter.name());
        model.addAttribute("kinds", SignInHistory.Filter.values());
        model.addAttribute("name", typed);
        model.addAttribute("user", account);
        return VIEW;
    }
}
