package com.hnp.filemanagement.trash.web;

import com.hnp.filemanagement.folder.domain.QuotaExceededException;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import com.hnp.filemanagement.trash.TrashListing;
import com.hnp.filemanagement.trash.TrashProperties;
import com.hnp.filemanagement.trash.TrashPurge;
import com.hnp.filemanagement.trash.TrashRestore;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The recycle bin (roadmap 13.1, 2.18.0): what was deleted from the folders the person may write in, newest first, a
 * page at a time - restored to where it was, or to a folder chosen under a name chosen, or purged for good, one entry
 * or every one older than a number of days.
 *
 * <p>Every refusal a person reads comes back as a message on the page it came from: a restore that cannot go back as
 * it was opens the restore form, saying why and asking where to.
 */
@Controller
@RequestMapping("/files/trash")
public class TrashController {

    private static final String VIEW = "file-management/files/trash.html";
    private static final String RESTORE_VIEW = "file-management/files/trash-restore.html";

    /** The ages "empty what is older than" offers, in days. */
    public static final List<Integer> AGES = List.of(7, 14, 30, 60, 90, 180);

    /** The most entries one "empty what is older than" purges; a larger bin is emptied by asking again. */
    static final int PURGE_OLDER_LIMIT = 1000;

    private final TrashListing listing;
    private final TrashRestore trashRestore;
    private final TrashPurge trashPurge;
    private final TrashProperties properties;
    private final GlobalGeneralLogging globalGeneralLogging;
    private final UiMessages messages;

    public TrashController(TrashListing listing, TrashRestore trashRestore, TrashPurge trashPurge,
                           TrashProperties properties, GlobalGeneralLogging globalGeneralLogging, UiMessages messages) {
        this.listing = listing;
        this.trashRestore = trashRestore;
        this.trashPurge = trashPurge;
        this.properties = properties;
        this.globalGeneralLogging = globalGeneralLogging;
        this.messages = messages;
    }

    //TRASH_PAGE
    @PreAuthorize("hasAuthority('TRASH_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String page(@AuthenticationPrincipal UserDetailsImpl userDetails,
                       @RequestParam(value = "kind", required = false) String kind,
                       @RequestParam(value = "name", required = false) String name,
                       @RequestParam(value = "mine", required = false) boolean mine,
                       @RequestParam(value = "before", required = false) String before,
                       @RequestParam(value = "olderThan", required = false) Integer olderThan,
                       Model model) {
        int principalId = userDetails.getId();
        TrashListing.Filter filter = TrashListing.Filter.of(kind);
        String typed = name == null ? "" : name.strip();
        globalGeneralLogging.detail("recycle bin, kind=" + filter + " mine=" + mine + (before == null ? "" : " older")
                + (olderThan == null ? "" : " preview older than " + olderThan + " day(s)"));

        model.addAttribute("summary", listing.summary(principalId));
        model.addAttribute("bin", listing.page(principalId, filter, typed, mine ? principalId : 0, before));
        model.addAttribute("kind", filter.name());
        model.addAttribute("kinds", TrashListing.Filter.values());
        model.addAttribute("name", typed);
        model.addAttribute("mine", mine);
        model.addAttribute("retentionDays", properties.retentionDays());
        model.addAttribute("ages", AGES);
        if (olderThan != null && AGES.contains(olderThan)) {
            model.addAttribute("olderThan", olderThan);
            model.addAttribute("olderPreview", listing.deletedBefore(principalId, cutoff(olderThan)));
        }
        return VIEW;
    }

    //RESTORE_FROM_TRASH
    @PreAuthorize("hasAuthority('RESTORE_FROM_TRASH') || hasAuthority('ADMIN')")
    @GetMapping("/{entryId}/restore")
    public String restoreForm(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("entryId") int entryId,
                              Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("recycle bin restore form, entry id=" + entryId);
        return listing.one(userDetails.getId(), entryId).map(row -> {
            model.addAttribute("entry", row);
            return RESTORE_VIEW;
        }).orElseGet(() -> {
            redirect.addFlashAttribute("refused", messages.get("trash.notFound"));
            return "redirect:/files/trash";
        });
    }

