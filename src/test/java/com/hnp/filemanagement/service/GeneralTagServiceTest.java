package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.ActionHistoryDTO;
import com.hnp.filemanagement.dto.GeneralTagDTO;
import com.hnp.filemanagement.dto.GeneralTagPageDTO;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GeneralTagService} against a real database, through the real Spring bean — so the
 * transaction boundaries on the service are actually in play.
 */
@ServiceIntegrationTest
class GeneralTagServiceTest extends MySqlSupport {

    @Autowired
    private GeneralTagService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private UserRepository userRepository;

    private int principalId;
    private int taggedId;
    private int untaggedId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag tagged = generalTagRepository.save(TestData.generalTag(creator, "IT" + TestData.nextSequence()));
        taggedId = tagged.getId();

        GeneralTag untagged = generalTagRepository.save(
                TestData.generalTag(creator, "Contract" + TestData.nextSequence()));
        untaggedId = untagged.getId();

        FileCategory category = TestData.category(creator, tagged, "documents" + TestData.nextSequence());
        fileCategoryRepository.save(category);
    }

    @Test
    @DisplayName("a tag is found by id")
    void findsATagById() {
        GeneralTagDTO tag = underTest.getGeneralTagDtoById(taggedId);
        assertThat(tag.getId()).isEqualTo(taggedId);
        assertThat(tag.getTagName()).startsWith("IT");
    }

    @Test
    @DisplayName("an id that does not exist is a 404, not an empty result")
    void missingTagIsNotFound() {
        assertThatThrownBy(() -> underTest.getGeneralTagDtoById(0))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("creating a tag writes the row and one history line")
    void createsATag() {
        GeneralTagDTO request = new GeneralTagDTO();
        request.setTagName("HR" + TestData.nextSequence());
        request.setTagNameDescription("HR Desc");

        underTest.createNewGeneralTag(request, principalId);

        GeneralTagDTO created = underTest.getGeneralTagDtoByTagName(request.getTagName());
        assertThat(created.getTagNameDescription()).isEqualTo("HR Desc");

        List<ActionHistoryDTO> history =
                actionHistoryService.getActionHistoriesOfEntity(created.getId(), EntityEnum.GeneralTag);
        assertThat(history).hasSize(1);
    }

    @Test
    @DisplayName("a duplicate tag name is a 409")
    void rejectsADuplicateName() {
        GeneralTagDTO request = new GeneralTagDTO();
        request.setTagName(generalTagRepository.findById(taggedId).orElseThrow().getTagName());
        request.setTagNameDescription("whatever");

        assertThatThrownBy(() -> underTest.createNewGeneralTag(request, principalId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("update writes both fields and records the change")
    void updatesDescriptions() {
        underTest.updateDescription(taggedId, "new name description", "new description", principalId);

        GeneralTagDTO updated = underTest.getGeneralTagDtoById(taggedId);
        assertThat(updated.getTagNameDescription()).isEqualTo("new name description");
        assertThat(updated.getDescription()).isEqualTo("new description");
        assertThat(actionHistoryService.getActionHistoriesOfEntity(taggedId, EntityEnum.GeneralTag))
                .isNotEmpty();
    }

    @Test
    @DisplayName("an omitted field is left alone rather than blanked")
    void leavesOmittedFieldsAlone() {
        String before = underTest.getGeneralTagDtoById(taggedId).getDescription();

        underTest.updateDescription(taggedId, "only the name changed", null, principalId);

        GeneralTagDTO updated = underTest.getGeneralTagDtoById(taggedId);
        assertThat(updated.getTagNameDescription()).isEqualTo("only the name changed");
        assertThat(updated.getDescription()).isEqualTo(before);
    }

    @Test
    @DisplayName("a tag no category uses can be deleted")
    void deletesAnUnusedTag() {
        underTest.deleteGeneralTag(untaggedId, principalId);

        assertThat(generalTagRepository.findById(untaggedId)).isEmpty();
    }

    @Test
    @DisplayName("a tag a category still uses is a 409, and survives")
    void refusesToDeleteATagInUse() {
        assertThatThrownBy(() -> underTest.deleteGeneralTag(taggedId, principalId))
                .isInstanceOf(DependencyResourceException.class);

        assertThat(generalTagRepository.findById(taggedId)).isPresent();
    }

    @Test
    @DisplayName("deleting a tag that does not exist is a 404, not a silent success")
    void refusesToDeleteAMissingTag() {
        assertThatThrownBy(() -> underTest.deleteGeneralTag(0, principalId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("the page is filtered by the search term, and a blank term matches everything")
    void pagesAndFilters() {
        String name = generalTagRepository.findById(taggedId).orElseThrow().getTagName();

        GeneralTagPageDTO filtered = underTest.getGeneralTagPage(10, 0, name);
        assertThat(filtered.getGeneralTagDTOList())
                .extracting(GeneralTagDTO::getTagName)
                .containsExactly(name);

        GeneralTagPageDTO blank = underTest.getGeneralTagPage(10, 0, "   ");
        assertThat(blank.getGeneralTagDTOList().size()).isGreaterThanOrEqualTo(2);
    }
}
