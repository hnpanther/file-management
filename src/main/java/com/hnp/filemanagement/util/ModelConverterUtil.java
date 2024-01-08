package com.hnp.filemanagement.util;


import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.entity.*;

public class ModelConverterUtil {

    public static UserDTO convertUserToUserDTO(User user) {

        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUsername(user.getUsername());
        userDTO.setPersonelCode(user.getPersonelCode());
        userDTO.setNationalCode(user.getNationalCode());
        userDTO.setPhoneNumber(user.getPhoneNumber());
        userDTO.setEmail(user.getEmail());
        userDTO.setPassword("**********");
        userDTO.setFirstName(user.getFirstName());
        userDTO.setLastName(user.getLastName());
        userDTO.setEnabled(user.getEnabled());
        userDTO.setState(user.getState());
        userDTO.setRoleList(
                user.getRoles().stream().map(
                        role -> convertRoleToRoleDTO(role)
                ).toList()
        );

        return userDTO;
    }

    public static RoleDTO convertRoleToRoleDTO(Role role) {

        RoleDTO roleDTO = new RoleDTO();
        roleDTO.setId(role.getId());
        roleDTO.setRoleName(role.getRoleName());
        roleDTO.setSelected(false);
        roleDTO.setPermissionDTOS(
                role.getPermissions().stream().map(
                        ModelConverterUtil::convertPermissionToPermissionDTO
                ).toList()
        );

        return roleDTO;


    }

    public static PermissionDTO convertPermissionToPermissionDTO(Permission permission) {

        PermissionDTO permissionDTO = new PermissionDTO();
        permissionDTO.setId(permission.getId());
        permissionDTO.setPermissionName(permission.getPermissionName());
        permissionDTO.setSelected(false);
        permissionDTO.setDescription(permission.getDescription());

        return permissionDTO;

    }

    public static FileCategoryDTO convertFileCategoryToFileCategoryDTO(FileCategory fileCategory) {

        FileCategoryDTO fileCategoryDTO = new FileCategoryDTO();
        fileCategoryDTO.setId(fileCategory.getId());
        fileCategoryDTO.setCategoryName(fileCategory.getCategoryName());
        fileCategoryDTO.setCategoryNameDescription(fileCategory.getCategoryNameDescription());
        fileCategoryDTO.setDescription(fileCategory.getDescription());
        fileCategoryDTO.setEnabled(fileCategory.getEnabled());
        fileCategoryDTO.setState(fileCategory.getState());
        fileCategoryDTO.setDisplayName(fileCategory.getCategoryNameDescription() + " - " + fileCategory.getGeneralTag().getTagNameDescription());
        fileCategoryDTO.setGeneralTagId(fileCategory.getGeneralTag().getId());

        return fileCategoryDTO;
    }

    public static FileSubCategoryDTO convertFileSubCategoryToFileSubCategoryDTO(FileSubCategory fileSubCategory) {

        FileSubCategoryDTO fileSubCategoryDTO = new FileSubCategoryDTO();
        fileSubCategoryDTO.setId(fileSubCategory.getId());
        fileSubCategoryDTO.setSubCategoryName(fileSubCategory.getSubCategoryName());
        fileSubCategoryDTO.setSubCategoryNameDescription(fileSubCategory.getSubCategoryNameDescription());
        fileSubCategoryDTO.setFileCategoryId(fileSubCategory.getFileCategory().getId());
        fileSubCategoryDTO.setFileCategoryName(fileSubCategory.getFileCategory().getCategoryName());
        fileSubCategoryDTO.setFileCategoryNameDescription(fileSubCategory.getFileCategory().getCategoryNameDescription());
        fileSubCategoryDTO.setDescription(fileSubCategory.getDescription());
        fileSubCategoryDTO.setEnabled(fileSubCategory.getEnabled());
        fileSubCategoryDTO.setState(fileSubCategory.getState());

        return fileSubCategoryDTO;
    }

    public static MainTagFileDTO convertMainTagFileToMainTagFileDTO(MainTagFile mainTagFile) {

        MainTagFileDTO mainTagFileDTO = new MainTagFileDTO();
        mainTagFileDTO.setId(mainTagFile.getId());
        mainTagFileDTO.setTagName(mainTagFile.getTagName());
        mainTagFileDTO.setTagNameDescription(mainTagFile.getTagNameDescription());
        mainTagFileDTO.setDescription(mainTagFile.getDescription());
        mainTagFileDTO.setType(mainTagFile.getType());
        mainTagFileDTO.setFileSubCategoryId(mainTagFile.getFileSubCategory().getId());
        mainTagFileDTO.setFileSubCategoryName(mainTagFile.getFileSubCategory().getSubCategoryName());
        mainTagFileDTO.setFileSubCategoryNameDescription(mainTagFile.getFileSubCategory().getSubCategoryNameDescription());
        mainTagFileDTO.setFileCategoryId(mainTagFile.getFileSubCategory().getFileCategory().getId());
        mainTagFileDTO.setFileCategoryName(mainTagFile.getFileSubCategory().getFileCategory().getCategoryName());
        mainTagFileDTO.setFileCategoryNameDescription(mainTagFile.getFileSubCategory().getFileCategory().getCategoryNameDescription());
        mainTagFileDTO.setEnabled(mainTagFile.getEnabled());
        mainTagFileDTO.setState(mainTagFile.getState());

        return mainTagFileDTO;
    }

