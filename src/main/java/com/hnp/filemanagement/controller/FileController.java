package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.validation.InsertValidation;
import com.hnp.filemanagement.service.FileCategoryService;
import com.hnp.filemanagement.service.FileSubCategoryService;
import com.hnp.filemanagement.service.MainTagFileService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/files")
public class FileController {

    Logger logger = LoggerFactory.getLogger(FileController.class);


    private final GlobalGeneralLogging globalGeneralLogging;

    private final FileCategoryService fileCategoryService;
    private final FileSubCategoryService fileSubCategoryService;
    private final MainTagFileService mainTagFileService;

    private final FileService fileService;

    @Value("${filemanagement.default.page-size:50}")
    private int defaultPageSize;

    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;


    public FileController(GlobalGeneralLogging globalGeneralLogging, FileCategoryService fileCategoryService, FileSubCategoryService fileSubCategoryService, MainTagFileService mainTagFileService, FileService fileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileCategoryService = fileCategoryService;
        this.fileSubCategoryService = fileSubCategoryService;
        this.mainTagFileService = mainTagFileService;
        this.fileService = fileService;
    }

    //CREATE_FILE_PAGE
    @PreAuthorize("hasAuthority('CREATE_FILE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("create")
    public String getCreateFilePage(Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get create file page";
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories(defaultElementSize, 0);

        FileInfoDTO fileInfoDTO = new FileInfoDTO();

        model.addAttribute("file", fileInfoDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", false);
        model.addAttribute("valid", false);
        model.addAttribute("message", "");


        return "file-management/files/save-file.html";
    }

    //SAVE_NEW_FILE
    @PreAuthorize("hasAuthority('SAVE_NEW_FILE') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveNewFile(@ModelAttribute @Validated(InsertValidation.class) FileInfoDTO fileInfoDTO,
                              BindingResult bindingResult,
                              Model model,
                              HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to save new file=" + fileInfoDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        logger.debug("file details for create new file===============");
        logger.debug("orig name=" +fileInfoDTO.getMultipartFile().getOriginalFilename());
        logger.debug("name=" + fileInfoDTO.getMultipartFile().getName());
        logger.debug("content type=" + fileInfoDTO.getMultipartFile().getContentType());
        logger.debug("size=" + fileInfoDTO.getMultipartFile().getSize());
        logger.debug("===============================================");


        boolean showMessage = true;
        boolean valid = false;
        String message = "";


        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileController.class",
                    "ValidationError:" + bindingResult);
        } else {

            try {
                fileService.createNewFile(fileInfoDTO, principalId);
                valid = true;
                message = "اطلاعات با موفقیت ذخیره شد";
            } catch (InvalidDataException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileController.class",
                        "InvalidDataException:" + e.getMessage());
                message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "فایلی با اطلاعات مشابه در سیستم وجود دارد";
            }

        }

        List<FileCategoryDTO> allFileCategories = fileCategoryService.getAllFileCategories(defaultElementSize, 0);
        model.addAttribute("file", fileInfoDTO);
        model.addAttribute("listCategory", allFileCategories);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);

        return "file-management/files/save-file.html";
    }


    //PUBLIC_FILE_PAGE
//    @PreAuthorize("hasAuthority('PUBLIC_FILE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("public-files")
    public String getAllPublicFile(Model model, HttpServletRequest request,
                                   @RequestParam(name = "page-size", required = false) Integer pageSize,
                                   @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                   @RequestParam(name = "search", required = false) String search) {


        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get all public files, pageSize=" + pageSize + ",pageNumber=" + pageNumber + ",search=" + search;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        if(pageSize == null) {
            pageSize = defaultPageSize;
        }
        if(pageNumber == null) {
            pageNumber = 0;
        }

        PublicFileDetailsPageDTO publicFileDetailsPageDTO = fileService.getPagePublicFiles(pageSize, pageNumber, search);


        model.addAttribute("files", publicFileDetailsPageDTO.getPublicFileDetailsDTOList());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", publicFileDetailsPageDTO.getTotalPages());
        model.addAttribute("search", search);

        return "file-management/files/files-public.html";
    }

    //FILE_INFO_PAGE
    @PreAuthorize("hasAuthority('FILE_INFO_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{id}")
    public String getFileInfoPage(@PathVariable("id") int fileInfoId, Model model, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get fileInfo Page with id=" + fileInfoId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        FileInfoDTO fileInfoDTO = fileService.getFileInfoDtoWithFileDetails(fileInfoId);
        model.addAttribute("file", fileInfoDTO);
        return "file-management/files/file-info-page.html";
    }

    //DOWNLOAD_PUBLIC_FILE
//    @PreAuthorize("hasAuthority('DOWNLOAD_PUBLIC_FILE') || hasAuthority('ADMIN')")
    @GetMapping("public-download/{id}")
    public ResponseEntity<?> downloadPublicFile(@PathVariable("id") int fileDetailsId, HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request download public fileDetails with id=" + fileDetailsId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        FileDownloadDTO fileDownloadDTO = fileService.downloadPublicFile(fileDetailsId);
        String contentType = fileDownloadDTO.getContentType();
        String header = "attachment; filename=\"" + fileDownloadDTO.getFileName() + "\"";



        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, header)
                .body(fileDownloadDTO.getResource());
    }


    //GET_ALL_FILE_INFO_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_FILE_INFO_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("file-info")
    public String getAllFileInfo(Model model, HttpServletRequest request,
                                 @RequestParam(name = "page-size", required = false) Integer pageSize,
                                 @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                 @RequestParam(name = "search", required = false) String search) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request to get all file info, pageSize=" + pageSize + ",pageNumber=" + pageNumber + ",search=" + search;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        if(pageSize == null) {
            pageSize = defaultPageSize;
        }
        if(pageNumber == null) {
            pageNumber = 0;
        }

        FileInfoPageDTO fileInfoPageDTO = fileService.getPageFileInfo(pageSize, pageNumber, search);

        model.addAttribute("files", fileInfoPageDTO.getFileInfoDTOList());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", fileInfoPageDTO.getTotalPages());
        model.addAttribute("search", search);
        return "file-management/files/file-info.html";
    }

    //DOWNLOAD_FILE
    @PreAuthorize("hasAuthority('DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    public ResponseEntity<?> downloadFile(@PathVariable("fileInfoId") int fileInfoId,
                                                @PathVariable("fileDetailsId") int fileDetailsId,
                                                HttpServletRequest request) {

        int principalId = 1;
        String principalUsername = "None";
        String logMessage = "request download fileDetails with id=" + fileDetailsId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        FileDownloadDTO fileDownloadDTO = fileService.downloadFile(fileDetailsId);
        String contentType = fileDownloadDTO.getContentType();
        String header = "attachment; filename=\"" + fileDownloadDTO.getFileName() + "\"";



        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, header)
                .body(fileDownloadDTO.getResource());
    }




}
