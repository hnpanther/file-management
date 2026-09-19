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
        userDTO.setLoginType(user.getLoginType());
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

    public static FileDetailsDTO covertFileDetailsToFileDetailsDTO(FileDetails fileDetails) {

        FileDetailsDTO fileDetailsDTO = new FileDetailsDTO();
        fileDetailsDTO.setId(fileDetails.getId());
        fileDetailsDTO.setFileName(fileDetails.getFileName());
        fileDetailsDTO.setFileExtension(fileDetails.getFileExtension());
        fileDetailsDTO.setContentType(fileDetails.getContentType());
        fileDetailsDTO.setDescription(fileDetails.getDescription());
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
        fileInfoDTO.setFileNameDescription(fileInfo.getFileNameDescription());
        fileInfoDTO.setDescription(fileInfo.getDescription());
        fileInfoDTO.setFileLink(fileInfo.getFileLink());
        fileInfoDTO.setLastVersion(fileInfo.getLastVersion());
        // The three folder levels, under the names the pages have always used for them.
        Folder tag = fileInfo.getFolder();
        Folder subCategory = tag.getParent();
        Folder category = subCategory.getParent();
        fileInfoDTO.setFolderId(tag.getId());
        fileInfoDTO.setTagName(tag.getName());
        fileInfoDTO.setTagDescription(tag.getDisplayName());
        fileInfoDTO.setFileSubCategoryName(subCategory.getName());
        fileInfoDTO.setFileSubCategoryNameDescription(subCategory.getDisplayName());
        fileInfoDTO.setFileCategoryName(category.getName());
        fileInfoDTO.setFileCategoryNameDescription(category.getDisplayName());
        fileInfoDTO.setFileCategoryDisplayName(category.getDisplayName()
                + (category.getTagGroup() == null ? "" : "(" + category.getTagGroup().getTitle() + ")"));
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
        Folder tag = fileDetails.getFileInfo().getFolder();
        publicFileDetailsDTO.setCategoryNameDescription(tag.getParent().getParent().getDisplayName());
        publicFileDetailsDTO.setSubCategoryNameDescription(tag.getParent().getDisplayName());
        publicFileDetailsDTO.setTagDescription(tag.getDisplayName());
        publicFileDetailsDTO.setVersion(fileDetails.getVersionName());
        publicFileDetailsDTO.setSize(fileDetails.getFileSize());
        publicFileDetailsDTO.setFileInfoName(fileDetails.getFileInfo().getDescription());

        return publicFileDetailsDTO;
    }

    public static ActionHistoryDTO convertActionHistoryToActionHistoryDTO(ActionHistory actionHistory) {

        ActionHistoryDTO actionHistoryDTO = new ActionHistoryDTO();
        actionHistoryDTO.setId(actionHistory.getId());
        actionHistoryDTO.setEntityName(actionHistory.getEntityName());
        actionHistoryDTO.setTableName(actionHistory.getEntityName().getValue());
        actionHistoryDTO.setEntityId(actionHistory.getEntityId());
        actionHistoryDTO.setAction(actionHistory.getAction());
        actionHistoryDTO.setActionDescription(actionHistory.getActionDescription());
        actionHistoryDTO.setDescription(actionHistory.getDescription());
        actionHistoryDTO.setEnabled(actionHistory.getEnabled());
        actionHistoryDTO.setState(actionHistory.getState());
        actionHistoryDTO.setCreatedAt(actionHistory.getCreatedAt());
        actionHistoryDTO.setUsername(actionHistory.getUser().getUsername());
        actionHistoryDTO.setFullName(actionHistory.getUser().getFirstName() + " " + actionHistory.getUser().getLastName());

        return actionHistoryDTO;
    }

    public static FileUploadOutputDTO convertFileDetailsDTOToFileUploadOutputDTO(FileDetailsDTO fileDetailsDTO) {
        FileUploadOutputDTO fileUploadOutputDTO = new FileUploadOutputDTO();

        fileUploadOutputDTO.setFileId(fileDetailsDTO.getFileInfoId());
        fileUploadOutputDTO.setFileDetailsId(fileDetailsDTO.getId());
        fileUploadOutputDTO.setFileName(fileDetailsDTO.getFileName());
        fileUploadOutputDTO.setFileExtension(fileDetailsDTO.getFileExtension());
        fileUploadOutputDTO.setContentType(fileDetailsDTO.getContentType());
        fileUploadOutputDTO.setDescription(fileDetailsDTO.getDescription());

        return fileUploadOutputDTO;
    }


    public static String getFileNameWithoutExtension(String fileName) {
        return fileName.replaceFirst("[.][^.]+$", "");
    }




















}
