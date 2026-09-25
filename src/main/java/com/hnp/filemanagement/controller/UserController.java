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
import com.hnp.filemanagement.util.UiMessages;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdatePasswordValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hnp.filemanagement.config.FileManagementProperties;
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
    private final com.hnp.filemanagement.service.RoleService roleService;

    private final int defaultPageSize;
    private final int defaultElementSize;

    private final UiMessages messages;

    public UserController(GlobalGeneralLogging globalGeneralLogging, UserService userService,
                          UserHomeService userHomeService, FolderQuotaService folderQuotaService,
                          com.hnp.filemanagement.service.RoleService roleService,
                          FileManagementProperties properties,
                          UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.userService = userService;
        this.userHomeService = userHomeService;
        this.folderQuotaService = folderQuotaService;
        this.roleService = roleService;
        this.defaultPageSize = properties.defaults().pageSize();
        this.defaultElementSize = properties.defaults().elementSize();
        this.messages = messages;
    }


    //CREATE_NEW_USER_PAGE
    @PreAuthorize("hasAuthority('CREATE_NEW_USER_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("/create")
    public String createUser(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {
        globalGeneralLogging.detail("create user page");


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
                           Model model) {
        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save new user=" + userDTO);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                userService.createUser(userDTO, principalId);
                valid = true;
                message = messages.get("form.saved");
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.detail("ResourceNotFoundException(probably user role can not found):" + e.getMessage());
                message = messages.get("form.unexpected");

            } catch (DuplicateResourceException e) {
                globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
                message = messages.get("user.duplicate");
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
    public String viewEditUserPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model) {
        globalGeneralLogging.detail("edit user page with id=" + userId);


        UserDTO userDTO = userService.getUserDtoById(userId);
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");
        model.addAttribute("pageType", "update");
        model.addAttribute("mayChangeUsername", roleService.isAdministrator(userDetails.getId()));


        return "user/save-user.html";
    }

    //VIEW_USER_PROFILE
    @PreAuthorize("hasAuthority('VIEW_USER_PROFILE') || hasAuthority('ADMIN') || #userId == authentication.principal.id")
    @GetMapping("{userId}")
    public String viewUserProfile(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model) {
        globalGeneralLogging.detail("user profile page with id=" + userId);


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
                                 RedirectAttributes redirectAttributes) {
        int principalId = userDetails.getId();
        globalGeneralLogging.detail("create home folder for user id=" + userId);

        try {
            userHomeService.ensureHome(userId, principalId);
            redirectAttributes.addFlashAttribute("homeMessage", messages.get("user.home.created"));
            redirectAttributes.addFlashAttribute("homeValid", true);
        } catch (InvalidDataException | DuplicateResourceException e) {
            globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
            redirectAttributes.addFlashAttribute("homeMessage", e instanceof DuplicateResourceException
                    ? messages.get("user.home.nameTaken")
                    : messages.get("user.home.badUsername"));
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
                                   RedirectAttributes redirectAttributes) {
        int principalId = userDetails.getId();
        globalGeneralLogging.detail("set home quota for user id=" + userId + " to " + quotaMb + " MB");

        Folder home = userHomeService.homeOf(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user id=" + userId + " has no home folder"));
        try {
            Long bytes = parseMegabytes(quotaMb);
            userHomeService.setQuota(home.getId(), bytes, principalId);
            redirectAttributes.addFlashAttribute("homeMessage", messages.get("user.home.quotaSaved"));
            redirectAttributes.addFlashAttribute("homeValid", true);
        } catch (InvalidDataException e) {
            globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
            redirectAttributes.addFlashAttribute("homeMessage", messages.get("user.home.quotaInvalid"));
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
    public String changePasswordPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model) {
        globalGeneralLogging.detail("user change password page with id=" + userId);


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
                                     @PathVariable("userId") int userId, Model model) {
        int principalId = userDetails.getId();
        globalGeneralLogging.detail("user change password page with id=" + userId);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors() || !userDTO.getId().equals(userId)) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                userService.changePassword(userDTO, principalId);
                valid = true;
                message = messages.get("form.saved");
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.detail("ResourceNotFoundException(probably user id not correct):" + e.getMessage());
                message = messages.get("form.unexpected");

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
                                  Model model) {
        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save updated user=" + userDTO);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                userService.updateUser(userDTO, principalId);
                valid = true;
                message = messages.get("form.saved");
            } catch (ResourceNotFoundException e) {
                globalGeneralLogging.detail("ResourceNotFoundException(probably user id not correct):" + e.getMessage());
                message = messages.get("form.unexpected");

            } catch (DuplicateResourceException e) {
                globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
                message = e.getMessage().contains("under Profiles")
                        ? messages.get("user.home.renameTaken")
                        : messages.get("user.duplicate");
            } catch (InvalidDataException e) {
                globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
                message = e.getMessageCode().map(code -> messages.get(code, e.getMessageArguments()))
                        .orElseGet(() -> messages.get("form.invalid"));
            }
        }


        userDTO.setPassword("**********");
        model.addAttribute("user", userDTO);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("pageType", "update");
        model.addAttribute("mayChangeUsername", roleService.isAdministrator(principalId));
        return "user/save-user.html";
    }


    //USER_ROLE_PAGE
    @PreAuthorize("hasAuthority('USER_ROLE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{userId}/roles")
    public String viewRoleOfUsers(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("userId") int userId, Model model) {

        globalGeneralLogging.detail("user role page with userId=" + userId);


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
                                       Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save user roles for user with id=" + userId + " user roles=" + userRoleDTO);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = messages.get("form.invalid");
            globalGeneralLogging.detail("ValidationError:" + bindingResult);
        } else {
            try {
                userService.updateUserRoles(userId, userRoleDTO.getRolesIds(), principalId);
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
    public String viewAllUserPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model,
                                  @RequestParam(name = "search", required = false) String search,
                                  @RequestParam(name = "page-size", required = false) Integer pageSize,
                                  @RequestParam(name = "page-number", required = false) Integer pageNumber) {
        globalGeneralLogging.detail("page of all user, search=" + search + ",pageSize=" + pageSize + ",pageNumber=" + pageNumber);


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
