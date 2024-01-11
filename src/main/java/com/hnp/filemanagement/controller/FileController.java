package com.hnp.filemanagement.controller;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.util.ModelConverterUtil;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public String getCreateFilePage(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
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
    public String saveNewFile(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) FileInfoDTO fileInfoDTO,
                              BindingResult bindingResult,
                              Model model,
                              HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
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
    public String getAllPublicFile(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request,
                                   @RequestParam(name = "page-size", required = false) Integer pageSize,
                                   @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                   @RequestParam(name = "search", required = false) String search) {


        int principalId = 0;
        String principalUsername = "None";
        if(userDetails != null) {
            principalId = userDetails.getId();
            principalUsername = userDetails.getUsername();
        }

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
    public String getFileInfoPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int fileInfoId, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
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
    public ResponseEntity<?> downloadPublicFile(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int fileDetailsId, HttpServletRequest request) {

        int principalId = 0;
        String principalUsername = "None";
        if(userDetails != null) {
            principalId = userDetails.getId();
            principalUsername = userDetails.getUsername();
        }

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
    public String getAllFileInfo(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request,
                                 @RequestParam(name = "page-size", required = false) Integer pageSize,
                                 @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                 @RequestParam(name = "search", required = false) String search) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
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
    public ResponseEntity<?> downloadFile(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId,
                                                @PathVariable("fileDetailsId") int fileDetailsId,
                                                HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
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


    // SAVE_NEW_FILE_DETAILS_PAGE
    @PreAuthorize("hasAuthority('SAVE_NEW_FILE_DETAILS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/file-details/create")
    public String createFileDetailsPage(@PathVariable("fileInfoId") int fileInfoId, @RequestParam(name = "type", required = true) String type,
                                        @RequestParam(name = "id", required = true) Integer fileDetailsId,
                                        @AuthenticationPrincipal UserDetailsImpl userDetails, Model model, HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request get create file details page with type=" + type + ", fileDetailsId=" + fileDetailsId;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        if(type == null || !(type.equals("format") || type.equals("version"))) {
            throw new  InvalidDataException("type not correct, type=" + type);
        }

        boolean showMessage = false;
        boolean valid = false;
        String message = "";

        FileInfoDTO fileInfoDTO = fileService.getFileInfoDtoWithFileDetails(fileInfoId);
        int lastVersion = fileService.getLastVersionOfFile(fileInfoId);
        FileUploadDTO fileUploadDTO = new FileUploadDTO();
        fileUploadDTO.setFileName(fileInfoDTO.getFileName());
        fileUploadDTO.setFileId(fileInfoDTO.getId());
        fileUploadDTO.setVersion(lastVersion);
        fileUploadDTO.setType(type);
        fileUploadDTO.setFileDetailsId(fileDetailsId);


        model.addAttribute("file", fileUploadDTO);
        model.addAttribute("lastVersion", lastVersion);
        model.addAttribute("pageType", type);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);


        return "file-management/files/new-file-details.html";
    }

    //SAVE_NEW_FILE_DETAILS
    @PreAuthorize("hasAuthority('SAVE_NEW_FILE_DETAILS') || hasAuthority('ADMIN')")
    @PostMapping("file-info/{fileInfoId}/file-details")
    public String createNewFileDetails(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) FileUploadDTO fileUploadDTO,
                                       BindingResult bindingResult,
                                       Model model,
                                       HttpServletRequest request) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save new file details, upload=" + fileUploadDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileController.class", logMessage);

        if(fileUploadDTO.getType() == null || !(fileUploadDTO.getType().equals("format") || fileUploadDTO.getType().equals("version"))) {
            throw new  InvalidDataException("type not correct, type=" + fileUploadDTO.getType());
        }

        logger.debug("file details for create new file===============");
        logger.debug("orig name=" +fileUploadDTO.getMultipartFile().getOriginalFilename());
        logger.debug("name=" + fileUploadDTO.getMultipartFile().getName());
        logger.debug("content type=" + fileUploadDTO.getMultipartFile().getContentType());
        logger.debug("size=" + fileUploadDTO.getMultipartFile().getSize());
        logger.debug("===============================================");




        boolean showMessage = true;
        boolean valid = false;
        String message = "اطلاعات با موفقیت ذخیره شد";

        String fileNameWithoutExtension = ModelConverterUtil.getFileNameWithoutExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());
        fileUploadDTO.setFileNameWithoutExtension(fileNameWithoutExtension);
        if(bindingResult.hasErrors() || !fileNameWithoutExtension.equals(fileUploadDTO.getFileName())) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileController.class",
                    "ValidationError:" + bindingResult);
        } else {
            try {
                fileService.createNewFileDetails(fileUploadDTO, principalId);
                valid = true;
            } catch (InvalidDataException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileController.class",
                        "InvalidDataException:" + e.getMessage());
                message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.controllerLogging(principalId, principalUsername,
                        request.getMethod() + " " + path, "FileController.class",
                        "DuplicateResourceException:" + e.getMessage());
                message = "فایل با این مشخصات وجود دارد";
            }

        }

        model.addAttribute("file", fileUploadDTO);
        model.addAttribute("lastVersion", fileUploadDTO.getVersion());
        model.addAttribute("pageType", fileUploadDTO.getType());
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);


        return "file-management/files/new-file-details.html";

    }




}
