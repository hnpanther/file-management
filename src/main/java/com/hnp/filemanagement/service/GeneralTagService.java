package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.GeneralTagDTO;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * General tags: labels attached to categories, and nothing more. Alone among the four taxonomy
 * levels a general tag creates no directory, so this service never touches storage — which is what
 * makes it the simplest of the four and a good place to read the shape the others follow.
 *
 * <p>Deleting one is refused while any category still points at it, as a
 * {@code DependencyResourceException} — 409, not 400. The count that decides this asks the database
 * rather than loading the categories, so refusing a delete costs one query regardless of how many
 * categories use the tag.
 */
@Service
@Transactional(readOnly = true)
public class GeneralTagService {

    private final GeneralTagRepository generalTagRepository;
    private final FileCategoryRepository fileCategoryRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;

    public GeneralTagService(GeneralTagRepository generalTagRepository,
                             FileCategoryRepository fileCategoryRepository,
                             UserRepository userRepository,
                             ActionHistoryService actionHistoryService) {
        this.generalTagRepository = generalTagRepository;
        this.fileCategoryRepository = fileCategoryRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
    }

    @Transactional
    public void createNewGeneralTag(GeneralTagDTO generalTagDTO, int principalId) {

        if (generalTagRepository.existsByTagName(generalTagDTO.getTagName())) {
            throw new DuplicateResourceException(
                    "general tag with tagName=" + generalTagDTO.getTagName() + " exists");
        }

        GeneralTag generalTag = new GeneralTag();
        generalTag.setTagName(generalTagDTO.getTagName());
        generalTag.setTagNameDescription(generalTagDTO.getTagNameDescription());
        generalTag.setDescription(generalTagDTO.getDescription());
        generalTag.setType(0);
        generalTag.setEnabled(1);
        generalTag.setState(0);
        generalTag.setCreatedBy(userRepository.getReferenceById(principalId));

        generalTagRepository.save(generalTag);

        actionHistoryService.saveActionHistory(EntityEnum.GeneralTag, generalTag.getId(), ActionEnum.CREATE,
                principalId, "CREATE NEW GENERAL_TAG", "CREATE NEW GENERAL_TAG");
    }

    /**
     * Updates the two free-text fields. Either may be omitted, and an omitted one is left alone
     * rather than blanked — the edit form posts only what it shows.
     *
     * <p>There is no {@code save()} call: the entity was loaded in this transaction, so Hibernate's
     * dirty check writes the change at flush. {@code updatedAt} is written by
     * {@code @UpdateTimestamp} rather than by hand.
     */
    @Transactional
    public void updateDescription(int id, String tagNameDescription, String description, int principalId) {

        GeneralTag generalTag = getGeneralTag(id);

        if (description != null && !description.isEmpty()) {
            generalTag.setDescription(description);
        }
        if (tagNameDescription != null && !tagNameDescription.isEmpty()) {
            generalTag.setTagNameDescription(tagNameDescription);
        }
        generalTag.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.GeneralTag, generalTag.getId(), ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE GENERAL TAG",
                "Update GeneralTag, new tagNameDescription=" + tagNameDescription + ", new description=" + description);
    }

    @Transactional
    public void deleteGeneralTag(int generalTagId, int principalId) {

        GeneralTag generalTag = getGeneralTag(generalTagId);

        if (generalTagRepository.countCategoriesOfGeneralTag(generalTagId) > 0) {
            throw new DependencyResourceException("can not delete general tag with id=" + generalTagId
                    + " , first delete all related FileCategory");
        }

        generalTagRepository.delete(generalTag);

        actionHistoryService.saveActionHistory(EntityEnum.GeneralTag, generalTagId, ActionEnum.DELETE, principalId,
                "DELETE GENERAL TAG", "DELETE GENERAL TAG");
    }

    public GeneralTagDTO getGeneralTagDtoById(int id) {
        return ModelConverterUtil.convertGeneralTagToGeneralTagDTO(getGeneralTag(id));
    }

    public GeneralTagDTO getGeneralTagDtoByTagName(String tagName) {
        GeneralTag generalTag = generalTagRepository.findByTagName(tagName).orElseThrow(
                () -> new ResourceNotFoundException("general tag not found. tagName=" + tagName)
        );
        return ModelConverterUtil.convertGeneralTagToGeneralTagDTO(generalTag);
    }

    /**
     * The categories that use this tag, for the detail page.
     *
     * <p>Queried directly rather than read off {@code generalTag.getFileCategories()}: a collection
     * answers from the persistence context and can be stale within a transaction, and reaching the
     * children through the parent loads the parent for nothing.
     */
    public List<FileCategoryDTO> getFileCategoryOfGeneralTag(int id) {

        if (!generalTagRepository.existsById(id)) {
            throw new ResourceNotFoundException("general tag with id=" + id + " not found");
        }

        return fileCategoryRepository.findByGeneralTagIdOrderByCategoryNameAsc(id).stream()
                .map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO)
                .toList();
    }

    public GeneralTagPageDTO getGeneralTagPage(int pageSize, int pageNumber, String search) {

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<GeneralTag> page = generalTagRepository.search(SearchTerms.blankToNull(search), pageable);

        GeneralTagPageDTO pageDTO = new GeneralTagPageDTO();
        pageDTO.setGeneralTagDTOList(page.getContent().stream()
                .map(ModelConverterUtil::convertGeneralTagToGeneralTagDTO).toList());
        pageDTO.setTotalPages(page.getTotalPages());
        pageDTO.setPageSize(page.getSize());
        pageDTO.setNumberOfElement(page.getNumberOfElements());
        return pageDTO;
    }

    /**
     * The entity, for the sibling services that build on it. Package-private on purpose: an entity
     * that reaches a controller is a lazy graph outside its transaction.
     */
    GeneralTag getGeneralTagEntity(int id) {
        return getGeneralTag(id);
    }

    private GeneralTag getGeneralTag(int id) {
        return generalTagRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("general tag not found. id=" + id)
        );
    }
}
