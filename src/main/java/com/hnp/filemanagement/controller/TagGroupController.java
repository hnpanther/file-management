package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.TagGroupForm;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.TagGroupService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
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

    public TagGroupController(GlobalGeneralLogging globalGeneralLogging, TagGroupService tagGroupService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.tagGroupService = tagGroupService;
    }

    //TAG_GROUP_PAGE
    @PreAuthorize("hasAuthority('TAG_GROUP_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String tagGroupsPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get tag groups page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "TagGroupController.class", logMessage);

        fill(model, new TagGroupForm(), false, false, "");
        return VIEW;
    }

    /** The same page with the form filled from one group, for editing it. */
    //TAG_GROUP_PAGE
    @PreAuthorize("hasAuthority('TAG_GROUP_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/{id}")
    public String editTagGroupPage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   @PathVariable("id") int id,
                                   Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get tag group edit page, id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "TagGroupController.class", logMessage);

        try {
            fill(model, tagGroupService.formOf(id), false, false, "");
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "TagGroupController.class", "ResourceNotFoundException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, "برچسب عمومی یافت نشد");
        }
        return VIEW;
    }

    //SAVE_TAG_GROUP
    @PreAuthorize("hasAuthority('SAVE_TAG_GROUP') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveTagGroup(@AuthenticationPrincipal UserDetailsImpl userDetails,
                               @ModelAttribute TagGroupForm form,
                               Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save tag group id=" + form.getId() + ", name=" + form.getName();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "TagGroupController.class", logMessage);

        try {
            if (form.getId() == null) {
                tagGroupService.create(form, principalId);
            } else {
                tagGroupService.update(form.getId(), form, principalId);
            }
            fill(model, new TagGroupForm(), true, true, "اطلاعات با موفقیت ذخیره شد");
        } catch (InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "TagGroupController.class", "InvalidDataException:" + e.getMessage());
            fill(model, form, true, false, "لطفا اطلاعات را بطور صحیح وارد نمایید");
        } catch (DuplicateResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "TagGroupController.class", "DuplicateResourceException:" + e.getMessage());
            fill(model, form, true, false, "برچسب عمومی با این نام وجود دارد");
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "TagGroupController.class", "ResourceNotFoundException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, "برچسب عمومی یافت نشد");
        }
        return VIEW;
    }

    //DELETE_TAG_GROUP
    @PreAuthorize("hasAuthority('DELETE_TAG_GROUP') || hasAuthority('ADMIN')")
    @PostMapping("/{id}/delete")
    public String deleteTagGroup(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                 @PathVariable("id") int id,
                                 Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to delete tag group id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "TagGroupController.class", logMessage);

        try {
            tagGroupService.delete(id, principalId);
            fill(model, new TagGroupForm(), true, true, "برچسب عمومی حذف شد");
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "TagGroupController.class", "ResourceNotFoundException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, "برچسب عمومی یافت نشد");
        } catch (DependencyResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "TagGroupController.class", "DependencyResourceException:" + e.getMessage());
            fill(model, new TagGroupForm(), true, false, "این برچسب عمومی در حال استفاده است و حذف نمی‌شود");
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
