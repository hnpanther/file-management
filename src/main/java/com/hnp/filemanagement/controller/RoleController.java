package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.RoleService;
import com.hnp.filemanagement.service.UploadPolicyService;
import com.hnp.filemanagement.service.UserService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.util.UiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hnp.filemanagement.config.FileManagementProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The role pages: create, edit and list.
 *
 * <p>The edit form renders every permission in the system with a checkbox, so a submission is the
 * complete set rather than a delta - a permission left unchecked is a removal.
 *
 * <p>This controller is mapped per-method rather than at class level; the paths still all begin
 * with {@code /roles}.
 */
@Controller
public class RoleController {

    private final Logger logger = LoggerFactory.getLogger(RoleController.class);

    private final GlobalGeneralLogging globalGeneralLogging;

    private final RoleService roleService;
    private final UploadPolicyService uploadPolicyService;

    private final UserService userService;

    private final int defaultPageSize;
    private final int defaultElementSize;

    private final UiMessages messages;

    public RoleController(GlobalGeneralLogging globalGeneralLogging, RoleService roleService, UserService userService,
                          UploadPolicyService uploadPolicyService, FileManagementProperties properties,
                          UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.roleService = roleService;
        this.uploadPolicyService = uploadPolicyService;
        this.userService = userService;
        this.defaultPageSize = properties.defaults().pageSize();
        this.defaultElementSize = properties.defaults().elementSize();
        this.messages = messages;
    }


    //CREATE_ROLE_PAGE
    @PreAuthorize("hasAuthority('CREATE_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles/create")
    public String createRolePage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        globalGeneralLogging.detail("create role page");


        RoleDTO roleDTO = new RoleDTO();
        model.addAttribute("role", roleDTO);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        model.addAttribute("pageType", "create");

        return "role/save-role.html";
    }


    //SAVE_NEW_ROLE
    @PreAuthorize("hasAuthority('SAVE_NEW_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles")
    public String saveNewRole(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) RoleDTO roleDTO, BindingResult bindingResult,
                              Model model) {
        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save new role=" + roleDTO);


        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                roleService.createRole(roleDTO.getRoleName(), null, principalId);
                valid = true;
                message = messages.get("form.saved");
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
                message = messages.get("role.duplicate");
            }
        }

        model.addAttribute("role", roleDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("pageType", "create");

        return "role/save-role.html";

    }


    //UPDATE_ROLE_PAGE
    @PreAuthorize("hasAuthority('UPDATE_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles/{roleId}")
    public String updateRolePage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId, Model model) {

        globalGeneralLogging.detail("update role page with id=" + roleId);

        RoleDTO roleDTO = roleService.getRoleDtoById(roleId);
        List<PermissionDTO> permissionDTOList = roleService.getAllPermissionsOfRoleWithSelected(roleId);

        model.addAttribute("role", roleDTO);
        model.addAttribute("permissions", permissionDTOList);
        model.addAttribute("folders", roleService.getFolderTreeForRole(roleId));
        addUploadPolicy(model, roleId);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        model.addAttribute("pageType", "update");

        return "role/save-role.html";
    }

    //SAVE_UPDATED_ROLE
    @PreAuthorize("hasAuthority('SAVE_UPDATED_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("/roles/{roleId}")
    public String saveUpdatedRole(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) RoleDTO roleDTO, BindingResult bindingResult,
                                  Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save updated role=" + roleDTO);
        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                roleService.updatePermissionsOfRole(roleDTO.getId(),
                        roleDTO.getPermissionDTOListId(), principalId);
                // What the role may do, and where - the two halves of the same form.
                roleService.updateFoldersOfRole(roleDTO.getId(), roleDTO.getFolderGrants(), principalId);
                // And what it may upload, when the page showed that section and the editor may set it.
                if (roleDTO.getUploadPolicyMode() != null && maySetUploadPolicy(userDetails)) {
                    uploadPolicyService.saveForRole(roleDTO.getId(),
                            "OWN".equals(roleDTO.getUploadPolicyMode())
                                    ? UploadPolicyService.limitsFrom(roleDTO.getUploadAllowed(), roleDTO.getUploadMax())
                                    : null,
                            principalId);
                }
                valid = true;
                message = messages.get("form.saved");
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.detail("ResourceNotFoundException:" + e.getMessage());
                message = messages.get("form.invalidShort");
            } catch (InvalidDataException e) {
                globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
                message = messages.get("form.invalidShort");
            }
        }

        List<PermissionDTO> permissionDTOList = roleService.getAllPermissionsOfRoleWithSelected(roleDTO.getId());

        model.addAttribute("permissions", permissionDTOList);
        model.addAttribute("folders", roleService.getFolderTreeForRole(roleDTO.getId()));
        addUploadPolicy(model, roleDTO.getId());
        model.addAttribute("role", roleDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("pageType", "update");

        return "role/save-role.html";
    }


    /**
     * The role's upload policy for the edit page: whether it has one of its own, and the rules
     * table filled from it - or from the system-wide policy, as the starting point for one.
     */
    private void addUploadPolicy(Model model, int roleId) {
        java.util.Optional<java.util.Map<String, Long>> own = uploadPolicyService.roleLimits(roleId);
        model.addAttribute("uploadOwn", own.isPresent());
        model.addAttribute("uploadRows", uploadPolicyService.rowsFor(own.orElseGet(uploadPolicyService::globalLimits)));
        model.addAttribute("serverCapMb", uploadPolicyService.serverCapMb());
    }

    private static boolean maySetUploadPolicy(UserDetailsImpl userDetails) {
        return userDetails.getAuthorities().stream()
                .map(Object::toString)
                .anyMatch(a -> a.equals("SAVE_UPLOAD_POLICY") || a.equals("ADMIN"));
    }

    //GET_ALL_ROLE_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles")
    public String viewAllRoles(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        globalGeneralLogging.detail("view all roles");

        List<RoleDTO> roleDTOList = roleService.getAllRoles();

        model.addAttribute("roles", roleDTOList);

        return "role/roles.html";
    }

}
