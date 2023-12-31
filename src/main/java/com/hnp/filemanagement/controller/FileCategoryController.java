package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.InsertValidation;
import com.hnp.filemanagement.dto.UpdateValidation;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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


    public FileCategoryController(GlobalGeneralLogging globalGeneralLogging, FileCategoryService fileCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileCategoryService = fileCategoryService;
    }

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


    @GetMapping("{id}")
    public String updateCagegoryPage(@PathVariable("id") int categoryId, Model model, HttpServletRequest request) {

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

    @PostMapping({"{id}"})
    public String saveUpdatedFileCategory(@PathVariable("id") int categoryId, @ModelAttribute @Validated(UpdateValidation.class) FileCategoryDTO fileCategoryDTO,
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


    @GetMapping
    public String getAllFileCategories(Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get all categories";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileCategoryController.class", logMessage);

        List<FileCategoryDTO> fileCategoryDTOList = fileCategoryService.getAllFileCategories();

        model.addAttribute("fileCategories", fileCategoryDTOList);
        return "file-management/category/categories.html";
    }

}