    //RESTORE_FROM_TRASH
    @PreAuthorize("hasAuthority('RESTORE_FROM_TRASH') || hasAuthority('ADMIN')")
    @PostMapping("/{entryId}/restore")
    public String restore(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("entryId") int entryId,
                          @RequestParam(value = "folderId", required = false) Integer folderId,
                          @RequestParam(value = "name", required = false) String name,
                          RedirectAttributes redirect) {
        globalGeneralLogging.detail("restore recycle bin entry id=" + entryId + (folderId == null ? "" : " to folder id=" + folderId)
                + (name == null || name.isBlank() ? "" : " under another name"));
        try {
            TrashRestore.Restored restored = trashRestore.restore(entryId, folderId, name, userDetails.getId());
            redirect.addFlashAttribute("saved", restored.version() == null
                    ? messages.get("trash.restored", restored.name())
                    : messages.get("trash.restoredRevision", restored.name(), restored.version()));
            redirect.addFlashAttribute("restoredFolderId", restored.folderId());
            return "redirect:/files/trash";
        } catch (InvalidDataException | QuotaExceededException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirect.addFlashAttribute("refused", refusal(e));
            redirect.addFlashAttribute("folderId", folderId);
            redirect.addFlashAttribute("newName", name);
            return "redirect:/files/trash/" + entryId + "/restore";
        } catch (ResourceNotFoundException | AccessDeniedException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirect.addFlashAttribute("refused", messages.get(e instanceof AccessDeniedException
                    ? "trash.restore.denied" : "trash.notFound"));
            return "redirect:/files/trash";
        }
    }

    //PURGE_TRASH
    @PreAuthorize("hasAuthority('PURGE_TRASH') || hasAuthority('ADMIN')")
    @PostMapping("/{entryId}/purge")
    public String purge(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("entryId") int entryId,
                        RedirectAttributes redirect) {
        globalGeneralLogging.detail("purge recycle bin entry id=" + entryId);
        try {
            trashPurge.purge(entryId, userDetails.getId());
            redirect.addFlashAttribute("saved", messages.get("trash.purged"));
        } catch (ResourceNotFoundException | AccessDeniedException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirect.addFlashAttribute("refused", messages.get("trash.notFound"));
        }
        return "redirect:/files/trash";
    }

    //PURGE_TRASH
    @PreAuthorize("hasAuthority('PURGE_TRASH') || hasAuthority('ADMIN')")
    @PostMapping("/purge-older")
    public String purgeOlder(@AuthenticationPrincipal UserDetailsImpl userDetails,
                             @RequestParam(value = "olderThan", required = false) Integer olderThan,
                             RedirectAttributes redirect) {
        globalGeneralLogging.detail("purge recycle bin entries older than " + olderThan + " day(s)");
        if (olderThan == null || !AGES.contains(olderThan)) {
            redirect.addFlashAttribute("refused", messages.get("trash.purgeOlder.invalid"));
            return "redirect:/files/trash";
        }
        int principalId = userDetails.getId();
        int purged = 0;
        int failed = 0;
        // Each in a transaction of its own: one that fails - restored meanwhile - is not the others' failure.
        for (Integer id : listing.idsDeletedBefore(principalId, cutoff(olderThan), PURGE_OLDER_LIMIT)) {
            try {
                trashPurge.purge(id, principalId);
                purged++;
            } catch (ResourceNotFoundException | AccessDeniedException e) {
                failed++;
            }
        }
        redirect.addFlashAttribute("saved", messages.get("trash.purgedOlder", purged, olderThan));
        if (failed > 0) {
            redirect.addFlashAttribute("refused", messages.get("trash.purgedOlder.skipped", failed));
        }
        return "redirect:/files/trash";
    }

    private Instant cutoff(int days) {
        return listing.now().minus(Duration.ofDays(days));
    }

    private String refusal(RuntimeException e) {
        if (e instanceof InvalidDataException invalid && invalid.getMessageCode().isPresent()) {
            return messages.get(invalid.getMessageCode().get(), invalid.getMessageArguments());
        }
        if (e instanceof QuotaExceededException) {
            return messages.get("trash.restore.quota");
        }
        return messages.get("trash.restore.refused");
    }
}
