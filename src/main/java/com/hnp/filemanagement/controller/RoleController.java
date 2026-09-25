package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.FileManagementProperties;
import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.entity.FixedRole;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.RoleService;
import com.hnp.filemanagement.service.UploadPolicyService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.util.UiMessages;
import com.hnp.filemanagement.validation.InsertValidation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The role pages: list, create, copy, and the edit page.
 *
 * <p><b>The edit page is three tabs, each its own form and its own request</b>: what the role may
 * do (permissions, {@code /permissions}), where (folder grants, {@code /folders}) and what it may
 * upload ({@code /upload-policy}). Saving one leaves the other two as they are - they used to be
 * one form, so saving a permission change also re-posted every folder grant and the policy. Each
 * post is the complete selection of its tab, so a box left unticked is a removal. Every save
 * answers with a redirect back to the page, on the tab it came from, with its message carried as a
 * flash attribute: reloading the page does not post again.
 *
 * <p>The permission tab puts {@code PermissionGroup}s above the boxes: ticking a group ticks its
 * members, in the browser only - the post is the members, and nothing stores a group.
 *
 * <p>The fixed roles ADMIN and USER ({@link FixedRole}) are shown read-only, and every save refuses
 * them in the service. They can be copied, like any role.
 */
@Controller
public class RoleController {

    /** The edit page's tabs, as {@code ?tab=} names them. */
    static final Set<String> TABS = Set.of("permissions", "folders", "upload");

    private final GlobalGeneralLogging globalGeneralLogging;
    private final RoleService roleService;
    private final UploadPolicyService uploadPolicyService;
    private final UiMessages messages;

    public RoleController(GlobalGeneralLogging globalGeneralLogging, RoleService roleService,
                          UploadPolicyService uploadPolicyService, FileManagementProperties properties,
                          UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.roleService = roleService;
        this.uploadPolicyService = uploadPolicyService;
        this.messages = messages;
    }

    // ------------------------------------------------------------------ list and create

    //GET_ALL_ROLE_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles")
    public String viewAllRoles(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        globalGeneralLogging.detail("view all roles");
        model.addAttribute("roles", roleService.getAllRoles());
        return "role/roles.html";
    }

