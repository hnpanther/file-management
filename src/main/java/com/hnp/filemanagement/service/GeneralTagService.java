package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.GeneralTagDTO;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GeneralTagService {

    private final EntityManager entityManager;
    private final GeneralTagRepository generalTagRepository;

    private final ActionHistoryService actionHistoryService;

    public GeneralTagService(EntityManager entityManager, GeneralTagRepository generalTagRepository, ActionHistoryService actionHistoryService) {
        this.entityManager = entityManager;
        this.generalTagRepository = generalTagRepository;
        this.actionHistoryService = actionHistoryService;
    }

    @Transactional
    public GeneralTagDTO getGeneralTagDTOByIdOrTagName(int id, String tagName) {
        GeneralTag generalTag = generalTagRepository.findByIdOrTagName(id, tagName).orElseThrow(
                () -> new ResourceNotFoundException("general tag not found. id=" + id + ", tagName=" + tagName)
        );
        return ModelConverterUtil.convertGeneralTagToGeneralTagDTO(generalTag);
    }


    public GeneralTag getGeneralTagByIdOrTagName(int id, String tagName) {

        return generalTagRepository.findByIdOrTagName(id, tagName).orElseThrow(
                () -> new ResourceNotFoundException("general tag not found. id=" + id + ", tagName=" + tagName)
        );
    }

    @Transactional
    public void createNewGeneralTag(GeneralTagDTO generalTagDTO, int principalId) {

        boolean existsByTagName = generalTagRepository.existsByTagName(generalTagDTO.getTagName());
        if(existsByTagName) {
            throw new DuplicateResourceException("general tag with tagName=" + generalTagDTO.getTagNameDescription() + " exists");
        }

        GeneralTag generalTag = new GeneralTag();
        generalTag.setTagName(generalTagDTO.getTagName());
        generalTag.setTagNameDescription(generalTagDTO.getTagNameDescription());
        generalTag.setDescription(generalTagDTO.getDescription());
        generalTag.setType(0);
        generalTag.setEnabled(1);
        generalTag.setState(0);
        generalTag.setCreatedAt(LocalDateTime.now());
        generalTag.setCreatedBy(entityManager.getReference(User.class, principalId));

        generalTagRepository.save(generalTag);

        actionHistoryService.saveActionHistory(EntityEnum.GeneralTag, generalTag.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW GENERAL_TAG", "CREATE NEW GENERAL_TAG");

    }


    @Transactional
    public List<FileCategoryDTO> getFileCategoryOfGeneralTag(int id) {
        GeneralTag generalTag = generalTagRepository.findByIdAndFetchFileCategory(id).orElseThrow(
                () -> new ResourceNotFoundException("general tag with id=" + id + " not found")
        );
        return generalTag.getFileCategories().stream().map(ModelConverterUtil::convertFileCategoryToFileCategoryDTO).toList();
    }


    @Transactional
    public void updateDescription(int id, String tagNameDescription, String description, int principalId) {
        GeneralTag generalTag = getGeneralTagByIdOrTagName(id, null);



        if(description != null && !description.isEmpty()) {
            generalTag.setDescription(description);
        }

        if(tagNameDescription != null && !tagNameDescription.isEmpty()) {
            generalTag.setTagNameDescription(tagNameDescription);
        }

        generalTag.setUpdatedAt(LocalDateTime.now());
        generalTag.setUpdatedBy(entityManager.getReference(User.class, principalId));

        generalTagRepository.save(generalTag);

        actionHistoryService.saveActionHistory(EntityEnum.GeneralTag, generalTag.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE GENERAL TAG", "Update GeneralTag, new tagNameDescription=" + tagNameDescription + ", new description=" + description);

    }


    @Transactional
    public void deleteGeneralTag(int generalTagId, int principalId) {
        int count = generalTagRepository.countGeneralTagWithCategory(generalTagId);
        if(count > 0) {
            throw new DependencyResourceException("can not delete general tag with id=" + generalTagId + " , first delete all related FileCategory");
        }
        GeneralTag generalTag = getGeneralTagByIdOrTagName(generalTagId, null);

        generalTagRepository.delete(generalTag);

        actionHistoryService.saveActionHistory(EntityEnum.GeneralTag, generalTagId, ActionEnum.DELETE, principalId,
                "DELETE GENERAL TAG", "DELETE GENERAL TAG");

    }


    public GeneralTagPageDTO getGeneralTagPage(int pageSize, int pageNumber, String search) {
        if(search != null) {
            if(search.isEmpty() || search.isBlank()) {
                search = null;
            }
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        Page<GeneralTag> page = generalTagRepository.findByParameterAndPagination(search, pageable);
        GeneralTagPageDTO generalTagPageDTO = new GeneralTagPageDTO();
        generalTagPageDTO.setGeneralTagDTOList(page.getContent().stream().map(ModelConverterUtil::convertGeneralTagToGeneralTagDTO).toList());
        generalTagPageDTO.setTotalPages(page.getTotalPages());
        generalTagPageDTO.setPageSize(page.getSize());
        generalTagPageDTO.setNumberOfElement(page.getNumberOfElements());
        return generalTagPageDTO;

    }



}
