package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.folder.web.FileExplorerController;
import com.hnp.filemanagement.file.domain.ShareLinkService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * What the share-link panel needs to know before it asks (roadmap 10.5): the cap and the default
 * on the validity, and whether a password is mandatory. Read from properties, so this costs
 * nothing; put on the model of the two pages that open the panel - the explorer and the file page.
 */
@ControllerAdvice(assignableTypes = {FileExplorerController.class, FileController.class})
public class ShareLinkRulesAdvice {

    public record ShareLinkRules(long maxMinutes, long defaultMinutes, boolean passwordRequired) {
    }

    private final ShareLinkService shareLinkService;

    public ShareLinkRulesAdvice(ShareLinkService shareLinkService) {
        this.shareLinkService = shareLinkService;
    }

    @ModelAttribute("shareLinkRules")
    public ShareLinkRules shareLinkRules() {
        return new ShareLinkRules(shareLinkService.maxMinutes(), shareLinkService.defaultMinutes(),
                shareLinkService.passwordRequired());
    }
}
