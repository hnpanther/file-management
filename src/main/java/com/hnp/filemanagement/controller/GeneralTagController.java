package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.GeneralTagDTO;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.service.GeneralTagService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.validation.InsertValidation;
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

@Controller
@RequestMapping("/general-tags")
public class GeneralTagController {

    Logger logger = LoggerFactory.getLogger(FileSubCategoryController.class);

    private final GlobalGeneralLogging globalGeneralLogging;

    private final GeneralTagService generalTagService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;


    public GeneralTagController(GlobalGeneralLogging globalGeneralLogging, GeneralTagService generalTagService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.generalTagService = generalTagService;
    }


    // CREATE_GENERAL_TAG_PAGE
    @PreAuthorize("hasAuthority('CREATE_GENERAL_TAG_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("create")
    public String createNewGeneralTag(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                      Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get create GeneralTag page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagController.class", logMessage);

        boolean showMessage = false;
        boolean valid = false;
        String message = "";

        GeneralTagDTO generalTagDTO = new GeneralTagDTO();

        model.addAttribute("generalTag", generalTagDTO);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);


        return "file-management/general-tag/save-general-tag.html";
    }

    // SAVE_NEW_GENERAL_TAG
    @PreAuthorize("hasAuthority('SAVE_NEW_GENERAL_TAG') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveNewGeneralTag(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @ModelAttribute @Validated(InsertValidation.class) GeneralTagDTO generalTagDTO, BindingResult bindingResult,
                                    Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save new GeneralTag=" + generalTagDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagController.class", logMessage);



        boolean showMessage = true;
        boolean valid = true;
        String message = "اطلاعات با موفقیت ذخیره شد";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "GeneralTagController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                generalTagService.createNewGeneralTag(generalTagDTO, principalId);
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "GeneralTagController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            }
        }

        model.addAttribute("generalTag", generalTagDTO);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/general-tag/save-general-tag.html";
    }

    // GET_ALL_GENERAL_TAG_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_GENERAL_TAG_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String getAllGeneralTag(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                   @ModelAttribute @Validated(InsertValidation.class) GeneralTagDTO generalTagDTO, BindingResult bindingResult,
                                   Model model, HttpServletRequest request,
                                   @RequestParam(name = "page-size", required = false) Integer pageSize,
                                   @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                   @RequestParam(name = "search", required = false) String search) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get all general tag page. pageSize=" + pageNumber + ", pageNumber=" + pageNumber + ", search=" + search;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagController.class", logMessage);

        if(pageSize == null) {
            pageSize = defaultPageSize;
        }
        if(pageNumber == null) {
            pageNumber = 0;
        }

        GeneralTagPageDTO generalTagPage = generalTagService.getGeneralTagPage(pageSize, pageNumber, search);

        model.addAttribute("generalTags", generalTagPage.getGeneralTagDTOList());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", generalTagPage.getTotalPages());
        model.addAttribute("search", search);


        return "file-management/general-tag/general-tags.html";
    }


    // UPDATE_GENERAL_TAG_PAGE
    @PreAuthorize("hasAuthority('UPDATE_GENERAL_TAG_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{id}")
    public String updateGeneralTagPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get general tag update page, id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagController.class", logMessage);

        boolean showMessage = false;
        boolean valid = false;
        String message = "";

        GeneralTagDTO generalTagDTO = generalTagService.getGeneralTagDTOByIdOrTagName(id, null);

        model.addAttribute("generalTag", generalTagDTO);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);


        return "file-management/general-tag/save-general-tag.html";





    }

    // SAVE_UPDATED_GENERAL_TAG
    @PreAuthorize("hasAuthority('SAVE_UPDATED_GENERAL_TAG') || hasAuthority('ADMIN')")
    @PostMapping("{id}")
    public String saveUpdatedGeneralTag(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                        @ModelAttribute @Validated(InsertValidation.class) GeneralTagDTO generalTagDTO, BindingResult bindingResult,
                                        Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save updated GeneralTag=" + generalTagDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "GeneralTagController.class", logMessage);



        boolean showMessage = true;
        boolean valid = true;
        String message = "اطلاعات با موفقیت ذخیره شد";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "GeneralTagController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
//                generalTagService.updateDescription(generalTagDTO, principalId);
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "GeneralTagController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            }
        }

        model.addAttribute("generalTag", generalTagDTO);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/general-tag/save-general-tag.html";
    }



}
