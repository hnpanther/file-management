package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.UploadPolicyForm;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
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

    private final UiMessages messages;

    public UploadPolicyController(GlobalGeneralLogging globalGeneralLogging, UploadPolicyService uploadPolicyService,
                                  UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.uploadPolicyService = uploadPolicyService;
        this.messages = messages;
    }

    //UPLOAD_POLICY_PAGE
    @PreAuthorize("hasAuthority('UPLOAD_POLICY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String uploadPolicyPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {

        globalGeneralLogging.detail("upload policy page");

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
                                   Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save upload policy, allowed=" + form.getAllowed());
        boolean valid = false;
        String message;

        try {
            uploadPolicyService.saveGlobal(UploadPolicyService.limitsFrom(form.getAllowed(), form.getMax()), principalId);
            valid = true;
            message = messages.get("form.saved");
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            message = messages.get("uploadPolicy.sizeOutOfRange", uploadPolicyService.serverCapMb());
        }

        model.addAttribute("rows", uploadPolicyService.rowsFor(uploadPolicyService.globalLimits()));
        model.addAttribute("serverCapMb", uploadPolicyService.serverCapMb());
        model.addAttribute("showMessage", true);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "settings/upload-policy.html";
    }
}