    public static FileDetailsDTO covertFileDetailsToFileDetailsDTO(FileDetails fileDetails) {

        FileDetailsDTO fileDetailsDTO = new FileDetailsDTO();
        fileDetailsDTO.setId(fileDetails.getId());
        fileDetailsDTO.setFileName(fileDetails.getFileName());
        fileDetailsDTO.setFileExtension(fileDetails.getFileExtension());
        fileDetailsDTO.setContentType(fileDetails.getContentType());
        fileDetailsDTO.setDescription(fileDetails.getDescription());
        fileDetailsDTO.setFilePath(fileDetails.getFilePath());
        fileDetailsDTO.setFileLink(fileDetails.getFileLink());
        fileDetailsDTO.setFileSize(fileDetails.getFileSize());
        fileDetailsDTO.setVersion(fileDetails.getVersion());
        fileDetailsDTO.setVersionName(fileDetails.getVersionName());
        fileDetailsDTO.setVersionNameDescription(fileDetails.getVersionNameDescription());
        fileDetailsDTO.setEnabled(fileDetails.getEnabled());
        fileDetailsDTO.setState(fileDetails.getState());
        fileDetailsDTO.setCreatedById(fileDetails.getCreatedBy().getId());
        fileDetailsDTO.setCreatedBy(fileDetails.getCreatedBy().getUsername());
        fileDetailsDTO.setFileInfoId(fileDetails.getFileInfo().getId());
        fileDetailsDTO.setCreatedAt(fileDetails.getCreatedAt());

        return fileDetailsDTO;
    }

    public static FileInfoDTO convertFileInfoToFileInfoDTO(FileInfo fileInfo) {

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        fileInfoDTO.setId(fileInfo.getId());
        fileInfoDTO.setFileName(fileInfo.getFileName());
        fileInfoDTO.setDescription(fileInfo.getDescription());
        fileInfoDTO.setFilePath(fileInfo.getFilePath());
        fileInfoDTO.setFileLink(fileInfo.getFileLink());
        fileInfoDTO.setFileSubCategoryId(fileInfo.getFileSubCategory().getId());
        fileInfoDTO.setFileSubCategoryName(fileInfo.getFileSubCategory().getSubCategoryName());
        fileInfoDTO.setFileSubCategoryNameDescription(fileInfo.getFileSubCategory().getSubCategoryNameDescription());
        fileInfoDTO.setFileCategoryId(fileInfo.getFileSubCategory().getFileCategory().getId());
        fileInfoDTO.setFileCategoryName(fileInfo.getFileSubCategory().getFileCategory().getCategoryName());
        fileInfoDTO.setFileCategoryNameDescription(fileInfo.getFileSubCategory().getFileCategory().getCategoryNameDescription());
        fileInfoDTO.setMainTagFileId(fileInfo.getMainTagFile().getId());
        fileInfoDTO.setTagName(fileInfo.getMainTagFile().getTagName());
        fileInfoDTO.setTagDescription(fileInfo.getMainTagFile().getDescription());
        fileInfoDTO.setState(fileInfo.getState());
        fileInfoDTO.setEnabled(fileInfo.getEnabled());
        fileInfoDTO.setCreatedAt(fileInfo.getCreatedAt());
        fileInfoDTO.setCreatedBy(fileInfo.getCreatedBy().getUsername());

        fileInfoDTO.setFileDetailsDTOS(fileInfo.getFileDetailsList().stream().map(ModelConverterUtil::covertFileDetailsToFileDetailsDTO).toList());


        return fileInfoDTO;
    }

    public static PublicFileDetailsDTO convertFileDetailsToPublicFileDetailsDTO(FileDetails fileDetails) {

        PublicFileDetailsDTO publicFileDetailsDTO = new PublicFileDetailsDTO();
        publicFileDetailsDTO.setId(fileDetails.getId());
        publicFileDetailsDTO.setFileInfoId(fileDetails.getFileInfo().getId());
        publicFileDetailsDTO.setFileName(fileDetails.getFileName());
        publicFileDetailsDTO.setDescription(fileDetails.getDescription());
        publicFileDetailsDTO.setCategoryNameDescription(fileDetails.getFileInfo().getMainTagFile().getFileSubCategory().getFileCategory().getCategoryNameDescription());
        publicFileDetailsDTO.setSubCategoryNameDescription(fileDetails.getFileInfo().getMainTagFile().getFileSubCategory().getSubCategoryNameDescription());
        publicFileDetailsDTO.setTagDescription(fileDetails.getFileInfo().getMainTagFile().getDescription());
        publicFileDetailsDTO.setVersion(fileDetails.getVersionName());
        publicFileDetailsDTO.setSize(fileDetails.getFileSize());
        publicFileDetailsDTO.setFileInfoName(fileDetails.getFileInfo().getDescription());

        return publicFileDetailsDTO;
    }

    public static GeneralTagDTO convertGeneralTagToGeneralTagDTO(GeneralTag generalTag) {

        GeneralTagDTO generalTagDTO = new GeneralTagDTO();
        generalTagDTO.setId(generalTag.getId());
        generalTagDTO.setTagName(generalTag.getTagName());
        generalTagDTO.setTagNameDescription(generalTag.getTagNameDescription());
        generalTagDTO.setDescription(generalTag.getDescription());
        generalTagDTO.setType(generalTag.getType());
        generalTagDTO.setEnabled(generalTag.getEnabled());
        generalTagDTO.setState(generalTag.getState());

//        generalTagDTO.setFileCategoryDTOList(generalTag.getFileCategories().stream().map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO).toList());

        return generalTagDTO;


    }




















}
