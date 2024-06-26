package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.entity.*;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.validation.ValidationUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class FileService {

    private final String baseDir;
    private final Logger logger = LoggerFactory.getLogger(FileService.class);
    private final FileStorageService fileStorageService;

    private final FileCategoryService fileCategoryService;
    private final FileSubCategoryService fileSubCategoryService;
    private final MainTagFileService mainTagFileService;
    private final EntityManager entityManager;
    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final ActionHistoryService actionHistoryService;


    public FileService(@Value("${file.management.base-dir}") String baseDir, FileStorageService fileStorageService,
                       EntityManager entityManager, FileCategoryService fileCategoryService, FileSubCategoryService fileSubCategoryService,
                       MainTagFileService mainTagFileService, FileInfoRepository fileInfoRepository, FileDetailsRepository fileDetailsRepository, ActionHistoryService actionHistoryService) {
        this.baseDir = baseDir;
        this.fileStorageService = fileStorageService;
        this.fileCategoryService = fileCategoryService;
        this.fileSubCategoryService = fileSubCategoryService;
        this.mainTagFileService = mainTagFileService;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.entityManager = entityManager;
        this.actionHistoryService = actionHistoryService;
    }


    @Transactional
    public FileDetailsDTO createNewFile(FileInfoDTO fileInfoDTO, int principalId, int publicFile) {

        if(fileInfoDTO.getMultipartFile().getOriginalFilename() == null) {
            throw new InvalidDataException("file name is null");
        }
        String fileNameWithoutExtension = getFileWithoutExtension(fileInfoDTO.getMultipartFile().getOriginalFilename());
        String fileExtension = getFileExtension(fileInfoDTO.getMultipartFile().getOriginalFilename());

        MainTagFile mainTagFile = mainTagFileService.getMainTagFileByIdOrTagName(fileInfoDTO.getMainTagFileId(), null);

        if(!mainTagFile.getFileSubCategory().getId().equals(fileInfoDTO.getFileSubCategoryId())
                || !mainTagFile.getFileSubCategory().getFileCategory().getId().equals(fileInfoDTO.getFileCategoryId())) {
            throw new InvalidDataException("invalid category and sub category");
        }

        if(isDuplicate(fileNameWithoutExtension, fileInfoDTO.getFileSubCategoryId())) {
            throw new DuplicateResourceException("file with name=" + fileNameWithoutExtension + " exists in sub category with id=" + fileInfoDTO.getFileSubCategoryId());
        }

        if(!ValidationUtil.checkCorrectFileName(fileInfoDTO.getMultipartFile().getOriginalFilename())) {
            throw new InvalidDataException("invalid file name=" + fileInfoDTO.getMultipartFile().getOriginalFilename());
        }

        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileNameWithoutExtension);
        fileInfo.setCodeName(fileNameWithoutExtension);
        fileInfo.setFileNameDescription(fileNameWithoutExtension);
        fileInfo.setDescription(fileInfoDTO.getDescription());
        fileInfo.setDescription(fileInfoDTO.getDescription());
        fileInfo.setFilePath(mainTagFile.getFileSubCategory().getPath() + "/" + fileNameWithoutExtension);
        fileInfo.setRelativePath(mainTagFile.getFileSubCategory().getRelativePath() + "/" + fileNameWithoutExtension);
        fileInfo.setEnabled(1);

        if(publicFile == 1) {
            fileInfo.setState(0);
        } else {
            fileInfo.setState(-1);
        }


        fileInfo.setCreatedAt(LocalDateTime.now());
        fileInfo.setCreatedBy(entityManager.getReference(User.class, principalId));
        fileInfo.setMainTagFile(mainTagFile);
        fileInfo.setFileSubCategory(mainTagFile.getFileSubCategory());

        UUID uuid = UUID. randomUUID();
        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName(fileInfoDTO.getMultipartFile().getOriginalFilename());
//        fileDetails.setHashId(fileInfoDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setHashId(uuid.toString());
        fileDetails.setFileExtension(fileExtension);
        fileDetails.setContentType(fileInfoDTO.getMultipartFile().getContentType());
        fileDetails.setDescription(fileInfoDTO.getDescription());
        fileDetails.setFilePath(mainTagFile.getFileSubCategory().getPath() + "/" + fileNameWithoutExtension + "/v1/" + fileInfoDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setRelativePath(mainTagFile.getFileSubCategory().getRelativePath() + "/" + fileNameWithoutExtension + "/v1/" + fileInfoDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setFileSize((int) fileInfoDTO.getMultipartFile().getSize());
        fileDetails.setVersion(1);
        fileDetails.setVersionName("V1");
        fileDetails.setEnabled(1);
        fileDetails.setState(0);
        fileDetails.setCreatedAt(LocalDateTime.now());
        fileDetails.setCreatedBy(entityManager.getReference(User.class, principalId));

        // do ...
        fileDetails.setDescription(fileInfo.getDescription());

        fileDetails.setFileInfo(fileInfo);
        fileInfo.getFileDetailsList().add(fileDetails);
        fileInfo.setLastVersion(1);

        fileInfoRepository.save(fileInfo);

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, fileInfo.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW FILE_INFO", "CREATE NEW FILE_INFO");
        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetails.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW FILE_DETAILS", "CREATE NEW FILE_DETAILS");


        FileDetailsDTO fileDetailsDTO = ModelConverterUtil.covertFileDetailsToFileDetailsDTO(fileDetails);

        String address = mainTagFile.getFileSubCategory().getFileCategory().getCategoryName() + "/" + mainTagFile.getFileSubCategory().getSubCategoryName();
        fileStorageService.save(address, fileInfoDTO.getMultipartFile(), 1, fileExtension);

        return fileDetailsDTO;

    }


    @Transactional
    public void createNewFileDetails(FileUploadDTO fileUploadDTO, int principalId) {

        FileInfo fileInfo = getFileInfoWithFileDetails(fileUploadDTO.getFileId());

        int version = fileUploadDTO.getVersion();
        String fileExtension = getFileExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());
        String fileNameWithoutExtension = getFileWithoutExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());

        // check correct name
        if(!fileInfo.getFileName().equals(fileUploadDTO.getFileName()) || !fileInfo.getFileName().equals(fileNameWithoutExtension)) {
            throw new InvalidDataException("file name not correct, fileName=" + fileUploadDTO.getFileNameWithoutExtension() + " should be=" + fileInfo.getFileName());
        }

        boolean saveFile = false;

        // check type
        if(fileUploadDTO.getType().equals("format")) {

            if(version > fileInfo.getLastVersion()) {
                throw new InvalidDataException("wrong version for new format, requested version=" +
                        version + ", last version=" + fileInfo.getLastVersion());
            }

            createNewFormatFileDetails(fileUploadDTO, fileInfo, principalId);
            saveFile = true;
        } else  if(fileUploadDTO.getType().equals("version")) {

            if(version != fileInfo.getLastVersion() + 1) {
                throw new InvalidDataException("wrong version for create new version, requested version=" +
                        version + ", last version=" + fileInfo.getLastVersion());
            }

            createNewVersionFileDetails(fileUploadDTO, fileInfo, principalId);
            saveFile = true;
        }

        if(saveFile) {
            String address = fileInfo.getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/" + fileInfo.getMainTagFile().getFileSubCategory().getSubCategoryName();
            fileStorageService.save(address, fileUploadDTO.getMultipartFile(), version, fileExtension);
        }





    }

    private void createNewFormatFileDetails(FileUploadDTO fileUploadDTO, FileInfo fileInfo, int principalId) {

        String fileExtension = getFileExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());
        String fileNameWithoutExtension = getFileWithoutExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());

        FileDetails sampleFileDetails = fileDetailsRepository.findById(fileUploadDTO.getFileDetailsId()).orElseThrow(
                () -> new InvalidDataException("invalid fileDetailsId=" + fileUploadDTO.getFileDetailsId())
        );

        if(!fileNameWithoutExtension.equals(getFileWithoutExtension(sampleFileDetails.getFileName()))) {
            throw new InvalidDataException("invalid fileDetailsId, file name not same");
        }

        if(!sampleFileDetails.getVersion().equals(fileUploadDTO.getVersion())) {
            throw new InvalidDataException("invalid fileDetailsId, version not same");
        }

        //check duplicate format
        if(existsFileDetailsWithSameVersionAndFormat(fileInfo.getId(), fileUploadDTO.getVersion(), fileExtension)) {
            throw new DuplicateResourceException("fileDetails with same version and format exists. version=" + fileUploadDTO.getVersion() + ", format=" + fileExtension);
        }


        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName(fileUploadDTO.getMultipartFile().getOriginalFilename());
        UUID uuid = UUID. randomUUID();
