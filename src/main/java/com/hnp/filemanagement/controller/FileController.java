package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.service.FileStorageFileSystemService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/files")
public class FileController {

    Logger logger = LoggerFactory.getLogger(FileController.class);


    private final FileStorageFileSystemService fileStorageFileSystemService;

    public FileController(FileStorageFileSystemService fileStorageFileSystemService) {
        this.fileStorageFileSystemService = fileStorageFileSystemService;
    }


    @GetMapping("categories/create")
    public String createCategoryPage(Model model) {

        logger.info("get request to /categories/create");

        model.addAttribute("showMessage", false);
        model.addAttribute("saved", false);
        model.addAttribute("message", "");
        return "file-management/create-category.html";
    }

    @PostMapping("categories")
    public String saveCategory(@ModelAttribute @Valid FileCategoryDTO fileCategoryDTO, BindingResult bindingResult, Model model) {

        logger.info("post request to /files/categories :" + fileCategoryDTO);

        fileStorageFileSystemService.createDirectory(fileCategoryDTO.getCategoryName());
        return "file-management/create-category.html";

    }
}
