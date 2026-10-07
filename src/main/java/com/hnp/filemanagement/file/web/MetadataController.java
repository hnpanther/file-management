package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.FileMetadataService;
import com.hnp.filemanagement.folder.domain.FolderMetadataService;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.PreconditionFailedException;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.shared.metadata.MetadataView;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
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
 * Metadata on the web (roadmap 12.2, 12.3 - 2.13.0): a file's - every format of its newest version -
 * or one revision's edited on a page of its own; a folder's shown, edited, and its changes listed;
 * and the queue of a folder's children nobody has described yet.
 *
 * <p>Every form carries the entity tag of the document it was opened on and saves conditioned on
 * it ({@link MetadataPrecondition}): two people editing one document do not silently overwrite each
 * other - the second is told it changed and shown it again. The checks are the services' -
 * {@code WRITE} on the folder, the kind of folder, the document's rules - and a refusal re-renders
 * the form with what was typed and why, never an error page.
 */
@Controller
@RequestMapping("/files")
public class MetadataController {

    private static final String EDIT_VIEW = "file-management/files/metadata-edit.html";
    private static final String FOLDER_VIEW = "file-management/files/folder-metadata.html";
    private static final String QUEUE_VIEW = "file-management/files/folder-undescribed.html";

    private final FileMetadataService fileMetadataService;
    private final FolderMetadataService folderMetadataService;
    private final MetadataView metadataView;
    private final UiMessages messages;
    private final GlobalGeneralLogging globalGeneralLogging;

    public MetadataController(FileMetadataService fileMetadataService, FolderMetadataService folderMetadataService,
                              MetadataView metadataView, UiMessages messages, GlobalGeneralLogging globalGeneralLogging) {
        this.fileMetadataService = fileMetadataService;
        this.folderMetadataService = folderMetadataService;
        this.metadataView = metadataView;
        this.messages = messages;
        this.globalGeneralLogging = globalGeneralLogging;
    }

    // ------------------------------------------------------------------ a file's, a revision's

    //EDIT_FILE_METADATA
    @PreAuthorize("hasAuthority('EDIT_FILE_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/metadata")
    public String fileForm(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId,
                           Model model) {
        globalGeneralLogging.detail("metadata form of file info id=" + fileInfoId);
        var current = fileMetadataService.ofFile(fileInfoId, userDetails.getId());
        return editView(model, current, "file", metadataView.json(current.tree()), current.etag(), null);
    }

