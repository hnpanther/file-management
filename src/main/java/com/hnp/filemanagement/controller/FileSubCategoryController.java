package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
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

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public FileSubCategoryController(GlobalGeneralLogging globalGeneralLogging, FileSubCategoryService fileSubCategoryService, FileCategoryService fileCategoryService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileSubCategoryService = fileSubCategoryService;
        this.fileCategoryService = fileCategoryService;
    }

    @GetMapping("create")
    public String createSubCategoryPage(Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
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


    @PostMapping
    public String saveNewSubCategory(@ModelAttribute @Validated(InsertValidation.class) FileSubCategoryDTO fileSubCategoryDTO, BindingResult bindingResult,
                                     Model model, HttpServletRequest request) {


        int principalId = 1;
        String principalUsername = "None";
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


    @GetMapping("{id}")
    public String editSubCategoryPage(@PathVariable("id") int id, Model model, HttpServletRequest request) {
        int principalId = 1;
        String principalUsername = "None";
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

    @PostMapping("{id}")
    public String updateSubCategory(@ModelAttribute @Validated(UpdateValidation.class) FileSubCategoryDTO fileSubCategoryDTO, BindingResult bindingResult,
                                    Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
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


    @GetMapping
    public String viewAllSubCategory(Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request get all file sub category=";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileSubCategoryController.class", logMessage);

        List<FileSubCategoryDTO> fileSubCategoryDTOS = fileSubCategoryService.getAll();

        model.addAttribute("listSubCategory", fileSubCategoryDTOS);


        return "file-management/sub-category/sub-categories.html";

    }



}
