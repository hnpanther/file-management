package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.PublicFileDetailsDTO;
import com.hnp.filemanagement.entity.*;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.ValidationUtil;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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


    public FileService(@Value("${file.management.base-dir}") String baseDir, FileStorageService fileStorageService,
                       EntityManager entityManager, FileCategoryService fileCategoryService, FileSubCategoryService fileSubCategoryService,
                       MainTagFileService mainTagFileService, FileInfoRepository fileInfoRepository, FileDetailsRepository fileDetailsRepository) {
        this.baseDir = baseDir;
        this.fileStorageService = fileStorageService;
        this.fileCategoryService = fileCategoryService;
        this.fileSubCategoryService = fileSubCategoryService;
        this.mainTagFileService = mainTagFileService;
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.entityManager = entityManager;
    }


    @Transactional
    public void createNewFile(FileInfoDTO fileInfoDTO, int principalId) {

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
        fileInfo.setDescription(fileInfoDTO.getDescription());
        fileInfo.setFilePath(mainTagFile.getFileSubCategory().getPath() + "/" + fileNameWithoutExtension);
        fileInfo.setEnabled(1);
        fileInfo.setState(0);
        fileInfo.setCreatedAt(LocalDateTime.now());
        fileInfo.setCreatedBy(entityManager.getReference(User.class, principalId));
        fileInfo.setMainTagFile(mainTagFile);
        fileInfo.setFileSubCategory(mainTagFile.getFileSubCategory());

        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName(fileInfoDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setFileExtension(fileExtension);
        fileDetails.setContentType(fileInfoDTO.getMultipartFile().getContentType());
        fileDetails.setDescription(fileInfoDTO.getDescription());
        fileDetails.setFilePath(mainTagFile.getFileSubCategory().getPath() + "/" + fileNameWithoutExtension + "/v1/" + fileInfoDTO.getMultipartFile().getOriginalFilename());
        fileDetails.setFileSize((int) fileInfoDTO.getMultipartFile().getSize());
        fileDetails.setVersion(1);
        fileDetails.setVersionName("V1");
        fileDetails.setEnabled(1);
        fileDetails.setState(0);
        fileDetails.setCreatedAt(LocalDateTime.now());
        fileDetails.setCreatedBy(entityManager.getReference(User.class, principalId));

        fileDetails.setFileInfo(fileInfo);
        fileInfo.getFileDetailsList().add(fileDetails);

        fileInfoRepository.save(fileInfo);

        String address = mainTagFile.getFileSubCategory().getFileCategory().getCategoryName() + "/" + mainTagFile.getFileSubCategory().getSubCategoryName();
        fileStorageService.save(address, fileInfoDTO.getMultipartFile(), 1, fileExtension);

    }

    public void updateFileInfoDescription(int id, String description) {
        FileInfo fileInfo = fileInfoRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("file info with id=" + id + " not exists")
        );
        fileInfo.setDescription(description);
        fileInfoRepository.save(fileInfo);
    }

    @Transactional
    public void deleteCompleteFileById(int id) {

        FileInfo fileInfo = getFileInfoWithFileDetails(id);
        String address = fileInfo.getMainTagFile().getFileSubCategory().getFileCategory().getCategoryName() + "/" +
                fileInfo.getMainTagFile().getFileSubCategory().getSubCategoryName() + "/" + fileInfo.getFileName();

        fileInfoRepository.delete(fileInfo);
        fileStorageService.delete(address, "", 1, "", false);
    }

    public FileDownloadDTO downloadFile(int fileDetailsId) {
        FileDetails fileDetails = fileDetailsRepository.findById(fileDetailsId)
                .orElseThrow(
                        () -> new ResourceNotFoundException("fileDatails with id=" + fileDetailsId + " not exists")
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
        List<FileDetails> fileDetails = fileDetailsRepository.getByState(0, pageable);

        return fileDetails.stream().map(ModelConverterUtil::convertFileDetailsToPublicFileDetailsDTO).toList();

    }



    public boolean isDuplicate(String fileName, int subCategoryId) {
        List<FileInfo> fileInfos = fileInfoRepository.checkExistsFile(fileName, subCategoryId);
        return fileInfos != null && !fileInfos.isEmpty();
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
