package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.UserService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdatePasswordValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping("/users")
public class UserController {

    private final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final UserService userService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;
    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public UserController(GlobalGeneralLogging globalGeneralLogging, UserService userService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userService = userService;
    }


    //CREATE_NEW_USER
    @PreAuthorize("hasAuthority('CREATE_NEW_USER') || hasAuthority('ADMIN')")
    @GetMapping("/create")
    public String createUser(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request create user page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);


        UserDTO userDTO = new UserDTO();
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        model.addAttribute("pageType", "create");


        return "user/save-user.html";
    }

    //SAVE_NEW_USER
    @PreAuthorize("hasAuthority('SAVE_NEW_USER') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveUser(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) UserDTO userDTO, BindingResult bindingResult,
                           Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save new user=" + userDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                userService.createUser(userDTO);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "ResourceNotFoundException(probably user role can not found):" + e.getMessage());
                message = "مشکلی پیش آمده. مجددا تلاش کنید. در صورت تکرار این مشکل با مدیر سیستم تماس بگیرید";

            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "کاربری با این مشخصات در سیستم وجود دارد";
            }
        }


        userDTO.setPassword("**********");
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("pageType", "create");

        return "user/save-user.html";

    }

    //UPDATE_USER_PAGE
    @PreAuthorize("hasAuthority('UPDATE_USER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{userId}/edit")
    public String viewEditUserPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get edit user page with id=" + userId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);


        UserDTO userDTO = userService.getUserDtoByIdOrUsername(userId, null);
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        model.addAttribute("pageType", "update");


        return "user/save-user.html";
    }

    //VIEW_USER_PROFILE
    @PreAuthorize("hasAuthority('VIEW_USER_PROFILE') || hasAuthority('ADMIN') || #userId == authentication.principal.id")
    @GetMapping("{userId}")
    public String viewUserProfile(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get user profile page with id=" + userId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);


        UserDTO userDTO = userService.getUserDtoByIdOrUsername(userId, null);
        model.addAttribute("user", userDTO);
        return "user/user-profile.html";
    }

    //CHANGE_USER_PASSWORD_PAGE
    @PreAuthorize("hasAuthority('CHANGE_USER_PASSWORD_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{userId}/change-password")
    public String changePasswordPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get user change password page with id=" + userId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);


        UserDTO userDTO = userService.getUserDtoByIdOrUsername(userId, null);
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        return "user/user-change-password.html";
    }

    //CHANGE_USER_PASSWORD
    @PreAuthorize("hasAuthority('CHANGE_USER_PASSWORD') || hasAuthority('ADMIN')")
    @PostMapping("{userId}/change-password")
    public String changeUserPassword(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(UpdatePasswordValidation.class) UserDTO userDTO, BindingResult bindingResult,
                                     @PathVariable("userId") int userId, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get user change password page with id=" + userId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors() || !userDTO.getId().equals(userId)) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                userService.changePassword(userDTO);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "ResourceNotFoundException(probably user id not correct):" + e.getMessage());
                message = "مشکلی پیش آمده. مجددا تلاش کنید. در صورت تکرار این مشکل با مدیر سیستم تماس بگیرید";

            }
        }


        userDTO.setPassword("");
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        return "user/user-change-password.html";
    }


    //SAVE_UPDATED_USER
    @PreAuthorize("hasAuthority('SAVE_UPDATED_USER') || hasAuthority('ADMIN')")
    @PostMapping("{userId}")
    public String saveupdatedUser(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(UpdateValidation.class) UserDTO userDTO, BindingResult bindingResult,
                                  Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save updated user=" + userDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                userService.updateUser(userDTO);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "ResourceNotFoundException(probably user id not correct):" + e.getMessage());
                message = "مشکلی پیش آمده. مجددا تلاش کنید. در صورت تکرار این مشکل با مدیر سیستم تماس بگیرید";

            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "کاربری با این مشخصات در سیستم وجود دارد";
            }
        }


        userDTO.setPassword("**********");
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("pageType", "update");
        return "user/save-user.html";
    }


    //USER_ROLE_PAGE
    @PreAuthorize("hasAuthority('USER_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{userId}/roles")
    public String viewRoleOfUsers(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get user role page with userId=" + userId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);


        UserDTO userDTO = userService.getUserDtoByIdOrUsername(userId, null);

        List<RoleDTO> roleDTOList = userService.getAllRoleDtoOfUserWithSelected(userId);
        model.addAttribute("user", userDTO);
        model.addAttribute("roles", roleDTOList);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");

        return "user/user-role.html";
    }

    //SAVE_UPDATED_USER_ROLE
    @PreAuthorize("hasAuthority('SAVE_UPDATED_USER_ROLE') || hasAuthority('ADMIN')")
    @PostMapping("{userId}/roles")
    public String saveUpdatedUserRoles(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, @ModelAttribute @Valid UserRoleDTO userRoleDTO, BindingResult bindingResult,
                                       Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save user roles for user with id=" + userId + " user roles=" + userRoleDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                userService.updateUserRoles(userId, userRoleDTO.getRolesIds());
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "ResourceNotFoundException:" + e.getMessage());
                message = "اطلاعات صحیح نمیباشد";
            } catch (InvalidDataException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "UserController.class",
                        "InvalidDataException:" + e.getMessage());
                message = "اطلاعات صحیح نمیباشد";
            }
        }

        List<RoleDTO> roleDTOList = userService.getAllRoleDtoOfUserWithSelected(userId);
        model.addAttribute("user", userRoleDTO);
        model.addAttribute("roles", roleDTOList);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "user/user-role.html";

    }


    //GET_ALL_USER_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_USER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String viewAllUserPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request,
                                  @RequestParam(name = "search", required = false) String search,
                                  @RequestParam(name = "page-size", required = false) Integer pageSize,
                                  @RequestParam(name = "page-number", required = false) Integer pageNumber) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get page of all user, search=" + search + ",pageSize=" + pageSize + ",pageNumber=" + pageNumber;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);


        if(pageSize == null) {
            pageSize = defaultPageSize;
        }
        if(pageNumber == null) {
            pageNumber = 0;
        }


        List<UserDTO> userDTOS = userService.getAllUserWithSearchPage(search, pageSize, pageNumber);
        int count = userService.countAllUserWithSearchPage(search);

        model.addAttribute("users", userDTOS);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("currentPageSize", userDTOS.size());
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("search", search);
        model.addAttribute("totalNumber", count);

        return "user/users.html";
    }






}