    //EDIT_FILE_METADATA
    @PreAuthorize("hasAuthority('EDIT_FILE_METADATA') || hasAuthority('ADMIN')")
    @PostMapping("file-info/{fileInfoId}/metadata")
    public String saveFile(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId,
                           @RequestParam(value = "metadata", required = false) String metadata,
                           @RequestParam(value = "etag", required = false) String etag,
                           Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("save metadata of file info id=" + fileInfoId);
        try {
            fileMetadataService.replaceOnFile(fileInfoId, metadata, new MetadataPrecondition(etag, null), userDetails.getId());
        } catch (InvalidDataException | PreconditionFailedException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ": " + e.getMessage());
            var current = fileMetadataService.ofFile(fileInfoId, userDetails.getId());
            return editView(model, current, "file", metadata, refusedTag(e, etag, current.etag()), reasonOf(e));
        }
        redirect.addFlashAttribute("metadataSaved", messages.get("metadata.saved"));
        return "redirect:/files/file-info/" + fileInfoId + "#file-metadata";
    }

    //EDIT_FILE_METADATA (one revision's)
    @PreAuthorize("hasAuthority('EDIT_FILE_METADATA') || hasAuthority('ADMIN')")
    @GetMapping("file-details/{fileDetailsId}/metadata")
    public String revisionForm(@AuthenticationPrincipal UserDetailsImpl userDetails,
                               @PathVariable("fileDetailsId") int fileDetailsId, Model model) {
        globalGeneralLogging.detail("metadata form of file details id=" + fileDetailsId);
        var current = fileMetadataService.ofRevision(fileDetailsId, userDetails.getId());
        return editView(model, current, "revision", metadataView.json(current.tree()), current.etag(), null);
    }

    //EDIT_FILE_METADATA (one revision's)
    @PreAuthorize("hasAuthority('EDIT_FILE_METADATA') || hasAuthority('ADMIN')")
    @PostMapping("file-details/{fileDetailsId}/metadata")
    public String saveRevision(@AuthenticationPrincipal UserDetailsImpl userDetails,
                               @PathVariable("fileDetailsId") int fileDetailsId,
                               @RequestParam(value = "metadata", required = false) String metadata,
                               @RequestParam(value = "etag", required = false) String etag,
                               Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("save metadata of file details id=" + fileDetailsId);
        FileMetadataService.RevisionMetadata saved;
        try {
            saved = fileMetadataService.replaceOnRevision(fileDetailsId, metadata, new MetadataPrecondition(etag, null),
                    userDetails.getId()).current();
        } catch (InvalidDataException | PreconditionFailedException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ": " + e.getMessage());
            var current = fileMetadataService.ofRevision(fileDetailsId, userDetails.getId());
            return editView(model, current, "revision", metadata, refusedTag(e, etag, current.etag()), reasonOf(e));
        }
        redirect.addFlashAttribute("metadataSaved", messages.get("metadata.saved"));
        return "redirect:/files/file-info/" + saved.fileInfoId() + "#file-metadata";
    }

    private String editView(Model model, FileMetadataService.RevisionMetadata revision, String target, String text,
                            String etag, String error) {
        model.addAttribute("revision", revision);
        model.addAttribute("target", target);
        model.addAttribute("metadataText", text == null ? "" : text);
        model.addAttribute("etag", etag);
        model.addAttribute("error", error);
        return EDIT_VIEW;
    }

    // ------------------------------------------------------------------ a folder's

    /**
     * A folder's metadata and its changes; the form too, for whoever may write them. Shown to
     * whoever may open the explorer and read the folder.
     */
    //EDIT_FOLDER_METADATA (the page; read with FILE_EXPLORER_PAGE as well)
    @PreAuthorize("hasAuthority('EDIT_FOLDER_METADATA') || hasAuthority('FILE_EXPLORER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("folders/{folderId}/metadata")
    public String folder(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                         Model model) {
        globalGeneralLogging.detail("metadata of folder id=" + folderId);
        var folder = folderMetadataService.of(folderId, userDetails.getId());
        return folderView(model, userDetails, folder, metadataView.json(folder.tree()), folder.etag(), null);
    }

    //EDIT_FOLDER_METADATA
    @PreAuthorize("hasAuthority('EDIT_FOLDER_METADATA') || hasAuthority('ADMIN')")
    @PostMapping("folders/{folderId}/metadata")
    public String saveFolder(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                             @RequestParam(value = "metadata", required = false) String metadata,
                             @RequestParam(value = "etag", required = false) String etag,
                             Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("save metadata of folder id=" + folderId);
        try {
            folderMetadataService.replace(folderId, metadata, new MetadataPrecondition(etag, null), userDetails.getId());
        } catch (InvalidDataException | PreconditionFailedException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ": " + e.getMessage());
            var folder = folderMetadataService.of(folderId, userDetails.getId());
            return folderView(model, userDetails, folder, metadata, refusedTag(e, etag, folder.etag()), reasonOf(e));
        }
        redirect.addFlashAttribute("metadataSaved", messages.get("metadata.saved"));
        return "redirect:/files/folders/" + folderId + "/metadata";
    }

    private String folderView(Model model, UserDetailsImpl userDetails, FolderMetadataService.Described folder, String text,
                              String etag, String error) {
        model.addAttribute("folder", folder);
        model.addAttribute("metadataText", text == null ? "" : text);
        model.addAttribute("etag", etag);
        model.addAttribute("error", error);
        model.addAttribute("mayEdit", holds(userDetails, "EDIT_FOLDER_METADATA")
                && folderMetadataService.mayWrite(folder.folderId(), userDetails.getId()));
        model.addAttribute("changes", folderMetadataService.changesOf(folder.folderId(), 0, 20, userDetails.getId()).getContent());
        return FOLDER_VIEW;
    }

    // ------------------------------------------------------------------ the queue

    //FOLDER_METADATA_QUEUE_PAGE
    @PreAuthorize("hasAuthority('FOLDER_METADATA_QUEUE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("folders/{folderId}/undescribed")
    public String undescribed(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("folderId") int folderId,
                              @RequestParam(value = "page", required = false) Integer page, Model model) {
        globalGeneralLogging.detail("folders without metadata under folder id=" + folderId + " page=" + page);
        int number = PageRequests.number(page);
        model.addAttribute("folder", folderMetadataService.of(folderId, userDetails.getId()));
        model.addAttribute("queue", folderMetadataService.undescribedUnder(folderId, number, 50, userDetails.getId()));
        model.addAttribute("page", number);
        return QUEUE_VIEW;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The tag the form is sent with again after a refusal: after a bad document, still the one it
     * was opened on; after a change in between, the current one - the person sees why and decides.
     */
    private static String refusedTag(RuntimeException e, String sent, String current) {
        return e instanceof PreconditionFailedException ? current : sent;
    }

    private String reasonOf(RuntimeException e) {
        if (e instanceof PreconditionFailedException) {
            return messages.get("metadata.changedMeanwhile");
        }
        InvalidDataException invalid = (InvalidDataException) e;
        return invalid.getMessageCode()
                .map(code -> messages.get(code, invalid.getMessageArguments()))
                .orElseGet(() -> messages.get("form.invalid"));
    }

    private static boolean holds(UserDetailsImpl userDetails, String authority) {
        return userDetails.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(each -> each.equals(authority) || each.equals("ADMIN"));
    }
}
