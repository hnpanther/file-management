package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryPageDTO;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.validation.UpdateValidation;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.service.FileSubCategoryService;
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
@RequestMapping("file-sub-categories")
public class FileSubCategoryController {

    Logger logger = LoggerFactory.getLogger(FileSubCategoryController.class);

    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileSubCategoryService fileSubCategoryService;

    private final FileCategoryService fileCategoryService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public FileSubCategoryController(GlobalGeneralLogging globalGeneralLogging, FileSubCategoryService fileSubCategoryService, FileCategoryService fileCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileSubCategoryService = fileSubCategoryService;
        this.fileCategoryService = fileCategoryService;
    }

    //GET_CREATE_SUB_CATEGORY_PAGE
    @PreAuthorize("hasAuthority('GET_CREATE_SUB_CATEGORY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("create")
    public String createSubCategoryPage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get create file sub category page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryController.class", logMessage);

        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories(defaultElementSize, 0);


        FileSubCategoryDTO fileSubCategoryDTO = new FileSubCategoryDTO();
        fileSubCategoryDTO.setFileCategoryId(0);
        model.addAttribute("fileSubCategory", fileSubCategoryDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");

        return "file-management/sub-category/save-sub-category.html";
    }


    //SAVE_NEW_SUB_CATEGORY
    @PreAuthorize("hasAuthority('SAVE_NEW_SUB_CATEGORY') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveNewSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) FileSubCategoryDTO fileSubCategoryDTO, BindingResult bindingResult,
                                     Model model, HttpServletRequest request) {


        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save new fileSubCategory=" + fileSubCategoryDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";


        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileSubCategoryController.class",
                    "ValidationError:" + bindingResult);
        } else {

            try {
                fileSubCategoryService.createFileSubCategory(fileSubCategoryDTO, principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileSubCategoryController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            } catch (BusinessException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileSubCategoryController.class",
                        "BusinessException:" + e.getMessage());
                message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            }
        }


        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories(defaultElementSize, 0);
        model.addAttribute("fileSubCategory", fileSubCategoryDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/sub-category/save-sub-category.html";
    }


    //GET_EDIT_SUB_CATEGORY_PAGE
    @PreAuthorize("hasAuthority('GET_EDIT_SUB_CATEGORY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("{id}")
    public String editSubCategoryPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int id, Model model, HttpServletRequest request) {
        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to get edit file sub category page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryController.class", logMessage);

        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories(defaultElementSize, 0);


        FileSubCategoryDTO fileSubCategoryDTO = fileSubCategoryService.getFileSubCategoryDtoByIdOrSubCategoryName(id, null);
        model.addAttribute("fileSubCategory", fileSubCategoryDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");

        return "file-management/sub-category/save-sub-category.html";
    }

    //SAVE_UPDATED_SUB_CATEGORY
    @PreAuthorize("hasAuthority('SAVE_UPDATED_SUB_CATEGORY') || hasAuthority('ADMIN')")
    @PostMapping("{id}")
    public String updateSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(UpdateValidation.class) FileSubCategoryDTO fileSubCategoryDTO, BindingResult bindingResult,
                                    Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save updated fileSubCategory=" + fileSubCategoryDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";


        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileSubCategoryController.class",
                    "ValidationError:" + bindingResult);
        } else {

            try {
                fileSubCategoryService.updateFileSubCategory(fileSubCategoryDTO, principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileSubCategoryController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            } catch (BusinessException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileSubCategoryController.class",
                        "BusinessException:" + e.getMessage());
                message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            }
        }


        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories(defaultElementSize, 0);
        model.addAttribute("fileSubCategory", fileSubCategoryDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/sub-category/save-sub-category.html";
    }


    //GET_ALL_SUB_CATEGORY_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_SUB_CATEGORY_PAGE') || hasAuthority('ADMIN')")
    @GetMapping
    public String viewAllSubCategory(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request,
                                     @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                     @RequestParam(name = "page-size",required = false) Integer pageSize,
                                     @RequestParam(name = "search", required = false) String search) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request get all file sub category,pageNumber=" + pageNumber + ",pageSize=" + pageSize + ",search=" + search;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryController.class", logMessage);

        if(pageSize == null) {
            pageSize = defaultPageSize;
        }
        if(pageNumber == null) {
            pageNumber = 0;
        }

        FileSubCategoryPageDTO fileSubCategoryPageDTO = fileSubCategoryService.getPageFileSubCategories(pageSize, pageNumber, search);

        model.addAttribute("listSubCategory", fileSubCategoryPageDTO.getFileSubCategoryDTOList());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", fileSubCategoryPageDTO.getTotalPages());
        model.addAttribute("search", search);


        return "file-management/sub-category/sub-categories.html";

    }



}
