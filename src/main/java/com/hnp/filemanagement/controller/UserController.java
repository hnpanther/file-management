package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FolderQuotaService;
import com.hnp.filemanagement.service.UserHomeService;
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
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * The user pages: create, edit, profile, password change, role assignment and the paged list.
 *
 * <p>Password changes are a separate form and a separate service call, so an ordinary edit can
 * never blank a password by omitting the field.
 *
 * <p>The profile page hosts the two state toggles - enabled and login type - which it drives over
 * AJAX against {@code UserResource} rather than posting the whole form back.
 */
@Controller
@RequestMapping("/users")
public class UserController {

    private final Logger logger = LoggerFactory.getLogger(UserController.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final UserService userService;
    private final UserHomeService userHomeService;
    private final FolderQuotaService folderQuotaService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;
    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public UserController(GlobalGeneralLogging globalGeneralLogging, UserService userService,
                          UserHomeService userHomeService, FolderQuotaService folderQuotaService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userService = userService;
        this.userHomeService = userHomeService;
        this.folderQuotaService = folderQuotaService;
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
        // The personal folder: ticked by default, the administrator's to untick (roadmap 10.4).
        userDTO.setCreateHome(true);
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
                userService.createUser(userDTO, principalId);
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


        UserDTO userDTO = userService.getUserDtoById(userId);
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


        UserDTO userDTO = userService.getUserDtoById(userId);
        model.addAttribute("user", userDTO);
        model.addAttribute("home", homeOf(userId));
        return "user/user-profile.html";
    }

    /** The user's personal folder as the profile shows it, or null (roadmap 10.4). */
    private UserHomeDTO homeOf(int userId) {
        return userHomeService.homeOf(userId)
                .map(home -> new UserHomeDTO(home.getId(), home.getName(), home.getDisplayName(),
                        folderQuotaService.usageOf(home), home.getQuotaBytes()))
                .orElse(null);
    }

    /**
     * Creates the user's personal folder from their page, for a user made without one - by an
     * administrator's decision, never automatically.
     */
    //CREATE_USER_HOME
    @PreAuthorize("hasAuthority('CREATE_USER_HOME') || hasAuthority('ADMIN')")
    @PostMapping("{userId}/home")
    public String createUserHome(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId,
                                 RedirectAttributes redirectAttributes, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class", "request to create home folder for user id=" + userId);

        try {
            userHomeService.ensureHome(userId, principalId);
            redirectAttributes.addFlashAttribute("homeMessage", "پوشهٔ شخصی ساخته شد");
            redirectAttributes.addFlashAttribute("homeValid", true);
        } catch (InvalidDataException | DuplicateResourceException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserController.class", e.getClass().getSimpleName() + ":" + e.getMessage());
            redirectAttributes.addFlashAttribute("homeMessage", e instanceof DuplicateResourceException
                    ? "پوشه‌ای با نام این کاربر از قبل زیر Profiles وجود دارد"
                    : "نام کاربری نمی‌تواند نام یک پوشه باشد");
            redirectAttributes.addFlashAttribute("homeValid", false);
        }
        return "redirect:/users/" + userId;
    }

    /**
     * Sets or clears the quota of the user's personal folder: a number of megabytes, or blank
     * for none. Lowering it below what is stored is allowed - it stops further uploads.
     */
    //SET_FOLDER_QUOTA
    @PreAuthorize("hasAuthority('SET_FOLDER_QUOTA') || hasAuthority('ADMIN')")
    @PostMapping("{userId}/home/quota")
    public String setUserHomeQuota(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId,
                                   @RequestParam(value = "quotaMb", required = false) String quotaMb,
                                   RedirectAttributes redirectAttributes, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "UserController.class",
                "request to set home quota for user id=" + userId + " to " + quotaMb + " MB");

        Folder home = userHomeService.homeOf(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user id=" + userId + " has no home folder"));
        try {
            Long bytes = parseMegabytes(quotaMb);
            userHomeService.setQuota(home.getId(), bytes, principalId);
            redirectAttributes.addFlashAttribute("homeMessage", "سهمیه ذخیره شد");
            redirectAttributes.addFlashAttribute("homeValid", true);
        } catch (InvalidDataException e) {
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "UserController.class", "InvalidDataException:" + e.getMessage());
            redirectAttributes.addFlashAttribute("homeMessage", "سهمیه باید یک عدد مثبت به مگابایت باشد، یا خالی برای بدون سقف");
            redirectAttributes.addFlashAttribute("homeValid", false);
        }
        return "redirect:/users/" + userId;
    }

    /** Megabytes from the form to bytes, or null for blank; anything else is refused. */
    private static Long parseMegabytes(String quotaMb) {
        if (quotaMb == null || quotaMb.isBlank()) {
            return null;
        }
        try {
            long megabytes = Long.parseLong(quotaMb.trim());
            if (megabytes <= 0) {
                throw new InvalidDataException("quota must be positive: " + quotaMb);
            }
            return Math.multiplyExact(megabytes, 1024L * 1024L);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidDataException("quota is not a number of megabytes: " + quotaMb);
        }
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


        UserDTO userDTO = userService.getUserDtoById(userId);
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
                userService.changePassword(userDTO, principalId);
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
                userService.updateUser(userDTO, principalId);
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


        UserDTO userDTO = userService.getUserDtoById(userId);

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
                userService.updateUserRoles(userId, userRoleDTO.getRolesIds(), principalId);
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


        // One query answers both the rows and the total, so the pager can never disagree with the
        // list it pages - the two used to be separate calls that parsed the search term differently.
        Page<UserDTO> page = userService.getUserPage(search, pageSize, pageNumber);
        List<UserDTO> userDTOS = page.getContent();
        long count = page.getTotalElements();

        model.addAttribute("users", userDTOS);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("currentPageSize", userDTOS.size());
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("search", search);
        model.addAttribute("totalNumber", count);

        return "user/users.html";
    }






}
