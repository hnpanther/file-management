package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.service.AppSettingService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.util.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The general settings: the few switches an administrator flips at run time. One so far -
 * whether the public files are open to visitors who are not signed in.
 */
@Controller
@RequestMapping("/settings/general")
public class GeneralSettingsController {

    private static final String VIEW = "settings/general.html";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final AppSettingService appSettingService;

    private final UiMessages messages;

    public GeneralSettingsController(GlobalGeneralLogging globalGeneralLogging, AppSettingService appSettingService,
                                     UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.appSettingService = appSettingService;
        this.messages = messages;
    }

    //GENERAL_SETTINGS_PAGE
    @PreAuthorize("hasAuthority('GENERAL_SETTINGS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String generalSettingsPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {

        globalGeneralLogging.detail("general settings page");

        fill(model, false, false, "");
        return VIEW;
    }

    /** A checkbox that is not ticked is not posted at all, so its absence means "off". */
    //SAVE_GENERAL_SETTINGS
    @PreAuthorize("hasAuthority('SAVE_GENERAL_SETTINGS') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveGeneralSettings(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                      @RequestParam(value = "publicFilesAnonymous", required = false) String publicFilesAnonymous,
                                      Model model) {

        int principalId = userDetails.getId();
        boolean anonymous = publicFilesAnonymous != null;
        globalGeneralLogging.detail("save general settings, publicFilesAnonymous=" + anonymous);

        appSettingService.setPublicFilesAnonymous(anonymous, principalId);
        fill(model, true, true, messages.get("form.saved"));
        return VIEW;
    }

    private void fill(Model model, boolean showMessage, boolean valid, String message) {
        model.addAttribute("publicFilesAnonymous", appSettingService.isPublicFilesAnonymous());
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
    }
}
