package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.InsertValidation;
import com.hnp.filemanagement.dto.UserDTO;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.RoleService;
import com.hnp.filemanagement.service.UserService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/users")
public class UserController {

    private final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final UserService userService;

    public UserController(GlobalGeneralLogging globalGeneralLogging, UserService userService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userService = userService;
    }


    @GetMapping("/create")
    public String createUser(Model model, HttpServletRequest request) {
        int principalId = 0;
        String principalUsername = "None";
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


        return "user/create-user.html";
    }

    @PostMapping
    public String saveUser(@ModelAttribute @Validated(InsertValidation.class) UserDTO userDTO, BindingResult bindingResult,
                           Model model, HttpServletRequest request) {
        int principalId = 0;
        String principalUsername = "None";
        String logMessage = "request create user page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
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

        return "user/create-user.html";

    }

    @GetMapping("{userId}")
    public String viewUserPage(@PathVariable("userId") int userId, Model model, HttpServletRequest request) {
        int principalId = 0;
        String principalUsername = "None";
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


        return "user/create-user.html";
    }

//    @PostMapping("{userId}") {
//
//    }



}