    //CREATE_ROLE_PAGE
    @PreAuthorize("hasAuthority('CREATE_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles/create")
    public String createRolePage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        globalGeneralLogging.detail("create role page");
        model.addAttribute("role", new RoleDTO());
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        return "role/save-role.html";
    }

    /** Creates an empty role and opens it, so its permissions are the next thing set. */
    //SAVE_NEW_ROLE
    @PreAuthorize("hasAuthority('SAVE_NEW_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles")
    public String saveNewRole(@AuthenticationPrincipal UserDetailsImpl userDetails,
                              @ModelAttribute @Validated(InsertValidation.class) RoleDTO roleDTO, BindingResult bindingResult,
                              Model model, RedirectAttributes redirect) {
        globalGeneralLogging.detail("save new role name=" + roleDTO.getRoleName());

        String message;
        if (bindingResult.hasErrors()) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                int id = roleService.createRole(roleDTO.getRoleName(), null, userDetails.getId());
                flash(redirect, "permissions", true, messages.get("role.created"));
                return "redirect:/roles/" + id + "?tab=permissions";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
                message = messages.get("role.duplicate");
            } catch (InvalidDataException e) {
                globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
                message = reasonOf(e);
            }
        }
        model.addAttribute("role", roleDTO);
        model.addAttribute("showMessage", true);
        model.addAttribute("valid", false);
        model.addAttribute("message", message);
        return "role/save-role.html";
    }

    /**
     * A new role starting as a copy of this one - permissions, folder grants and own upload
     * policy - opened on its permissions tab. Refused with a message on the page it came from when
     * the name is taken or empty.
     */
    //COPY_ROLE
    @PreAuthorize("hasAuthority('COPY_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles/{roleId}/copy")
    public String copyRole(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId,
                           @RequestParam(value = "newRoleName", required = false) String newRoleName,
                           @RequestParam(value = "from", required = false) String from,
                           RedirectAttributes redirect) {
        globalGeneralLogging.detail("copy role id=" + roleId + " as " + newRoleName);
        String back = "list".equals(from) ? "redirect:/roles" : "redirect:/roles/" + roleId;
        try {
            int copyId = roleService.copyRole(roleId, newRoleName, userDetails.getId());
            flash(redirect, "permissions", true, messages.get("role.copied", newRoleName.trim()));
            return "redirect:/roles/" + copyId + "?tab=permissions";
        } catch (DuplicateResourceException e) {
            globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
            redirect.addFlashAttribute("copyMessage", messages.get("role.duplicate"));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            redirect.addFlashAttribute("copyMessage", reasonOf(e));
        }
        return back;
    }

    // ------------------------------------------------------------------ the edit page

    //UPDATE_ROLE_PAGE
    @PreAuthorize("hasAuthority('UPDATE_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles/{roleId}")
    public String updateRolePage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId,
                                 @RequestParam(value = "tab", required = false) String tab, Model model) {
        globalGeneralLogging.detail("update role page with id=" + roleId);

        RoleDTO role = roleService.getRoleDtoById(roleId);
        FixedRole fixed = FixedRole.of(role.getRoleName()).orElse(null);
        model.addAttribute("role", role);
        model.addAttribute("fixedRole", fixed == null ? null : fixed.name());
        model.addAttribute("permissionGroups", roleService.getPermissionGroupsOfRole(roleId));
        model.addAttribute("folders", roleService.getFolderTreeForRole(roleId));
        Optional<Map<String, Long>> own = uploadPolicyService.roleLimits(roleId);
        model.addAttribute("uploadOwn", own.isPresent());
        // ADMIN is above the policy: every catalogued kind, each up to the server's cap.
        model.addAttribute("uploadRows", uploadPolicyService.rowsFor(fixed == FixedRole.ADMIN
                ? uploadPolicyService.administratorLimits()
                : own.orElseGet(uploadPolicyService::globalLimits)));
        model.addAttribute("serverCapMb", uploadPolicyService.serverCapMb());
        // A tab named by a flash (after a save) wins over the address, which is the same tab anyway.
        if (!model.containsAttribute("activeTab")) {
            model.addAttribute("activeTab", tab != null && TABS.contains(tab) ? tab : "permissions");
        }
        return "role/role-edit.html";
    }

    /** The first tab: the complete set of permissions, as ticked. */
    //SAVE_UPDATED_ROLE
    @PreAuthorize("hasAuthority('SAVE_UPDATED_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles/{roleId}/permissions")
    public String savePermissions(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId,
                                  @RequestParam(value = "permissionIds", required = false) List<Integer> permissionIds,
                                  RedirectAttributes redirect) {
        globalGeneralLogging.detail("save permissions of role id=" + roleId + ", count="
                + (permissionIds == null ? 0 : permissionIds.size()));
        return save(roleId, "permissions", redirect,
                () -> roleService.updatePermissionsOfRole(roleId, permissionIds, userDetails.getId()));
    }

    /** The second tab: the complete set of folder grants, each {@code "{folderId}:{READ|WRITE}"}. */
    //SAVE_UPDATED_ROLE
    @PreAuthorize("hasAuthority('SAVE_UPDATED_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles/{roleId}/folders")
    public String saveFolders(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId,
                              @RequestParam(value = "folderGrants", required = false) List<String> folderGrants,
                              RedirectAttributes redirect) {
        globalGeneralLogging.detail("save folder grants of role id=" + roleId);
        return save(roleId, "folders", redirect,
                () -> roleService.updateFoldersOfRole(roleId, folderGrants, userDetails.getId()));
    }

    /**
     * The third tab: {@code GLOBAL} to follow the system-wide policy, {@code OWN} for a policy of
     * the role's own made of the ticked kinds and their limits. Needs the upload policy's own
     * permission as well as the role's, as it always has.
     */
    //SAVE_UPDATED_ROLE and SAVE_UPLOAD_POLICY
    @PreAuthorize("(hasAuthority('SAVE_UPDATED_ROLE') && hasAuthority('SAVE_UPLOAD_POLICY')) || hasAuthority('ADMIN')")
    @PostMapping("/roles/{roleId}/upload-policy")
    public String saveUploadPolicy(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId,
                                   @ModelAttribute RoleDTO form, RedirectAttributes redirect) {
        globalGeneralLogging.detail("save upload policy of role id=" + roleId + ", mode=" + form.getUploadPolicyMode());
        return save(roleId, "upload", redirect, () -> uploadPolicyService.saveForRole(roleId,
                "OWN".equals(form.getUploadPolicyMode())
                        ? UploadPolicyService.limitsFrom(form.getUploadAllowed(), form.getUploadMax())
                        : null,
                userDetails.getId()));
    }

    // ------------------------------------------------------------------ pieces

    /** Runs one tab's save and answers with the page, on that tab, and what happened. */
    private String save(int roleId, String tab, RedirectAttributes redirect, Runnable saving) {
        try {
            saving.run();
            flash(redirect, tab, true, messages.get("form.saved"));
        } catch (ResourceNotFoundException e) {
            globalGeneralLogging.detail("ResourceNotFoundException:" + e.getMessage());
            flash(redirect, tab, false, messages.get("form.invalidShort"));
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            flash(redirect, tab, false, reasonOf(e));
        }
        return "redirect:/roles/" + roleId + "?tab=" + tab;
    }

    private static void flash(RedirectAttributes redirect, String tab, boolean valid, String message) {
        redirect.addFlashAttribute("activeTab", tab);
        redirect.addFlashAttribute("messageTab", tab);
        redirect.addFlashAttribute("valid", valid);
        redirect.addFlashAttribute("message", message);
    }

    /** The refusal's own Persian message when it has one, the short generic one otherwise. */
    private String reasonOf(InvalidDataException e) {
        return e.getMessageCode()
                .map(code -> messages.get(code, e.getMessageArguments()))
                .orElseGet(() -> messages.get("form.invalidShort"));
    }
}
