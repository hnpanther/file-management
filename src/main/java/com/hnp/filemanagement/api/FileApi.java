package com.hnp.filemanagement.api;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadOutputDTO;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.validation.InsertValidation;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/files")
public class FileApi {

    Logger logger = LoggerFactory.getLogger(FileApi.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileService fileService;


    @Value("${filemanagement.default.element-size:50}")
    private int defaultElementSize;

    public FileApi(GlobalGeneralLogging globalGeneralLogging, FileService fileService) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileService = fileService;
    }


//    API_HEALTH_TEST
    @PreAuthorize("hasAuthority('API_HEALTH_TEST') || hasAuthority('ADMIN')")
    @GetMapping("/health-test")
    public String healthTest() {

        logger.info("request for health test");
        return "hello from endpoint";
    }


//    API_SAVE_NEW_FILE
    @PreAuthorize("hasAuthority('API_SAVE_NEW_FILE') || hasAuthority('ADMIN')")
    @PostMapping
    public ResponseEntity<Object> saveNewFile(@AuthenticationPrincipal UserDetailsImpl userDetails,
                            @ModelAttribute @Validated(InsertValidation.class) FileInfoDTO fileInfoDTO,
                            BindingResult bindingResult,
                            HttpServletRequest request
                            ) {

        int principalId = userDetails.getId();
        String principalUsername = userDetails.getUsername();
        String logMessage = "request to save new file=" + fileInfoDTO;
        String path = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        globalGeneralLogging.controllerLogging(principalId, principalUsername,
                request.getMethod() + " " + path, "FileApi.class", logMessage);

        logger.debug("file details for create new file===============");
        logger.debug("orig name=" +fileInfoDTO.getMultipartFile().getOriginalFilename());
        logger.debug("name=" + fileInfoDTO.getMultipartFile().getName());
        logger.debug("content type=" + fileInfoDTO.getMultipartFile().getContentType());
        logger.debug("size=" + fileInfoDTO.getMultipartFile().getSize());
        logger.debug("===============================================");

        boolean showMessage = true;
        boolean valid = false;
        String message = "";
        FileDetailsDTO fileDetailsDTO = null;
        FileUploadOutputDTO fileUploadOutputDTO = null;

        if(bindingResult.hasErrors()) {
            message = "لطفا اطلاعات را بطور صحیح وارد نمایید";
            globalGeneralLogging.controllerLogging(principalId, principalUsername,
                    request.getMethod() + " " + path, "FileController.class",
                    "ValidationError:" + bindingResult);
        } else {

            try {
                fileDetailsDTO = fileService.createNewFile(fileInfoDTO, principalId);
                fileUploadOutputDTO = ModelConverterUtil.convertFileDetailsDTOToFileUploadOutputDTO(fileDetailsDTO);
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

        if(valid) {
            return new ResponseEntity<>(
                    fileUploadOutputDTO,
                    HttpStatus.OK);
        }

        return new ResponseEntity<>(
                "can not save file: " + message,
                HttpStatus.BAD_REQUEST);

    }



//    @PostMapping("test")
//    public String test1(@AuthenticationPrincipal UserDetailsImpl userDetails,
//                        @ModelAttribute FileInfoDTO fileInfoDTO,
//                        BindingResult bindingResult,
//                        HttpServletRequest request) {
//
//
//        logger.info("request ......======: " + fileInfoDTO);
//        logger.info("file: " + fileInfoDTO.getMultipartFile().getOriginalFilename());
//        return "testpost";
//    }



}
