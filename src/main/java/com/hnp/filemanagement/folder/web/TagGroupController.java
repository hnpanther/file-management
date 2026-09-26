package com.hnp.filemanagement.folder.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.folder.domain.TagGroupForm;
import com.hnp.filemanagement.shared.exception.DependencyResourceException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.folder.domain.TagGroupService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The tag groups - the "general tags" - as a settings page: the list with what each is used by,
 * and one form that creates a group or edits the one chosen from the list.
 */
@Controller
@RequestMapping("/settings/tag-groups")
public class TagGroupController {

    private static final String VIEW = "settings/tag-groups.html";

    private final GlobalGeneralLogging globalGeneralLogging;
    private final TagGroupService tagGroupService;

    private final UiMessages messages;

    public TagGroupController(GlobalGeneralLogging globalGeneralLogging, TagGroupService tagGroupService,
                              UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.tagGroupService = tagGroupService;
        this.messages = messages;
    }

    //TAG_GROUP_PAGE
    @PreAuthorize("hasAuthority('TAG_GROUP_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String tagGroupsPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {

        globalGeneralLogging.detail("tag groups page");

        fill(model, new TagGroupForm(), false, false, "");
        return VIEW;
    }

    /** The same page with the form filled from one group, for editing it. */
    //TAG_GROUP_PAGE
    @PreAuthorize("hasAuthority('TAG_GROUP_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/{id}")
    public String editTagGroupPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   @PathVariable("id") int id,
                                   Model model) {

        globalGeneralLogging.detail("tag group edit page, id=" + id);

        try {
            fill(model, tagGroupService.formOf(id), false, false, "");
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.detail("ResourceNotFoundException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, messages.get("tagGroup.notFound"));
        }
        return VIEW;
    }

    //SAVE_TAG_GROUP
    @PreAuthorize("hasAuthority('SAVE_TAG_GROUP') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveTagGroup(@AuthenticationPrincipal UserDetailsImpl userDetails,
                               @ModelAttribute TagGroupForm form,
                               Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save tag group id=" + form.getId() + ", name=" + form.getName());

        try {
            if (form.getId() == null) {
                tagGroupService.create(form, principalId);
            } else {
                tagGroupService.update(form.getId(), form, principalId);
            }
            fill(model, new TagGroupForm(), true, true, messages.get("form.saved"));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            fill(model, form, true, false, messages.get("form.invalid"));
        } catch (DuplicateResourceException e) {
            globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
            fill(model, form, true, false, messages.get("tagGroup.duplicate"));
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.detail("ResourceNotFoundException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, messages.get("tagGroup.notFound"));
        }
        return VIEW;
    }

    //DELETE_TAG_GROUP
    @PreAuthorize("hasAuthority('DELETE_TAG_GROUP') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/delete")
    public String deleteTagGroup(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                 @PathVariable("id") int id,
                                 Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("delete tag group id=" + id);

        try {
            tagGroupService.delete(id, principalId);
            fill(model, new TagGroupForm(), true, true, messages.get("tagGroup.deleted"));
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.detail("ResourceNotFoundException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, messages.get("tagGroup.notFound"));
        } catch (DependencyResourceException e) {
            globalGeneralLogging.detail("DependencyResourceException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, messages.get("tagGroup.inUse"));
        }
        return VIEW;
    }

    private void fill(Model model, TagGroupForm form, boolean showMessage, boolean valid, String message) {
        model.addAttribute("groups", tagGroupService.rows());
        model.addAttribute("form", form);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
    }
}
