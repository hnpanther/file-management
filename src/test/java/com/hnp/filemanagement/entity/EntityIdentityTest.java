package com.hnp.filemanagement.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code equals}, {@code hashCode} and {@code toString} contract on {@link AbstractEntity} —
 * pure unit tests, no Spring and no database.
 *
 * <p>Every one of these is a regression test. The entities were all annotated {@code @Data}, which
 * generated the three methods over every field including the associations, and each test below
 * names the specific failure that produced.
 */
class EntityIdentityTest {

    @Test
    @DisplayName("toString does not recurse across a bidirectional pair")
    void toStringDoesNotRecurse() {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setId(1);
        fileInfo.setFileName("report");

        FileDetails fileDetails = new FileDetails();
        fileDetails.setId(2);
        fileInfo.addFileDetails(fileDetails);

        // With @Data this pair was a StackOverflowError: FileInfo printed FileDetails, which
        // printed FileInfo. Any log line that touched an entity crashed the request.
        assertThat(fileInfo.toString()).isEqualTo("FileInfo#1");
        assertThat(fileDetails.toString()).isEqualTo("FileDetails#2");
    }

    @Test
    @DisplayName("toString names the type and the id and nothing else")
    void toStringLeaksNothing() {
        User user = new User();
        user.setId(7);
        user.setUsername("someone");
        user.setPassword("$2a$10$averyrealbcrypthash");

        assertThat(user.toString()).isEqualTo("User#7");
        assertThat(user.toString()).doesNotContain("averyrealbcrypthash");
    }

    @Test
    @DisplayName("two entities of the same type with the same id are equal")
    void sameIdIsEqual() {
        assertThat(withId(new FileInfo(), 5)).isEqualTo(withId(new FileInfo(), 5));
    }

    @Test
    @DisplayName("different ids are not equal, and neither are different types sharing an id")
    void differentIdentityIsNotEqual() {
        assertThat(withId(new FileInfo(), 5)).isNotEqualTo(withId(new FileInfo(), 6));
        assertThat(withId(new FileInfo(), 5)).isNotEqualTo(withId(new FileDetails(), 5));
        assertThat(withId(new FileInfo(), 5)).isNotEqualTo(null);
    }

    @Test
    @DisplayName("two unsaved entities are equal only to themselves")
    void unsavedEntitiesAreOnlyEqualToThemselves() {
        FileInfo first = new FileInfo();
        FileInfo second = new FileInfo();

        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("an entity stays findable in a HashSet after its id is assigned")
    void hashCodeSurvivesTheIdBeingAssigned() {
        FileInfo fileInfo = new FileInfo();
        Set<FileInfo> set = new HashSet<>();
        set.add(fileInfo);

        // This is what the insert does. With a hashCode computed over the id - which @Data
        // generated - the entity moved bucket here and could never be found again.
        fileInfo.setId(42);

        assertThat(set).contains(fileInfo);
    }

    @Test
    @DisplayName("equals does not read the associations, so it cannot force a lazy load")
    void equalsIgnoresAssociations() {
        FileInfo withChild = withId(new FileInfo(), 5);
        withChild.addFileDetails(new FileDetails());

        FileInfo withoutChild = withId(new FileInfo(), 5);

        assertThat(withChild).isEqualTo(withoutChild);
    }

    @Test
    @DisplayName("adding a version links both sides of the association")
    void addFileDetailsLinksBothSides() {
        FileInfo fileInfo = withId(new FileInfo(), 1);
        FileDetails fileDetails = new FileDetails();

        fileInfo.addFileDetails(fileDetails);

        assertThat(fileInfo.getFileDetailsList()).containsExactly(fileDetails);
        assertThat(fileDetails.getFileInfo()).isSameAs(fileInfo);
    }

    @Test
    @DisplayName("removing a version unlinks both sides, which is what orphan removal deletes on")
    void removeFileDetailsUnlinksBothSides() {
        FileInfo fileInfo = withId(new FileInfo(), 1);
        FileDetails fileDetails = withId(new FileDetails(), 2);
        fileInfo.addFileDetails(fileDetails);

        fileInfo.removeFileDetails(fileDetails);

        assertThat(fileInfo.getFileDetailsList()).isEmpty();
        assertThat(fileDetails.getFileInfo()).isNull();
    }

    private static <T extends AbstractEntity> T withId(T entity, int id) {
        entity.setId(id);
        return entity;
    }
}
