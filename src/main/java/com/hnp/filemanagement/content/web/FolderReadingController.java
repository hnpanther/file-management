package com.hnp.filemanagement.content.web;

import com.hnp.filemanagement.content.FolderReadingService;
import com.hnp.filemanagement.content.FolderReadingService.Choice;
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

import java.util.Locale;

/**
 * Whether the files of a folder, and of the folders below it, are read for the search in contents
 * (2.16.0): the folder's setting and what decides it, the folders below it with a setting of their own,
 * the revisions held back - and, for whoever holds {@code SET_FOLDER_CONTENT_READING} and may write in the
 * folder, the choice: follow the folder above, on, or off. Reached from the explorer.
 */
@Controller
@RequestMapping("/files/folders/{folderId}/content-reading")
public class FolderReadingController {

    private static final String VIEW = "file-management/files/folder-content-reading.html";

    private final FolderReadingService folderReadingService;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public FolderReadingController(FolderReadingService folderReadingService, GlobalGeneralLogging globalGeneralLogging,
                                   UiMessages messages) {
        this.folderReadingService = folderReadingService;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    // SET_FOLDER_CONTENT_READING (the page; read with CONTENT_EXTRACTION_PAGE as well)
    @PreAuthorize("hasAuthority('SET_FOLDER_CONTENT_READING') || hasAuthority('CONTENT_EXTRACTION_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String page(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                       Model model) {
        globalGeneralLogging.detail("content reading of folder id=" + folderId);
        model.addAttribute("reading", folderReadingService.view(folderId, userDetails.getId(), holdsTheChange(userDetails)));
        return VIEW;
    }

    // SET_FOLDER_CONTENT_READING
    @PreAuthorize("hasAuthority('SET_FOLDER_CONTENT_READING') || hasAuthority('ADMIN')")
    @PostMapping
    public String set(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                      @RequestParam(value = "choice", required = false) String choice, RedirectAttributes redirect) {
        globalGeneralLogging.detail("set content reading of folder id=" + folderId + " to " + choice);
        try {
            FolderReadingService.Changed changed = folderReadingService.set(folderId, choiceOf(choice), userDetails.getId());
            redirect.addFlashAttribute("changed", messages.get(changed.readsNow() ? "folderReading.changed.on" : "folderReading.changed.off",
                    changed.queued(), changed.heldBack()));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            redirect.addFlashAttribute("refused", messages.get(e.getMessageCode().orElse("folderReading.invalid")));
        }
        return "redirect:/files/folders/" + folderId + "/content-reading";
    }

    /** The choice as the form sends it; anything else is no choice, refused by the service. */
    private static Choice choiceOf(String choice) {
        if (choice == null) {
            return null;
        }
        try {
            return Choice.valueOf(choice.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean holdsTheChange(UserDetailsImpl userDetails) {
        return userDetails.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> authority.equals("SET_FOLDER_CONTENT_READING") || authority.equals("ADMIN"));
    }
}
