package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.ContentKindForm;
import com.hnp.filemanagement.file.domain.ContentProbeDTO;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.file.domain.ContentKindService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import com.hnp.filemanagement.file.domain.ContentTypes;
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

    private final UiMessages messages;

    public ContentKindController(GlobalGeneralLogging globalGeneralLogging, ContentKindService contentKindService,
                                 UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.contentKindService = contentKindService;
        this.messages = messages;
    }

    //CONTENT_KIND_PAGE
    @PreAuthorize("hasAuthority('CONTENT_KIND_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String contentKindsPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {

        globalGeneralLogging.detail("content kinds page");

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
                        Model model) {

        globalGeneralLogging.detail("probe a sample, name=" + sample.getOriginalFilename() + ", size=" + sample.getSize());

        if (sample.isEmpty()) {
            fill(model, new ContentKindForm(), null, true, false, messages.get("contentKind.noSample"));
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
                                 Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("add content kind ." + form.getExtension() + " as " + form.getMediaType());

        try {
            contentKindService.add(form, principalId);
            fill(model, new ContentKindForm(), null, true, true, messages.get("form.saved"));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, form, null, true, false, messages.get("form.invalidWithReason", e.getMessage()));
        } catch (DuplicateResourceException e) {
            globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
            fill(model, form, null, true, false, messages.get("contentKind.duplicate"));
        }
        return VIEW;
    }

    //DELETE_CONTENT_KIND
    @PreAuthorize("hasAuthority('DELETE_CONTENT_KIND') || hasAuthority('ADMIN')")
    @PostMapping("/{extension}/delete")
    public String deleteContentKind(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @PathVariable("extension") String extension,
                                    Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("delete content kind ." + extension);

        try {
            contentKindService.delete(extension, principalId);
            fill(model, new ContentKindForm(), null, true, true, messages.get("contentKind.deleted"));
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.detail("ResourceNotFoundException:" + e.getMessage());
            fill(model, new ContentKindForm(), null, true, false, messages.get("form.invalidShort"));
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
