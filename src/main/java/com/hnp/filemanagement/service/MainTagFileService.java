package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.dto.MainTagFilePageDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.SearchTerms;
import com.hnp.filemanagement.validation.ValidationUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Main tags: the third level of the taxonomy, scoped to one sub-category.
 *
 * <p>A main tag creates no directory today; files carry it as metadata, and the directory a file
 * lands in is decided by its category and sub-category. Phase 5 turns all three levels into real
 * folders, which makes this the level that changes most — the name is validated as a directory name
 * already, in anticipation.
 *
 * <p>Deletion is refused while files still carry the tag. That check used to be hand-written SQL in
 * a {@code MainTagFileDAO} that queried {@code file_Info} with a capital I — which worked only on a
 * case-insensitive MySQL and failed on Linux. It is now a JPQL count, which cannot misspell a table
 * because it does not name one.
 */
@Service
@Transactional(readOnly = true)
public class MainTagFileService {

    private final MainTagFileRepository mainTagFileRepository;
    private final FileInfoRepository fileInfoRepository;
    private final UserRepository userRepository;
    private final FileSubCategoryService fileSubCategoryService;
    private final ActionHistoryService actionHistoryService;
    private final FolderMirrorService folderMirrorService;

    public MainTagFileService(MainTagFileRepository mainTagFileRepository,
                              FileInfoRepository fileInfoRepository,
                              UserRepository userRepository,
                              FileSubCategoryService fileSubCategoryService,
                              ActionHistoryService actionHistoryService,
                              FolderMirrorService folderMirrorService) {
        this.mainTagFileRepository = mainTagFileRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.userRepository = userRepository;
        this.fileSubCategoryService = fileSubCategoryService;
        this.actionHistoryService = actionHistoryService;
        this.folderMirrorService = folderMirrorService;
    }

    @Transactional
    public void createMainTagFile(MainTagFileDTO mainTagFileDTO, int principalId) {

        if (!ValidationUtil.checkCorrectDirectoryName(mainTagFileDTO.getTagName())) {
            throw new BusinessException("tag name not correct=" + mainTagFileDTO.getTagName());
        }

        FileSubCategory fileSubCategory =
                fileSubCategoryService.getFileSubCategoryEntity(mainTagFileDTO.getFileSubCategoryId());

        requireCategoryMatches(fileSubCategory, mainTagFileDTO.getFileCategoryId());

        if (mainTagFileRepository.existsDuplicateInSubCategory(mainTagFileDTO.getTagName(),
                mainTagFileDTO.getDescription(), mainTagFileDTO.getFileSubCategoryId())) {
            throw new DuplicateResourceException("main tag exists=" + mainTagFileDTO);
        }

        MainTagFile mainTagFile = new MainTagFile();
        mainTagFile.setFileSubCategory(fileSubCategory);
        mainTagFile.setType(mainTagFileDTO.getType());
        mainTagFile.setEnabled(1);
        mainTagFile.setState(0);
        mainTagFile.setTagName(mainTagFileDTO.getTagName());
        mainTagFile.setDescription(mainTagFileDTO.getDescription());
        mainTagFile.setTagNameDescription(mainTagFileDTO.getTagNameDescription());
        mainTagFile.setCreatedBy(userRepository.getReferenceById(principalId));

        mainTagFileRepository.save(mainTagFile);

        // Same transaction as the row it mirrors - see FolderMirrorService.
        folderMirrorService.created(mainTagFile);

        actionHistoryService.saveActionHistory(EntityEnum.MainTagFile, mainTagFile.getId(), ActionEnum.CREATE,
                principalId, "CREATE NEW MAIN_TAG_FILE", "CREATE NEW MAIN_TAG_FILE");
    }