//        fileDetails.setHashId(fileUploadDTO.getMultipartFile().getOriginalFilename() + fileUploadDTO.getVersion());
        fileDetails.setHashId(uuid.toString());
        fileDetails.setFileExtension(fileExtension);
        fileDetails.setContentType(fileUploadDTO.getMultipartFile().getContentType());
        fileDetails.setDescription(fileUploadDTO.getFileDetailsDescription());
        fileDetails.setFilePath(fileInfo.getMainTagFile().getFileSubCategory().getPath() + "/" + fileNameWithoutExtension + "/v" + sampleFileDetails.getVersion() + "/" + fileUploadDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setRelativePath(fileInfo.getFileSubCategory().getRelativePath() + "/" + fileNameWithoutExtension + "/v" + sampleFileDetails.getVersion() + "/" + fileUploadDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setFileSize((int) fileUploadDTO.getMultipartFile().getSize());
        fileDetails.setVersion(sampleFileDetails.getVersion());
        fileDetails.setVersionName(sampleFileDetails.getVersionName());
        fileDetails.setEnabled(1);
        fileDetails.setState(0);
        fileDetails.setCreatedAt(LocalDateTime.now());
        fileDetails.setCreatedBy(entityManager.getReference(User.class, principalId));

        fileDetails.setFileInfo(fileInfo);


        fileDetailsRepository.save(fileDetails);

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetails.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW FILE_DETAILS", "CREATE NEW FILE_DETAILS");

    }

    private void createNewVersionFileDetails(FileUploadDTO fileUploadDTO, FileInfo fileInfo, int principalId) {
        // check duplicate version
        int lastVersion = getLastVersionOfFile(fileInfo.getId());
        if(lastVersion != fileUploadDTO.getVersion() -1) {
            throw new InvalidDataException("version is not correct, current version=" + lastVersion + ", new version=" + fileUploadDTO.getVersion());
        }

        String fileExtension = getFileExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());
        String fileNameWithoutExtension = getFileWithoutExtension(fileUploadDTO.getMultipartFile().getOriginalFilename());

        UUID uuid = UUID. randomUUID();
        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName(fileUploadDTO.getMultipartFile().getOriginalFilename());
//        fileDetails.setHashId(fileUploadDTO.getMultipartFile().getOriginalFilename() + fileUploadDTO.getVersion());
        fileDetails.setHashId(uuid.toString());
        fileDetails.setFileExtension(fileExtension);
        fileDetails.setContentType(fileUploadDTO.getMultipartFile().getContentType());
        fileDetails.setDescription(fileUploadDTO.getFileDetailsDescription());
        fileDetails.setFilePath(fileInfo.getMainTagFile().getFileSubCategory().getPath() + "/" + fileNameWithoutExtension + "/v" + fileUploadDTO.getVersion() + "/" + fileUploadDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setRelativePath(fileInfo.getFileSubCategory().getRelativePath() + "/" + fileNameWithoutExtension + "/v" + fileUploadDTO.getVersion() + "/" + fileUploadDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setFileSize((int) fileUploadDTO.getMultipartFile().getSize());
        fileDetails.setVersion(fileUploadDTO.getVersion());
        fileDetails.setVersionName("V" + fileUploadDTO.getVersion());
        fileDetails.setEnabled(1);
        fileDetails.setState(0);
        fileDetails.setCreatedAt(LocalDateTime.now());
        fileDetails.setCreatedBy(entityManager.getReference(User.class, principalId));

        fileDetails.setFileInfo(fileInfo);

        fileInfo.setLastVersion(fileDetails.getVersion());
        fileInfo.getFileDetailsList().add(fileDetails);
        fileInfoRepository.save(fileInfo);

//        fileDetailsRepository.save(fileDetails);

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetails.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW FILE_DETAILS", "CREATE NEW FILE_DETAILS");


    }

    public void updateFileInfoDescription(int id, String description, int principalId) {

        if(description == null || description.isEmpty()) {
            throw new InvalidDataException("description of file info can not be empty");
        }

        FileInfo fileInfo = fileInfoRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("file info with id=" + id + " not exists")
        );
        fileInfo.setDescription(description);
        fileInfo.setUpdatedAt(LocalDateTime.now());
        fileInfo.setUpdatedBy(entityManager.getReference(User.class, principalId));
        fileInfoRepository.save(fileInfo);

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, id, ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE FILE_INFO", "Update File info, new description=" + description);
    }

    public void changeFileInfoState(int fileInfoId, int newState, int principalId) {

        if(newState != 0 && newState != -1) {
            throw new InvalidDataException("newState not correct");
        }

        FileInfo fileInfo = fileInfoRepository.findById(fileInfoId).orElseThrow(
                () -> new ResourceNotFoundException("file info with id=" + fileInfoId + " not exists")
        );

        int oldState = fileInfo.getState();
        fileInfo.setState(newState);
        fileInfoRepository.save(fileInfo);

        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, fileInfoId, ActionEnum.UPDATE_CHANGE_STATE, principalId,
                "CHANGE STATE FILE_INFO", "Change state from " + oldState + " to " + newState);
    }

    public void changeFileDetailsState(int fileDetailsId, int newState, int principalId) {

        if(newState != 0 && newState != -1) {
            throw new InvalidDataException("newState not correct");
        }

        FileDetails fileDetails = fileDetailsRepository.findById(fileDetailsId).orElseThrow(
                () -> new ResourceNotFoundException("file details with id=" + fileDetailsRepository + " not exists")
        );

        int oldState = fileDetails.getState();
        fileDetails.setState(newState);
        fileDetailsRepository.save(fileDetails);

        actionHistoryService.saveActionHistory(EntityEnum.FileDetails, fileDetailsId, ActionEnum.UPDATE_CHANGE_STATE, principalId,
                "CHANGE STATE FILE_DETAILS", "Change state from " + oldState + " to " + newState);
    }

    // in progress ...
    @Transactional
    public void deleteCompleteFileById(int id, int principalId) {

        FileInfo fileInfo = getFileInfoWithFileDetails(id);
        String address = fileInfo.getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/" +
                fileInfo.getMainTagFile().getFileSubCategory().getSubCategoryName() + "/" + fileInfo.getFileName();

        fileInfoRepository.delete(fileInfo);
        actionHistoryService.saveActionHistory(EntityEnum.FileInfo, id, ActionEnum.DELETE, principalId,
                "DELETE FILE_INFO", "Delete Complete File_Info");
        fileStorageService.delete(address, "", 1, "", false);
    }

    public int getLastVersionOfFile(int fileInfoId) {
        Integer lastVersionNumberOfFile = fileInfoRepository.getLastVersionNumberOfFile(fileInfoId);
        if(lastVersionNumberOfFile == null) {
            throw new ResourceNotFoundException(("file with fileInfoId=" + fileInfoId + " doesn't have version!"));
        }
        return lastVersionNumberOfFile;
    }

    public FileDownloadDTO downloadPublicFile(int fileDetailsId) {
        FileDetails fileDetails = fileDetailsRepository.findPublicFile(fileDetailsId, 0)
                .orElseThrow(
                        () -> new ResourceNotFoundException("public fileDetails with id=" + fileDetailsId + " not exists")
                );

        String address = fileDetails.getFileInfo().getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/"
                + fileDetails.getFileInfo().getMainTagFile().getFileSubCategory().getSubCategoryName();
        Resource resource = fileStorageService.load(address, fileDetails.getFileName(), fileDetails.getVersion(), fileDetails.getFileExtension());

        FileDownloadDTO fileDownloadDTO = new FileDownloadDTO();
        fileDownloadDTO.setResource(resource);
        fileDownloadDTO.setContentType(fileDetails.getContentType());
        fileDownloadDTO.setFileName(fileDetails.getFileName());

        return fileDownloadDTO;

    }

    public FileDownloadDTO downloadFile(int fileDetailsId) {
        FileDetails fileDetails = fileDetailsRepository.findById(fileDetailsId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("fileDetails with id=" + fileDetailsId + " not exists")
                );

        String address = fileDetails.getFileInfo().getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/"
                + fileDetails.getFileInfo().getMainTagFile().getFileSubCategory().getSubCategoryName();
        Resource resource = fileStorageService.load(address, fileDetails.getFileName(), fileDetails.getVersion(), fileDetails.getFileExtension());

        FileDownloadDTO fileDownloadDTO = new FileDownloadDTO();
        fileDownloadDTO.setResource(resource);
        fileDownloadDTO.setContentType(fileDetails.getContentType());
        fileDownloadDTO.setFileName(fileDetails.getFileName());

        return fileDownloadDTO;

    }

    public List<PublicFileDetailsDTO> getAllPublicFileDetails(int pageSize, int pageNumber) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        List<FileDetails> fileDetails = fileDetailsRepository.getAllPublicFileDetails(0, pageable);

        return fileDetails.stream().map(ModelConverterUtil::convertFileDetailsToPublicFileDetailsDTO).toList();

    }

    public List<FileInfoDTO> getAllFileInfo(int pageSize, int pageNumber) {
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        List<FileInfo> fileInfos = fileInfoRepository.findAll(pageable).toList();
        return fileInfos.stream().map(ModelConverterUtil::convertFileInfoToFileInfoDTO).toList();
    }

    @Transactional
    public FileInfoPageDTO getPageFileInfo(int pageSize, int pageNumber, String search) {
        if(search != null) {
            if(search.isEmpty() || search.isBlank()) {
                search = null;
            }
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<FileInfo> page = fileInfoRepository.findByParameterAndPagination(search, pageable);
        FileInfoPageDTO fileInfoPageDTO = new FileInfoPageDTO();
        fileInfoPageDTO.setFileInfoDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileInfoToFileInfoDTO).toList());
        fileInfoPageDTO.setTotalPages(page.getTotalPages());
        fileInfoPageDTO.setPageSize(page.getSize());
        fileInfoPageDTO.setNumberOfElement(page.getNumberOfElements());

        return fileInfoPageDTO;
    }

    @Transactional
    public PublicFileDetailsPageDTO getPagePublicFiles(int pageSize, int pageNumber, String search) {
        if(search != null) {
            if(search.isEmpty() || search.isBlank()) {
                search = null;
            }
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<FileDetails> page = fileDetailsRepository.getAllPublicFileDetailsPage(search, pageable);

        PublicFileDetailsPageDTO publicFileDetailsPageDTO = new PublicFileDetailsPageDTO();
        publicFileDetailsPageDTO.setPublicFileDetailsDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertFileDetailsToPublicFileDetailsDTO).toList());
        publicFileDetailsPageDTO.setTotalPages(page.getTotalPages());
        publicFileDetailsPageDTO.setPageSize(page.getSize());
        publicFileDetailsPageDTO.setNumberOfElement(page.getNumberOfElements());

        return publicFileDetailsPageDTO;

    }


    @Transactional
    public void deleteFileDetails(int fileInfoId, int fileDetailsId, int principalId) {

        Optional<FileDetails> fileDetailsOp = fileDetailsRepository.findById(fileDetailsId);
        if(fileDetailsOp.isEmpty() || fileDetailsOp.get().getFileInfo().getId() != fileInfoId) {
            throw new ResourceNotFoundException("fileDetails with id=" + fileDetailsId + " and fileInfoId=" + fileInfoId + " not exists");
        }

        FileDetails fileDetails = fileDetailsOp.get();
        FileInfo fileInfo = fileDetails.getFileInfo();
        List<FileDetails> fileDetailsList = fileInfo.getFileDetailsList();
        int listSize = fileDetailsList.size();
        String completeName = fileDetails.getFileName();
        int version = fileDetails.getVersion();
        String extension = fileDetails.getFileExtension();

        if(listSize == 1) {
            deleteCompleteFileById(fileInfoId, principalId);


        } else {
            // check same version
            List<FileDetails> list = fileDetailsList.stream()
                    .filter(fd -> fd.getVersion().equals(fileDetails.getVersion())).toList();
            if(list == null || list.size() == 0) {
                throw new BusinessException("list of same version file is empty!");
            }
            if(list.size() == 1) {
                fileInfo.getFileDetailsList().remove(fileDetails);
                fileDetailsRepository.delete(fileDetails);
                String address = fileInfo.getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/" +
                        fileInfo.getMainTagFile().getFileSubCategory().getSubCategoryName() + "/" + fileInfo.getFileName() +
                        "/v" + fileDetails.getVersion();
                fileStorageService.delete(address, "", 1, "", false);
            } else {
                fileInfo.getFileDetailsList().remove(fileDetails);
                fileDetailsRepository.delete(fileDetails);
                String address = fileInfo.getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/" +
                        fileInfo.getMainTagFile().getFileSubCategory().getSubCategoryName();
                fileStorageService.delete(address, completeName, version, extension, true);
            }


        }



    }

    public int countFileWithSameTag(int mainTagFileId) {
        return fileInfoRepository.countFileWithTagId(mainTagFileId);
    }



    public boolean isDuplicate(String fileName, int subCategoryId) {
        List<FileInfo> fileInfos = fileInfoRepository.checkExistsFile(fileName, subCategoryId);
        return fileInfos != null && !fileInfos.isEmpty();
    }

    private boolean checkFileDetailsWithInfoIdExists(int fileInfoId, int fileDetailsId) {
        return fileInfoRepository.checkExistsWithFileDetails(fileInfoId, fileDetailsId).isPresent();
    }

    private boolean existsFileDetailsWithSameVersionAndFormat(int fileInfoId, int version, String format) {
        List<FileDetails> list = fileDetailsRepository.findByFileInfoAndVersionAndFormat(fileInfoId, version, format);
        return list != null && !list.isEmpty();
    }

    public FileInfoDTO getFileInfoDtoWithFileDetails(int id) {
        FileInfo fileInfo = fileInfoRepository.findByIdAndFetchFileDetails(id).orElseThrow(
                () -> new ResourceNotFoundException("file info not exists, id=" + id)
        );

        return ModelConverterUtil.convertFileInfoToFileInfoDTO(fileInfo);
    }

    public FileInfo getFileInfoWithFileDetails(int id) {
        return fileInfoRepository.findByIdAndFetchFileDetails(id).orElseThrow(
                () -> new ResourceNotFoundException("file info not exists, id=" + id)
        );
    }



    public FileInfoDTO getFileInfoDtoWithFileDetails(int subCategoryId, String fileName) {
        FileInfo fileInfo = fileInfoRepository.findByNameAndSubCategoryId(subCategoryId, fileName).orElseThrow(
                () -> new ResourceNotFoundException("file info not exists, subCategoryId=" + subCategoryId + ", fileName=" + fileName)
        );

        return ModelConverterUtil.convertFileInfoToFileInfoDTO(fileInfo);
    }

    private String getFileWithoutExtension(String fileName) {
        return fileName.replaceFirst("[.][^.]+$", "");
    }

    private String getFileExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        return fileName.substring(index+1);
    }

}
