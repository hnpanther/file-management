package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.dto.PermissionDTO;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.RoleService;
import com.hnp.filemanagement.service.UserService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class RoleController {

    private final Logger logger = LoggerFactory.getLogger(RoleController.class);

    private final GlobalGeneralLogging globalGeneralLogging;

    private final RoleService roleService;

    private final UserService userService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;
    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public RoleController(GlobalGeneralLogging globalGeneralLogging, RoleService roleService, UserService userService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.roleService = roleService;
        this.userService = userService;
    }


    //CREATE_ROLE_PAGE
    @PreAuthorize("hasAuthority('CREATE_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles/create")
    public String createRolePage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request create role page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "RoleController.class", logMessage);


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
                              Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request save new role=" + roleDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "RoleController.class", logMessage);


        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "RoleController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                roleService.createRole(roleDTO.getRoleName(), null, principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "RoleController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "نقشی با این مشخصات در سیستم وجود دارد";
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
    public String updateRolePage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("roleId") int roleId, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get update role page with id=" + roleId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "RoleController.class", logMessage);

        RoleDTO roleDTO = roleService.getRoleDtoByIdOrRoleName(roleId, null);
        List<PermissionDTO> permissionDTOList = roleService.getAllPermissionsOfRoleWithSelected(roleId);



        model.addAttribute("role", roleDTO);
        model.addAttribute("permissions", permissionDTOList);
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
                                  Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save updated role=" + roleDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "RoleController.class", logMessage);
        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "RoleController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                roleService.updatePermissionsOfRole(roleDTO.getId(),
                        roleDTO.getPermissionDTOListId(), principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "RoleController.class",
                        "ResourceNotFoundException:" + e.getMessage());
                message = "اطلاعات صحیح نمیباشد";
            } catch (InvalidDataException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "RoleController.class",
                        "InvalidDataException:" + e.getMessage());
                message = "اطلاعات صحیح نمیباشد";
            }
        }

        List<PermissionDTO> permissionDTOList = roleService.getAllPermissionsOfRoleWithSelected(roleDTO.getId());


        model.addAttribute("permissions", permissionDTOList);
        model.addAttribute("role", roleDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("pageType", "update");

        return "role/save-role.html";
    }


    //GET_ALL_ROLE_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/roles")
    public String viewAllRoles(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get view all roles";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "RoleController.class", logMessage);

        List<RoleDTO> roleDTOList = roleService.getAllRoles();

        model.addAttribute("roles", roleDTOList);

        return "role/roles.html";
    }

}
