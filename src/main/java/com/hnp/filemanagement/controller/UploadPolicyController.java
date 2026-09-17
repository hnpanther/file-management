package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.UploadPolicyForm;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.UploadPolicyService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The system-wide upload policy: which kinds of file anyone may upload, and how large each may
 * be. A role's own policy is edited on the role's page, next to its permissions and folders.
 */
@Controller
@RequestMapping("/settings/upload")
public class UploadPolicyController {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final UploadPolicyService uploadPolicyService;

    public UploadPolicyController(GlobalGeneralLogging globalGeneralLogging, UploadPolicyService uploadPolicyService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.uploadPolicyService = uploadPolicyService;
    }

    //UPLOAD_POLICY_PAGE
    @PreAuthorize("hasAuthority('UPLOAD_POLICY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String uploadPolicyPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get upload policy page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UploadPolicyController.class", logMessage);

        model.addAttribute("rows", uploadPolicyService.rowsFor(uploadPolicyService.globalLimits()));
        model.addAttribute("serverCapMb", uploadPolicyService.serverCapMb());
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");

        return "settings/upload-policy.html";
    }

    //SAVE_UPLOAD_POLICY
    @PreAuthorize("hasAuthority('SAVE_UPLOAD_POLICY') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveUploadPolicy(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   @ModelAttribute UploadPolicyForm form,
                                   Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save upload policy, allowed=" + form.getAllowed();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UploadPolicyController.class", logMessage);
        boolean valid = false;
        String message;

        try {
            uploadPolicyService.saveGlobal(UploadPolicyService.limitsFrom(form.getAllowed(), form.getMax()), principalId);
            valid = true;
            message = "اطلاعات با موفقیت ذخیره شد";
        } catch (InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UploadPolicyController.class",
                    "InvalidDataException:" + e.getMessage());
            message = "حداکثر حجم هر نوع باید بین ۱ و " + uploadPolicyService.serverCapMb() + " مگابایت باشد";
        }

        model.addAttribute("rows", uploadPolicyService.rowsFor(uploadPolicyService.globalLimits()));
        model.addAttribute("serverCapMb", uploadPolicyService.serverCapMb());
        model.addAttribute("showMessage", true);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "settings/upload-policy.html";
    }
}
