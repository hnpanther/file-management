package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.domain.PublicFileDetailsDTO;
import com.hnp.filemanagement.shared.web.PageResponse;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.folder.domain.QuotaExceededException;
import com.hnp.filemanagement.file.domain.UploadRefusedException;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.shared.web.PageRequests;
import com.hnp.filemanagement.shared.web.UiMessages;
import com.hnp.filemanagement.file.domain.FileNames;
import com.hnp.filemanagement.shared.validation.InsertValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


/**
 * The file pages: upload, the paged list, one file with its versions, the public list, and the two
 * download endpoints.
 *
 * <p>Two downloads exist on purpose. {@code public-download/{id}} serves a file whose own state and
 * whose parent's state are both active, and is reachable without signing in; the other requires a
 * permission and serves any version. They must not be merged - the first is the anonymous path.
 *
 * <p>Uploading a file that already exists is not an error: depending on what matches, the service
 * stores a new format of the current version or a new version. The page cannot tell the two apart
 * and does not need to.
 */
@Controller
@RequestMapping("/files")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    private final GlobalGeneralLogging globalGeneralLogging;
    private final FileService fileService;
    private final UploadPolicyService uploadPolicyService;
    private final UiMessages messages;

    private final int defaultPageSize;

    public FileController(GlobalGeneralLogging globalGeneralLogging, FileService fileService,
                          UploadPolicyService uploadPolicyService, FileManagementProperties properties,
                          UiMessages messages) {
        this.globalGeneralLogging = globalGeneralLogging;
        this.fileService = fileService;
        this.uploadPolicyService = uploadPolicyService;
        this.defaultPageSize = properties.defaults().pageSize();
        this.messages = messages;
    }

    /**
     * What this person may upload, for the form's hint and the file picker's {@code accept}:
     * extension → megabytes, and the same as one {@code .pdf,.png} string.
     */
    private void addUploadLimits(Model model, int principalId) {
        java.util.Map<String, Long> limitsMb = new java.util.LinkedHashMap<>();
        uploadPolicyService.effectiveLimitsFor(principalId)
                .forEach((extension, bytes) -> limitsMb.put(extension, UploadPolicyService.megabytesOf(bytes)));
        model.addAttribute("uploadLimits", limitsMb);
        model.addAttribute("uploadAccept", limitsMb.keySet().stream().map(e -> "." + e)
                .collect(java.util.stream.Collectors.joining(",")));
    }

    /** A quota refusal in the page's language, with the numbers it carries (roadmap 10.4). */
    private String quotaExceededMessage(QuotaExceededException e) {
        return messages.get("upload.refused.quota", e.getFolderName(),
                UploadPolicyService.megabytesOf(e.getQuotaBytes()),
                UploadPolicyService.megabytesOf(e.getUsedBytes()),
                UploadPolicyService.megabytesOf(e.getIncomingBytes()));
    }

    /** One debug line per upload, safe when the request carried no file part at all. */
    private void logUpload(MultipartFile file) {
        logger.debug("upload originalName={}, contentType={}, size={}",
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getContentType(),
                file == null ? 0 : file.getSize());
    }

    /**
     * What a refused upload says to the person who sent it: the policy's answer, the refusal's own
     * message when it has one ({@link InvalidDataException#getMessageCode()}), and the generic
     * sentence only for what a person could not have caused - a hidden field that does not add up.
     */
    private String reasonOf(InvalidDataException e) {
        if (e instanceof UploadRefusedException refused) {
            return uploadRefusedMessage(refused);
        }
        return e.getMessageCode()
                .map(code -> messages.get(code, e.getMessageArguments()))
                .orElseGet(() -> messages.get("form.invalid"));
    }

    /**
     * A form that did not bind, said by field. What is left to fail at binding on the two upload
     * forms is a missing value - the file's kind and content are judged by the service, where the
     * answer can say which kinds this person may upload. A hidden field that is missing is not
     * something the person can fix, and gets the generic sentence.
     */
    private String bindingMessage(BindingResult bindingResult) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String field : bindingResult.getFieldErrors().stream().map(FieldError::getField).distinct().toList()) {
            String label = switch (field) {
                case "multipartFile" -> messages.get("field.chooseFile");
                case "description", "fileDetailsDescription" -> messages.get("field.description");
                case "fileName" -> messages.get("field.fileTitle");
                default -> null;
            };
            if (label == null) {
                return messages.get("form.invalid");
            }
            labels.add(label);
        }
        return labels.isEmpty() ? messages.get("form.invalid") : messages.get("form.fill", String.join(messages.get("form.listSeparator"), labels));
    }

    private String uploadRefusedMessage(UploadRefusedException e) {
        if (e.getReason() == UploadRefusedException.Reason.TOO_LARGE) {
            return messages.get("upload.refused.tooLarge", UploadPolicyService.megabytesOf(e.getSizeBytes()),
                    e.getExtension(), UploadPolicyService.megabytesOf(e.getLimitBytes()));
        }
        return e.getAllowed().isEmpty()
                ? messages.get("upload.refused.nothingAllowed")
                : messages.get("upload.refused.typeNotAllowed", e.getExtension(), String.join(", ", e.getAllowed()));
    }

    /**
     * The upload form. With {@code ?folderId=} - the link the explorer offers on a folder the
     * person may write into - the target is fixed to that folder and shown as a path (roadmap
     * 7.2 step 5). Without it, the form opens its folder chooser at the root, which reads
     * {@code /resource/folders/children} as the person drills down - the same endpoint the
     * explorer reads.
     *
     * <p>A {@code folderId} that cannot be filed into - the root, or outside the person's write
     * grants - falls back to the plain form with a message rather than an error page: the
     * person came here to upload, and the form is where they can still do that.
     */
    //CREATE_FILE_PAGE
    @PreAuthorize("hasAuthority('CREATE_FILE_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("create")
    public String getCreateFilePage(@AuthenticationPrincipal UserDetailsImpl userDetails,
                                    @RequestParam(value = "folderId", required = false) Integer folderId,
                                    Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("create file page, folderId=" + folderId);

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        boolean showMessage = false;
        String message = "";

        if (folderId != null) {
            try {
                fileInfoDTO = fileService.uploadTargetOf(folderId, principalId);
            } catch (InvalidDataException | AccessDeniedException e) {
                globalGeneralLogging.detail(e.getClass().getSimpleName() + ":" + e.getMessage());
                showMessage = true;
                message = messages.get("file.upload.targetUnavailable");
            }
        }

        model.addAttribute("file", fileInfoDTO);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", false);
        model.addAttribute("message", message);
        addUploadLimits(model, principalId);

        return "file-management/files/save-file.html";
    }

    //SAVE_NEW_FILE
    @PreAuthorize("hasAuthority('SAVE_NEW_FILE') || hasAuthority('ADMIN')")
    @PostMapping
    public String saveNewFile(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) FileInfoDTO fileInfoDTO,
                              BindingResult bindingResult,
                              // The form's "public" box: ticked sends 1, left alone sends nothing -
                              // private, as every upload is unless it asks (FileService.visibilityOf).
                              @RequestParam(value = "public-file", required = false) String publicFile,
                              Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save new file name=" + fileInfoDTO.getFileName() + ", folderId=" + fileInfoDTO.getFolderId());
        logUpload(fileInfoDTO.getMultipartFile());

        boolean showMessage = true;
        boolean valid = false;
        String message = "";
        // The file just stored, for the success message's links to its page and its place in the explorer.
        Integer savedFileId = null;

        if(bindingResult.hasErrors()) {
            message = bindingMessage(bindingResult);
            globalGeneralLogging.invalid(bindingResult);
        } else {

            try {
                FileDetailsDTO fileDetailsDTO = fileService.createNewFile(fileInfoDTO, principalId,
                        FileService.visibilityOf(publicFile));
                valid = true;
                savedFileId = fileDetailsDTO.getFileInfoId();
                message = messages.get("form.saved");
            } catch (QuotaExceededException e) {
                globalGeneralLogging.detail("QuotaExceededException:" + e.getMessage());
                message = quotaExceededMessage(e);
            } catch (UploadRefusedException e) {
                globalGeneralLogging.detail("UploadRefusedException:" + e.getMessage());
                message = uploadRefusedMessage(e);
            } catch (InvalidDataException e) {
                globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
                message = reasonOf(e);
            } catch (DuplicateResourceException e) {
                globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
                message = messages.get("file.duplicate");
            }

        }

        // In folder mode the form re-renders with the target still fixed, so its labels are
        // resolved again; a folder that no longer resolves simply drops the form back to the selects.
        if (fileInfoDTO.getFolderId() != null) {
            try {
                FileInfoDTO target = fileService.uploadTargetOf(fileInfoDTO.getFolderId(), principalId);
                fileInfoDTO.setFolderPath(target.getFolderPath());
                fileInfoDTO.setFolderTitle(target.getFolderTitle());
            } catch (InvalidDataException | AccessDeniedException e) {
                fileInfoDTO.setFolderId(null);
            }
        }

        model.addAttribute("file", fileInfoDTO);
        model.addAttribute("pageType", "create");
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        model.addAttribute("savedFileId", savedFileId);
        // A refused upload re-renders the form as it was sent, the box included.
        model.addAttribute("publicFile", FileService.visibilityOf(publicFile) == FileService.PUBLIC);
        addUploadLimits(model, principalId);

        return "file-management/files/save-file.html";
    }


    /**
     * The public file list. No permission: who may see it - everyone, or every signed-in person -
     * is the administrator's switch on {@code /settings/general}, which {@code SecurityConfig}
     * asks through {@code PublicFilesAuthorizationManager}.
     */
    @GetMapping("public-files")
    public String getAllPublicFile(Model model,
                                   @RequestParam(name = "page-size", required = false) Integer pageSize,
                                   @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                   @RequestParam(name = "search", required = false) String search) {

        globalGeneralLogging.detail("all public files, pageSize=" + pageSize + ",pageNumber=" + pageNumber + ",search=" + search);

        pageSize = PageRequests.size(pageSize, defaultPageSize);
        pageNumber = PageRequests.number(pageNumber);

        PageResponse<PublicFileDetailsDTO> files = fileService.getPagePublicFiles(pageSize, pageNumber, search);

        model.addAttribute("files", files.content());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", files.totalPages());
        model.addAttribute("search", search);

        return "file-management/files/files-public.html";
    }

    //FILE_INFO_PAGE
    @PreAuthorize("hasAuthority('FILE_INFO_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{id}")
    public String getFileInfoPage(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("id") int fileInfoId, Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("fileInfo Page with id=" + fileInfoId);

        FileInfoDTO fileInfoDTO = fileService.getFileInfoDtoWithFileDetails(fileInfoId, principalId);
        model.addAttribute("file", fileInfoDTO);
        return "file-management/files/file-info-page.html";
    }

    /** A public file's bytes, under the same switch as the public list above. */
    @GetMapping("public-download/{id}")
    public ResponseEntity<?> downloadPublicFile(@PathVariable("id") int fileDetailsId,
                                                @RequestParam(value = "inline", required = false) String inline) {

        globalGeneralLogging.detail("download public fileDetails with id=" + fileDetailsId);

        FileDownloadDTO fileDownloadDTO = fileService.downloadPublicFile(fileDetailsId);
        return download(fileDownloadDTO, "1".equals(inline));
    }


    //GET_ALL_FILE_INFO_PAGE
    @PreAuthorize("hasAuthority('GET_ALL_FILE_INFO_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("file-info")
    public String getAllFileInfo(@AuthenticationPrincipal UserDetailsImpl userDetails, Model model,
                                 @RequestParam(name = "page-size", required = false) Integer pageSize,
                                 @RequestParam(name = "page-number", required = false) Integer pageNumber,
                                 @RequestParam(name = "search", required = false) String search) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("all file info, pageSize=" + pageSize + ",pageNumber=" + pageNumber + ",search=" + search);

        pageSize = PageRequests.size(pageSize, defaultPageSize);
        pageNumber = PageRequests.number(pageNumber);

        PageResponse<FileInfoDTO> files = fileService.getPageFileInfo(pageSize, pageNumber, search, principalId);

        model.addAttribute("files", files.content());
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("pageNumber", pageNumber + 1);
        model.addAttribute("totalPages", files.totalPages());
        model.addAttribute("search", search);
        return "file-management/files/file-info.html";
    }

    //DOWNLOAD_FILE
    @PreAuthorize("hasAuthority('DOWNLOAD_FILE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/file-details/{fileDetailsId}/download")
    public ResponseEntity<?> downloadFile(@AuthenticationPrincipal UserDetailsImpl userDetails, @PathVariable("fileInfoId") int fileInfoId,
                                                @PathVariable("fileDetailsId") int fileDetailsId,
                                                @RequestParam(value = "inline", required = false) String inline) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("download fileDetails with id=" + fileDetailsId);

        FileDownloadDTO fileDownloadDTO = fileService.downloadFile(fileDetailsId, principalId);
        return download(fileDownloadDTO, "1".equals(inline));
    }

    /**
     * One shape for every download (issue 13). {@code inline} is honoured only for a type the
     * browser renders without running anything - the service decides which - so a stored SVG or
     * anything mislabelled is saved, never executed on this origin. Two headers back that up:
     * {@code nosniff} stops the browser second-guessing the declared type, and a
     * {@code Content-Security-Policy} of {@code default-src 'none'} means that even a document
     * that did contain script would have nowhere to load anything from. (Not {@code sandbox}:
     * Chrome refuses to render a sandboxed PDF inline and downloads it instead, which would undo
     * the preview for the one type it is most used for.)
     */
    private static ResponseEntity<?> download(FileDownloadDTO file, boolean inlineRequested) {
        boolean inline = inlineRequested && file.isInlineSafe();
        String disposition = ContentDispositions.of(inline, file.getFileName());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .body(file.getResource());
    }


    // SAVE_NEW_FILE_DETAILS_PAGE
    @PreAuthorize("hasAuthority('SAVE_NEW_FILE_DETAILS_PAGE') || hasAuthority('ADMIN')")
    @GetMapping("file-info/{fileInfoId}/file-details/create")
    public String createFileDetailsPage(@PathVariable("fileInfoId") int fileInfoId, @RequestParam(name = "type", required = true) String type,
                                        @RequestParam(name = "id", required = true) Integer fileDetailsId,
                                        @RequestParam(name = "version-number", required = true) Integer version,
                                        @AuthenticationPrincipal UserDetailsImpl userDetails, Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("create file details page with type=" + type + ", fileDetailsId=" + fileDetailsId);

        if(type == null || !(type.equals("format") || type.equals("version"))) {
            throw new  InvalidDataException("type not correct, type=" + type);
        }

        boolean showMessage = false;
        boolean valid = false;
        String message = "";

        FileInfoDTO fileInfoDTO = fileService.getFileInfoDtoWithFileDetails(fileInfoId, principalId);
        int lastVersion = fileInfoDTO.getLastVersion();
        FileUploadDTO fileUploadDTO = new FileUploadDTO();
        fileUploadDTO.setFileName(fileInfoDTO.getFileName());
        fileUploadDTO.setFileId(fileInfoDTO.getId());
        if(type.equals("version")) {
            fileUploadDTO.setVersion(lastVersion);
        } else {
            fileUploadDTO.setVersion(version);
        }

        fileUploadDTO.setType(type);
        fileUploadDTO.setFileDetailsId(fileDetailsId);


        model.addAttribute("file", fileUploadDTO);
        model.addAttribute("lastVersion", fileUploadDTO.getVersion());
        model.addAttribute("pageType", type);
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        addUploadLimits(model, principalId);


        return "file-management/files/new-file-details.html";
    }

    //SAVE_NEW_FILE_DETAILS
    @PreAuthorize("hasAuthority('SAVE_NEW_FILE_DETAILS') || hasAuthority('ADMIN')")
    @PostMapping("file-info/{fileInfoId}/file-details")
    public String createNewFileDetails(@AuthenticationPrincipal UserDetailsImpl userDetails, @ModelAttribute @Validated(InsertValidation.class) FileUploadDTO fileUploadDTO,
                                       BindingResult bindingResult,
                                       Model model) {

        int principalId = userDetails.getId();
        globalGeneralLogging.detail("save new file details, fileId=" + fileUploadDTO.getFileId()
                + ", type=" + fileUploadDTO.getType() + ", version=" + fileUploadDTO.getVersion());

        if(fileUploadDTO.getType() == null || !(fileUploadDTO.getType().equals("format") || fileUploadDTO.getType().equals("version"))) {
            throw new  InvalidDataException("type not correct, type=" + fileUploadDTO.getType());
        }

        logUpload(fileUploadDTO.getMultipartFile());

        boolean showMessage = true;
        boolean valid = false;
        String message = messages.get("form.saved");

        // No file part is a binding error (the field is @NotNull), answered below by name.
        MultipartFile uploaded = fileUploadDTO.getMultipartFile();
        String fileNameWithoutExtension = uploaded == null || uploaded.getOriginalFilename() == null
                ? null : FileNames.withoutExtension(uploaded.getOriginalFilename());
        fileUploadDTO.setFileNameWithoutExtension(fileNameWithoutExtension);
        if(bindingResult.hasErrors() || !java.util.Objects.equals(fileNameWithoutExtension, fileUploadDTO.getFileName())) {
            // A new version or format carries the file's own name: say which name, rather than
            // that something somewhere was wrong.
            message = bindingResult.hasErrors()
                    ? bindingMessage(bindingResult)
                    : messages.get("upload.invalid.versionName",
                            fileUploadDTO.getMultipartFile().getOriginalFilename(), fileUploadDTO.getFileName());
            fileUploadDTO.setVersion(fileUploadDTO.getVersion() -1);
            globalGeneralLogging.invalid(bindingResult);
        } else {
            try {
                fileService.createNewFileDetails(fileUploadDTO, principalId);
                valid = true;
            } catch (QuotaExceededException e) {
                fileUploadDTO.setVersion(fileUploadDTO.getVersion() -1);
                globalGeneralLogging.detail("QuotaExceededException:" + e.getMessage());
                message = quotaExceededMessage(e);
            } catch (UploadRefusedException e) {
                fileUploadDTO.setVersion(fileUploadDTO.getVersion() -1);
                globalGeneralLogging.detail("UploadRefusedException:" + e.getMessage());
                message = uploadRefusedMessage(e);
            } catch (InvalidDataException e) {
                fileUploadDTO.setVersion(fileUploadDTO.getVersion() -1);
                globalGeneralLogging.detail("InvalidDataException:" + e.getMessage());
                message = reasonOf(e);
            } catch (DuplicateResourceException e) {
                fileUploadDTO.setVersion(fileUploadDTO.getVersion() -1);
                globalGeneralLogging.detail("DuplicateResourceException:" + e.getMessage());
                message = messages.get("fileDetails.duplicate");
            }

        }

        model.addAttribute("file", fileUploadDTO);
        model.addAttribute("lastVersion", fileUploadDTO.getVersion());
        model.addAttribute("pageType", fileUploadDTO.getType());
        model.addAttribute("showMessage", showMessage);
        model.addAttribute("valid", valid);
        model.addAttribute("message", message);
        addUploadLimits(model, principalId);


        return "file-management/files/new-file-details.html";
    }
}
