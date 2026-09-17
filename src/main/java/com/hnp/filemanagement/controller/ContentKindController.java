package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ContentKindForm;
import com.hnp.filemanagement.dto.ContentProbeDTO;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.ContentKindService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.validation.ContentTypes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * The content catalogue: the built-in kinds, the custom ones an administrator added, a probe that
 * describes a sample file, and a form to add a kind - prefilled from the probe when there was one.
 */
@Controller
@RequestMapping("/settings/content-kinds")
public class ContentKindController {

    private static final String VIEW = "settings/content-kinds.html";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final ContentKindService contentKindService;

    public ContentKindController(GlobalGeneralLogging globalGeneralLogging, ContentKindService contentKindService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.contentKindService = contentKindService;
    }

    //CONTENT_KIND_PAGE
    @PreAuthorize("hasAuthority('CONTENT_KIND_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String contentKindsPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get content kinds page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "ContentKindController.class", logMessage);

        fill(model, new ContentKindForm(), null, false, false, "");
        return VIEW;
    }

    /**
     * Describes the sample and re-renders the page with the result and the add-form prefilled
     * from it. Nothing is stored: the sample is read for its first block and dropped.
     */
    //CONTENT_KIND_PAGE (the probe reads; it changes nothing)
    @PreAuthorize("hasAuthority('CONTENT_KIND_PAGE') || hasAuthority('ADMIN')")
    @PostMapping("/probe")
    public String probe(@AuthenticationPrincipal UserDetailsImpl userDetails,
                        @RequestParam("sample") MultipartFile sample,
                        Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to probe a sample, name=" + sample.getOriginalFilename() + ", size=" + sample.getSize();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "ContentKindController.class", logMessage);

        if (sample.isEmpty()) {
            fill(model, new ContentKindForm(), null, true, false, "فایلی برای بررسی انتخاب نشده است");
            return VIEW;
        }
        ContentProbeDTO probe = contentKindService.probe(sample);

        ContentKindForm form = new ContentKindForm();
        form.setExtension(probe.extension());
        form.setMediaType(probe.knownAs() != null ? probe.knownAs() : probe.detectedType());
        form.setTextOnly(probe.looksLikeText() && probe.detectedType().startsWith("text/"));
        form.setSignatureHex(form.isTextOnly() ? "" : probe.suggestedHex());
        form.setSignatureOffset(0);

        fill(model, form, probe, false, false, "");
        return VIEW;
    }

    //SAVE_CONTENT_KIND
    @PreAuthorize("hasAuthority('SAVE_CONTENT_KIND') || hasAuthority('ADMIN')")
    @PostMapping
    public String addContentKind(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                 @ModelAttribute ContentKindForm form,
                                 Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to add content kind ." + form.getExtension() + " as " + form.getMediaType();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "ContentKindController.class", logMessage);

        try {
            contentKindService.add(form, principalId);
            fill(model, new ContentKindForm(), null, true, true, "اطلاعات با موفقیت ذخیره شد");
        } catch (InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "ContentKindController.class", "InvalidDataException:" + e.getMessage());
            fill(model, form, null, true, false, "اطلاعات صحیح نمیباشد: " + e.getMessage());
        } catch (DuplicateResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "ContentKindController.class", "DuplicateResourceException:" + e.getMessage());
            fill(model, form, null, true, false, "این پسوند قبلاً تعریف شده است");
        }
        return VIEW;
    }

    //DELETE_CONTENT_KIND
    @PreAuthorize("hasAuthority('DELETE_CONTENT_KIND') || hasAuthority('ADMIN')")
    @PostMapping("/{extension}/delete")
    public String deleteContentKind(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("extension") String extension,
                                    Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to delete content kind ." + extension;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "ContentKindController.class", logMessage);

        try {
            contentKindService.delete(extension, principalId);
            fill(model, new ContentKindForm(), null, true, true, "نوع فایل حذف شد");
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "ContentKindController.class", "ResourceNotFoundException:" + e.getMessage());
            fill(model, new ContentKindForm(), null, true, false, "اطلاعات صحیح نمیباشد");
        }
        return VIEW;
    }

    private void fill(Model model, ContentKindForm form, ContentProbeDTO probe, boolean showMessage, boolean valid, String message) {
        model.addAttribute("kinds", ContentTypes.kinds());
        model.addAttribute("customCount", contentKindService.customKinds().size());
        model.addAttribute("form", form);
        model.addAttribute("probe", probe);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
    }
}