    /**
     * Updates the tag's name, description and human-readable name.
     *
     * <p>A tag cannot be moved between sub-categories: files reference the tag and derive their
     * directory from the sub-category, so moving one would leave stored bytes where the metadata no
     * longer points. The previous version had that check written out with its body commented away —
     * an {@code if} whose only statement was a disabled {@code throw} — so a request naming a
     * different sub-category was accepted and silently ignored. It now rejects the request.
     *
     * <p>The two duplicate checks each ask about one field. They used to share one query and pass
     * {@code ""} or {@code null} for the field they were not checking, which made the unused half
     * of the predicate a comparison against a value no row can hold.
     */
    @Transactional
    public void updateMainTagFile(MainTagFileDTO mainTagFileDTO, int principalId) {

        MainTagFile mainTagFile = getMainTagFileEntity(mainTagFileDTO.getId());
        int subCategoryId = mainTagFile.getFileSubCategory().getId();

        if (!Objects.equals(subCategoryId, mainTagFileDTO.getFileSubCategoryId())) {
            throw new InvalidDataException("a main tag cannot be moved between sub categories, id="
                    + mainTagFileDTO.getId());
        }

        if (!mainTagFile.getTagName().equals(mainTagFileDTO.getTagName())) {
            if (!ValidationUtil.checkCorrectDirectoryName(mainTagFileDTO.getTagName())) {
                throw new BusinessException("tag name not correct=" + mainTagFileDTO.getTagName());
            }
            if (mainTagFileRepository.existsByTagNameInSubCategory(mainTagFileDTO.getTagName(), subCategoryId)) {
                throw new DuplicateResourceException("duplicate mainTagFile=" + mainTagFileDTO);
            }
            mainTagFile.setTagName(mainTagFileDTO.getTagName());
        }

        if (!Objects.equals(mainTagFile.getDescription(), mainTagFileDTO.getDescription())) {
            if (mainTagFileRepository.existsByDescriptionInSubCategory(mainTagFileDTO.getDescription(), subCategoryId)) {
                throw new DuplicateResourceException("duplicate mainTagFile=" + mainTagFileDTO);
            }
            mainTagFile.setDescription(mainTagFileDTO.getDescription());
        }

        if (mainTagFileDTO.getTagNameDescription() != null && !mainTagFileDTO.getTagNameDescription().isBlank()) {
            mainTagFile.setTagNameDescription(mainTagFileDTO.getTagNameDescription());
        }

        mainTagFile.setUpdatedBy(userRepository.getReferenceById(principalId));

        // The one level whose directory-safe name can change, so this can move the folder's name.
        folderMirrorService.renamed(mainTagFile);

        actionHistoryService.saveActionHistory(EntityEnum.MainTagFile, mainTagFileDTO.getId(),
                ActionEnum.UPDATE_VALUES, principalId, "UPDATE MAIN_TAG_FILE", "UPDATE MAIN_TAG_FILE");
    }

    @Transactional
    public void deleteMainTagFile(int mainTagFileId, int principalId) {

        MainTagFile mainTagFile = getMainTagFileEntity(mainTagFileId);

        if (fileInfoRepository.countFileWithTagId(mainTagFileId) > 0) {
            throw new DependencyResourceException(
                    "can not delete mainTagFile, check id is correct and not related file to it");
        }

        mainTagFileRepository.delete(mainTagFile);
        folderMirrorService.deletedMainTag(mainTagFileId);

        actionHistoryService.saveActionHistory(EntityEnum.MainTagFile, mainTagFileId, ActionEnum.DELETE, principalId,
                "DELETE MAIN_TAG_FILE", "DELETE MAIN_TAG_FILE");
    }

    public MainTagFileDTO getMainTagFileDtoById(int id) {
        return ModelConverterUtil.convertMainTagFileToMainTagFileDTO(getMainTagFileEntity(id));
    }

    public MainTagFileDTO getMainTagFileDtoByTagName(String tagName) {
        MainTagFile mainTagFile = mainTagFileRepository.findByTagNameWithSubCategory(tagName).orElseThrow(
                () -> new ResourceNotFoundException("MainTagFile with tagName=" + tagName + " not exists")
        );
        return ModelConverterUtil.convertMainTagFileToMainTagFileDTO(mainTagFile);
    }

    public List<MainTagFileDTO> getAllMainTagFile() {
        return mainTagFileRepository.findAllWithSubCategory().stream()
                .map(ModelConverterUtil::convertMainTagFileToMainTagFileDTO)
                .toList();
    }

    public MainTagFilePageDTO getMainTagFilePage(int pageSize, int pageNumber, String search) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<MainTagFile> page = mainTagFileRepository.search(SearchTerms.blankToNull(search), pageable);

        MainTagFilePageDTO pageDTO = new MainTagFilePageDTO();
        pageDTO.setMainTagFileDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertMainTagFileToMainTagFileDTO).toList());
        pageDTO.setTotalPages(page.getTotalPages());
        pageDTO.setPageSize(page.getSize());
        pageDTO.setNumberOfElement(page.getNumberOfElements());
        return pageDTO;
    }

    /** The entity, for the sibling services that build on it. Package-private on purpose. */
    MainTagFile getMainTagFileEntity(int id) {
        return mainTagFileRepository.findByIdWithSubCategory(id).orElseThrow(
                () -> new ResourceNotFoundException("MainTagFile with id=" + id + " not exists")
        );
    }

    private static void requireCategoryMatches(FileSubCategory fileSubCategory, Integer categoryId) {
        if (!Objects.equals(fileSubCategory.getFileCategory().getId(), categoryId)) {
            throw new InvalidDataException("category id not correct");
        }
    }
}
