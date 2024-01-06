package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileCategoryPageDTO;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/file-categories")
public class FileCategoryController {

    Logger logger = LoggerFactory.getLogger(FileCategoryController.class);

    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileCategoryService fileCategoryService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;


    public FileCategoryController(GlobalGeneralLogging globalGeneralLogging, FileCategoryService fileCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileCategoryService = fileCategoryService;
    }

    // CREATE_FILE_CATEGORY_PAGE
    @GetMapping("create")
    public String getCreateCategoryPage(Model model, HttpServletRequest request) {

        int principalId = 0;
        String principalUsername = "None";
        String logMessage = "request to get create file category page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryController.class", logMessage);


        FileCategoryDTO fileCategoryDTO = new FileCategoryDTO();
        model.addAttribute("fileCategory", fileCategoryDTO);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");

        return "file-management/category/save-category.html";
    }

    // SAVE_NEW_CATEGORY
    @PostMapping
    public String saveNewCategory(@ModelAttribute @Validated(InsertValidation.class) FileCategoryDTO fileCategoryDTO, BindingResult bindingResult,
                                  Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to save new file category=" + fileCategoryDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileCategoryController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                fileCategoryService.createCategory(fileCategoryDTO, principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileCategoryController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            }
        }

        model.addAttribute("fileCategory", fileCategoryDTO);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/category/save-category.html";
    }


    // UPDATE_CATEGORY_PAGE
    @GetMapping("{id}")
    public String updateCategoryPage(@PathVariable("id") int categoryId, Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get file category update page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryController.class", logMessage);

        boolean showMessage = false;
        boolean valid = false;
        String message = "";

        FileCategoryDTO fileCategoryDTO = fileCategoryService.getFileCategoryDtoByIdOrCategoryName(categoryId, null);


        model.addAttribute("fileCategory", fileCategoryDTO);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/category/save-category.html";
    }

    //SAVE_UPDATED_CATEGORY
    @PostMapping({"{id}"})
    public String saveUpdatedFileCategory(@PathVariable("id") int categoryId, @ModelAttribute @Validated(InsertValidation.class) FileCategoryDTO fileCategoryDTO,
                                   BindingResult bindingResult, Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to save updated file category=" + fileCategoryDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileCategoryController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                fileCategoryService.updateCategoryNameDescription(fileCategoryDTO.getId(), fileCategoryDTO.getCategoryNameDescription());
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileCategoryController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            }
        }



        model.addAttribute("fileCategory", fileCategoryDTO);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/category/save-category.html";

    }


    // GET_ALL_FILE_CATEGORY_PAGE
    @GetMapping
    public String getAllFileCategories(Model model, HttpServletRequest request,
                                       @RequestParam(name = "page-size", required = false) Integer pageSize,
                                       @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                       @RequestParam(name = "search", required = false) String search) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get all categories. pageSize=" + pageSize + ",pageNumber=" + pageNumber + ",search=" + search;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryController.class", logMessage);

        if(pageSize == null) {
            pageSize = defaultPageSize;
        }
        if(pageNumber == null) {
            pageNumber = 0;
        }

        FileCategoryPageDTO pageFileCategories = fileCategoryService.getPageFileCategories(pageSize, pageNumber, search);



        model.addAttribute("fileCategories", pageFileCategories.getFileCategoryDTOList());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", pageFileCategories.getTotalPages());
        model.addAttribute("search", search);
        return "file-management/category/categories.html";
    }



}
