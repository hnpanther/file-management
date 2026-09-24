package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.entity.FileShareLink;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.ShareLinkService;
import com.hnp.filemanagement.util.ContentDispositions;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Temporary share links from the browser's side (roadmap 10.5).
 *
 * <p>{@code /share/{token}} is public - no sign-in, outside folder access - because the link is
 * the access. {@code GET} shows a landing page (the file, its size, when the link ends, a password
 * field if the link has one) and {@code POST} downloads: a download counts against the link's
 * cap, and a link previewer or a prefetch must not spend one, so nothing is handed out on a
 * {@code GET}. A token that names nothing usable - unknown, expired, revoked, used up - is one
 * identical 404, whatever the reason.
 *
 * <p>{@code /files/share-links} is the maker's page: their links, and a revoke each.
 */
@Controller
public class ShareLinkController {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final ShareLinkService shareLinkService;

    public ShareLinkController(GlobalGeneralLogging globalGeneralLogging, ShareLinkService shareLinkService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.shareLinkService = shareLinkService;
    }

    // ------------------------------------------------------------------ the public side

    /** The landing page: what the link is for, and the button (and password) that downloads it. */
    @GetMapping("/share/{token}")
    public String landing(@PathVariable("token") String token, Model model) {
        globalGeneralLogging.detail("share link landing page");
        FileShareLink link = shareLinkService.usable(token)
                .orElseThrow(() -> new ResourceNotFoundException("no such share link"));
        describe(model, link, token);
        model.addAttribute("outcome", link.hasPassword() ? "PASSWORD_REQUIRED" : "READY");
        return "share/download.html";
    }

    /** The download itself, or the landing page again with the reason it did not happen. */
    @PostMapping("/share/{token}")
    public Object download(@PathVariable("token") String token,
                           @RequestParam(value = "password", required = false) String password,
                           Model model) {
        globalGeneralLogging.detail("download via share link");
        ShareLinkService.Attempt attempt = shareLinkService.download(token, password == null ? "" : password);
        if (attempt.outcome() == ShareLinkService.Outcome.DOWNLOAD) {
            return attachment(attempt.file());
        }
        // Re-read for the page: the attempt may have locked the link.
        FileShareLink link = shareLinkService.usable(token)
                .orElseThrow(() -> new ResourceNotFoundException("no such share link"));
        describe(model, link, token);
        model.addAttribute("outcome", attempt.outcome().name());
        model.addAttribute("lockedUntil", attempt.lockedUntil());
        return "share/download.html";
    }

    private static void describe(Model model, FileShareLink link, String token) {
        model.addAttribute("token", token);
        model.addAttribute("fileName", link.getFileDetails().getFileName());
        model.addAttribute("version", link.getFileDetails().getVersion());
        model.addAttribute("sizeKb", Math.max(1L, link.getFileDetails().getFileSize() / 1024));
        model.addAttribute("expiresAt", link.getExpiresAt());
        model.addAttribute("passwordProtected", link.hasPassword());
        model.addAttribute("downloadsLeft", link.getMaxDownloads() == null ? null : link.getMaxDownloads() - link.getDownloadCount());
    }

    /** As the public download sends a file: an attachment, never inline, with the same headers. */
    static ResponseEntity<?> attachment(FileDownloadDTO file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(file.getFileName()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.getResource());
    }

    // ------------------------------------------------------------------ the maker's page

    //SHARE_LINKS_PAGE
    @PreAuthorize("hasAuthority('SHARE_LINKS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/files/share-links")
    public String shareLinksPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        globalGeneralLogging.detail("share links page");
        boolean all = holds(userDetails, PermissionEnum.REVOKE_SHARE_LINK);
        model.addAttribute("links", all ? shareLinkService.listAll() : shareLinkService.listMine(userDetails.getId()));
        model.addAttribute("allLinks", all);
        model.addAttribute("principalId", userDetails.getId());
        return "file-management/files/share-links.html";
    }

    static boolean holds(UserDetailsImpl userDetails, PermissionEnum permission) {
        return userDetails.getPermissions() != null && userDetails.getPermissions().stream()
                .anyMatch(held -> held == PermissionEnum.ADMIN || held == permission);
    }
}
