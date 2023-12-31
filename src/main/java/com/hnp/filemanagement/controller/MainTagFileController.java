package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.service.MainTagFileService;
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
@RequestMapping("/main-tags")
public class MainTagFileController {

    Logger logger = LoggerFactory.getLogger(MainTagFileController.class);

    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileSubCategoryService fileSubCategoryService;

    private final FileCategoryService fileCategoryService;

    private final MainTagFileService mainTagFileService;

    public MainTagFileController(GlobalGeneralLogging globalGeneralLogging, FileSubCategoryService fileSubCategoryService, FileCategoryService fileCategoryService, MainTagFileService mainTagFileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileSubCategoryService = fileSubCategoryService;
        this.fileCategoryService = fileCategoryService;
        this.mainTagFileService = mainTagFileService;
    }



    @GetMapping("create")
    public String createMainTagFilePage(Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get create main tag file page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "MainTagFileController.class", logMessage);

        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories();

        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setFileCategoryId(0);

        model.addAttribute("mainTag", mainTagFileDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");


        return "file-management/main-tag/save-main-tag.html";
    }


    @PostMapping
    public String saveNewTagFile(@ModelAttribute @Validated(InsertValidation.class) MainTagFileDTO mainTagFileDTO, BindingResult bindingResult,
                                 Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to save new mainTagFile=" + mainTagFileDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "MainTagFileController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";


        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "MainTagFileController.class",
                    "ValidationError:" + bindingResult);
        } else {

            try {
                mainTagFileService.createMainTagFile(mainTagFileDTO, principalId);
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
            } catch (InvalidDataException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "InvalidDataException.class",
                        "BusinessException:" + e.getMessage());
                message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            }
        }


        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories();
        model.addAttribute("mainTag", mainTagFileDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);


        return "file-management/main-tag/save-main-tag.html";
    }


    @GetMapping("{id}")
    public String updateMainTagFilePage(@PathVariable("id") int id, Model model, HttpServletRequest request) {
        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get update main tag file page with id=" + id;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "MainTagFileController.class", logMessage);

        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories();

        MainTagFileDTO mainTagFileDTO = mainTagFileService.getMainTagFileDtoByIdOrTagName(id, null);

        model.addAttribute("mainTag", mainTagFileDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");


        return "file-management/main-tag/save-main-tag.html";
    }

    @PostMapping("{id}")
    public String saveUpdatedMainTagFile(@PathVariable("id") int id, @ModelAttribute @Validated(UpdateValidation.class) MainTagFileDTO mainTagFileDTO, BindingResult bindingResult,
                                         Model model, HttpServletRequest request) {
        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "save updated mainTagFile=" + mainTagFileDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "MainTagFileController.class", logMessage);

        boolean showMessage = true;
        boolean valid = false;
        String message = "";


        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "MainTagFileController.class",
                    "ValidationError:" + bindingResult);
        } else {

            try {
                mainTagFileService.updateMainTagFile(mainTagFileDTO, principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileSubCategoryController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "دسته بندی با این اطلاعات در سیستم موجود میباشد";
            } catch (InvalidDataException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "InvalidDataException.class",
                        "BusinessException:" + e.getMessage());
                message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            }
        }


        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories();
        model.addAttribute("mainTag", mainTagFileDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "update");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);


        return "file-management/main-tag/save-main-tag.html";
    }


    @GetMapping
    public String getAllTags(Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get all tags";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "MainTagFileController.class", logMessage);

        List<MainTagFileDTO> allMainTagFile = mainTagFileService.getAllMainTagFile();
        model.addAttribute("tags", allMainTagFile);
        return "file-management/main-tag/main-tags.html";
    }



}
