package com.hnp.filemanagement.util;


import com.hnp.filemanagement.dto.*;
import com.hnp.filemanagement.entity.*;
import java.util.List;
import java.util.stream.Collectors;

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
        roleDTO.setFixed(com.hnp.filemanagement.entity.FixedRole.isFixed(role.getRoleName()));
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
        fileDetailsDTO.setExternalId(fileDetails.getExternalId());
        // The parent is loaded wherever this is called: it is the file being converted, or the one
        // just created.
        fileDetailsDTO.setFileInfoExternalId(fileDetails.getFileInfo().getExternalId());
        fileDetailsDTO.setChecksumSha256(fileDetails.getChecksumSha256());
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

    /** How the folders above a file are joined into one line for a page. */
    public static final String FOLDER_PATH_SEPARATOR = " / ";

    /**
     * Fills the folder fields of a file DTO from the folders above it (outermost first, the
     * file's own folder last), as {@code FolderService.ancestryOf} lists them.
     */
    public static void placeIn(FileInfoDTO fileInfoDTO, List<Folder> ancestry) {
        fileInfoDTO.setFolderPath(ancestry.stream()
                .map(f -> new FolderContentDTO.FolderRef(f.getId(), f.getName(),
                        f.getDisplayName() == null || f.getDisplayName().isBlank() ? f.getName() : f.getDisplayName(),
                        f.getKind().name()))
                .toList());
        fileInfoDTO.setFolderTitle(folderTitleOf(ancestry));
    }

    public static String folderTitleOf(List<Folder> ancestry) {
        return ancestry.stream()
                .map(f -> f.getDisplayName() == null || f.getDisplayName().isBlank() ? f.getName() : f.getDisplayName())
                .collect(Collectors.joining(FOLDER_PATH_SEPARATOR));
    }

    /**
     * @param ancestry the folders above the file, outermost first, the file's own folder last -
     *                 loaded for a whole page at once by {@code FolderService.ancestryOf}, since a
     *                 chain of any depth cannot be fetch-joined
     */
    public static FileInfoDTO convertFileInfoToFileInfoDTO(FileInfo fileInfo, List<Folder> ancestry) {

        FileInfoDTO fileInfoDTO = new FileInfoDTO();
        fileInfoDTO.setId(fileInfo.getId());
        fileInfoDTO.setExternalId(fileInfo.getExternalId());
        fileInfoDTO.setFileName(fileInfo.getFileName());
        fileInfoDTO.setFileNameDescription(fileInfo.getFileNameDescription());
        fileInfoDTO.setDescription(fileInfo.getDescription());
        fileInfoDTO.setFileLink(fileInfo.getFileLink());
        fileInfoDTO.setLastVersion(fileInfo.getLastVersion());
        fileInfoDTO.setFolderId(fileInfo.getFolder().getId());
        placeIn(fileInfoDTO, ancestry);
        fileInfoDTO.setState(fileInfo.getState());
        fileInfoDTO.setEnabled(fileInfo.getEnabled());
        fileInfoDTO.setCreatedAt(fileInfo.getCreatedAt());
        fileInfoDTO.setCreatedBy(fileInfo.getCreatedBy().getUsername());

        fileInfoDTO.setFileDetailsDTOS(fileInfo.getFileDetailsList().stream().map(ModelConverterUtil::covertFileDetailsToFileDetailsDTO).toList());


        return fileInfoDTO;
    }

    public static PublicFileDetailsDTO convertFileDetailsToPublicFileDetailsDTO(FileDetails fileDetails, List<Folder> ancestry) {

        PublicFileDetailsDTO publicFileDetailsDTO = new PublicFileDetailsDTO();
        publicFileDetailsDTO.setId(fileDetails.getId());
        publicFileDetailsDTO.setFileInfoId(fileDetails.getFileInfo().getId());
        publicFileDetailsDTO.setFileName(fileDetails.getFileName());
        publicFileDetailsDTO.setDescription(fileDetails.getDescription());
        publicFileDetailsDTO.setFolderTitle(folderTitleOf(ancestry));
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
        fileUploadOutputDTO.setFileExternalId(fileDetailsDTO.getFileInfoExternalId());
        fileUploadOutputDTO.setFileDetailsExternalId(fileDetailsDTO.getExternalId());
        fileUploadOutputDTO.setChecksumSha256(fileDetailsDTO.getChecksumSha256());
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
