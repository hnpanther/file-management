package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.folder.domain.FolderClassAssignment;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A folder's class (roadmap Phase 14): what it is, the choice - for whoever holds {@code SET_FOLDER_CLASS} and may
 * write in the folder - and the classes' rules applied to the folders below it: counts first, then a run in the
 * background, and its progress. Reached from the explorer.
 */
@Controller
@RequestMapping("/files/folders/{folderId}/class")
public class FolderClassAssignController {

    private static final String VIEW = "file-management/files/folder-class.html";

    private final FolderClassAssignment folderClassAssignment;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public FolderClassAssignController(FolderClassAssignment folderClassAssignment, GlobalGeneralLogging globalGeneralLogging,
                                       UiMessages messages) {
        this.folderClassAssignment = folderClassAssignment;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    // SET_FOLDER_CLASS (the page; read with FOLDER_CLASSES_PAGE as well)
    @PreAuthorize("hasAuthority('SET_FOLDER_CLASS') || hasAuthority('FOLDER_CLASSES_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String page(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                       Model model) {
        globalGeneralLogging.detail("class of folder id=" + folderId);
        fill(model, folderId, userDetails, null);
        return VIEW;
    }

    // SET_FOLDER_CLASS
    @PreAuthorize("hasAuthority('SET_FOLDER_CLASS') || hasAuthority('ADMIN')")
    @PostMapping
    public String set(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                      @RequestParam(value = "classId", required = false) String classId, RedirectAttributes redirect) {
        globalGeneralLogging.detail("set class of folder id=" + folderId + " to " + classId);
        try {
            FolderClassAssignment.Assigned assigned = folderClassAssignment.set(folderId, parse(classId), userDetails.getId());
            redirect.addFlashAttribute("changed", messages.get("folderClass.set.done", assigned.templateFoldersMade()));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            redirect.addFlashAttribute("refused", messages.get(e.getMessageCode().orElse("folderClass.invalid"),
                    e.getMessageArguments()));
        }
        return "redirect:/files/folders/" + folderId + "/class";
    }

    // SET_FOLDER_CLASS
    @PreAuthorize("hasAuthority('SET_FOLDER_CLASS') || hasAuthority('ADMIN')")
    @PostMapping("/apply/preview")
    public String preview(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                          Model model) {
        globalGeneralLogging.detail("preview folder classes below folder id=" + folderId);
        fill(model, folderId, userDetails, folderClassAssignment.preview(folderId, userDetails.getId()));
        return VIEW;
    }

    // SET_FOLDER_CLASS
    @PreAuthorize("hasAuthority('SET_FOLDER_CLASS') || hasAuthority('ADMIN')")
    @PostMapping("/apply")
    public String apply(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                        RedirectAttributes redirect) {
        globalGeneralLogging.detail("apply folder classes below folder id=" + folderId);
        if (!folderClassAssignment.start(folderId, userDetails.getId())) {
            redirect.addFlashAttribute("refused", messages.get("folderClass.apply.alreadyRunning"));
        }
        return "redirect:/files/folders/" + folderId + "/class";
    }

    private void fill(Model model, int folderId, UserDetailsImpl userDetails, FolderClassAssignment.Plan plan) {
        model.addAttribute("folderClass", folderClassAssignment.view(folderId, userDetails.getId()));
        model.addAttribute("mayChange", holdsTheChange(userDetails));
        model.addAttribute("plan", plan);
        FolderClassAssignment.Progress progress = folderClassAssignment.progress();
        model.addAttribute("progress", progress != null && progress.folderId() == folderId ? progress : null);
    }

    private static Integer parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException e) {
            throw new InvalidDataException("a class is chosen by its id", "folderClass.notFound");
        }
    }

    private static boolean holdsTheChange(UserDetailsImpl userDetails) {
        return userDetails.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("SET_FOLDER_CLASS") || authority.equals("ADMIN"));
    }
}
