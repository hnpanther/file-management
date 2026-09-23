package com.hnp.filemanagement.resource;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiResult;
import com.hnp.filemanagement.dto.ShareLinkDTO;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.service.ShareLinkService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Making and revoking temporary share links as JSON, for the explorer's pane, the file page and
 * the share-links page (roadmap 10.5). The public download lives in {@code ShareLinkController}.
 */
@RestController
@RequestMapping("/resource")
public class ShareLinkResource {

    private final GlobalGeneralLogging globalGeneralLogging;
    private final ShareLinkService shareLinkService;

    public ShareLinkResource(GlobalGeneralLogging globalGeneralLogging, ShareLinkService shareLinkService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.shareLinkService = shareLinkService;
    }

    /**
     * @param minutes      validity; null for the default, above the cap clamped to it
     * @param password     optional, unless the installation requires one
     * @param maxDownloads optional cap on downloads
     */
    public record CreateShareLinkRequest(Integer minutes, String password, Integer maxDownloads) {
    }

    /** The link as created, plus the absolute URL to hand out. */
    public record CreatedShareLink(ShareLinkDTO link, String url) {
    }

    /**
     * Makes a link to one revision. The token in the answer is shown this once and never
     * again; the URL is built from this request's scheme, host and context path.
     */
    //CREATE_SHARE_LINK
    @PreAuthorize("hasAuthority('CREATE_SHARE_LINK') || hasAuthority('ADMIN')")
    @PostMapping("files/file-details/{fileDetailsId}/share-links")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedShareLink create(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   @PathVariable("fileDetailsId") int fileDetailsId,
                                   @RequestBody CreateShareLinkRequest body,
                                   HttpServletRequest request) {
        globalGeneralLogging.controllerLogging(userDetails, request, ShareLinkResource.class,
                "create share link for fileDetails id=" + fileDetailsId + ", minutes=" + body.minutes()
                        + ", maxDownloads=" + body.maxDownloads() + ", password=" + (body.password() == null || body.password().isBlank() ? "no" : "yes"));
        ShareLinkDTO link = shareLinkService.create(fileDetailsId, body.minutes(), body.password(),
                body.maxDownloads(), userDetails.getId());
        String url = ServletUriComponentsBuilder.fromContextPath(request).path(link.path()).build().toUriString();
        return new CreatedShareLink(link, url);
    }

    /** Revokes a link: one's own under {@code CREATE_SHARE_LINK}, anyone's under {@code REVOKE_SHARE_LINK}. */
    //CREATE_SHARE_LINK, REVOKE_SHARE_LINK
    @PreAuthorize("hasAuthority('CREATE_SHARE_LINK') || hasAuthority('REVOKE_SHARE_LINK') || hasAuthority('ADMIN')")
    @DeleteMapping("share-links/{linkId}")
    public ApiResult revoke(@AuthenticationPrincipal UserDetailsImpl userDetails,
                            @PathVariable("linkId") int linkId,
                            HttpServletRequest request) {
        globalGeneralLogging.controllerLogging(userDetails, request, ShareLinkResource.class,
                "revoke share link id=" + linkId);
        boolean any = userDetails.getPermissions() != null && userDetails.getPermissions().stream()
                .anyMatch(held -> held == PermissionEnum.ADMIN || held == PermissionEnum.REVOKE_SHARE_LINK);
        shareLinkService.revoke(linkId, userDetails.getId(), any);
        return ApiResult.deleted("share-link", linkId);
    }
}
