package com.hnp.filemanagement.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prefix rule that folder access is built on — no Spring, no database, because the whole point
 * of a materialised path is that "is this folder inside that grant?" is a string question.
 *
 * <p>The trailing slash on every path is what makes it safe, and it is exactly the kind of detail
 * that is silently wrong until someone has folder 7 and folder 70.
 */
class FolderAccessTest {

    @Test
    @DisplayName("a grant covers the folder itself and everything beneath it")
    void grantCoversItselfAndItsDescendants() {
        FolderAccess access = FolderAccess.of(List.of("/1/5/"));

        assertThat(access.allows("/1/5/")).as("the granted folder itself").isTrue();
        assertThat(access.allows("/1/5/26/")).as("a child").isTrue();
        assertThat(access.allows("/1/5/26/198/")).as("a grandchild").isTrue();
    }

    @Test
    @DisplayName("a grant does not cover its ancestors or its siblings")
    void grantDoesNotLeakUpwardsOrSideways() {
        FolderAccess access = FolderAccess.of(List.of("/1/5/26/"));

        assertThat(access.allows("/1/")).as("the root above the grant").isFalse();
        assertThat(access.allows("/1/5/")).as("the parent above the grant").isFalse();
        assertThat(access.allows("/1/6/")).as("a sibling branch").isFalse();
    }

    @Test
    @DisplayName("the trailing slash stops folder 7 from matching folder 70")
    void oneFolderIsNotAPrefixOfAnother() {
        FolderAccess access = FolderAccess.of(List.of("/1/7/"));

        assertThat(access.allows("/1/7/3/")).isTrue();
        assertThat(access.allows("/1/70/")).as("/1/70/ is not under /1/7/").isFalse();
        assertThat(access.allows("/1/70/3/")).isFalse();
    }

    @Test
    @DisplayName("a grant already covered by a shorter one is dropped")
    void redundantGrantsAreReduced() {
        FolderAccess access = FolderAccess.of(List.of("/1/5/26/", "/1/5/", "/1/9/", "/1/5/27/198/"));

        assertThat(access.grantedPaths())
                .as("only the grants that are not already covered survive")
                .containsExactly("/1/5/", "/1/9/");
    }

    @Test
    @DisplayName("an ancestor of a grant is visible for navigation, but its contents are not readable")
    void anAncestorIsASignpostNotAnOpenDoor() {
        FolderAccess access = FolderAccess.of(List.of("/1/5/26/"));

        // The way down to the grant has to be walkable...
        assertThat(access.isOnPathTo("/1/")).isTrue();
        assertThat(access.isOnPathTo("/1/5/")).isTrue();
        assertThat(access.visible("/1/5/")).isTrue();

        // ...without that making anything in it readable.
        assertThat(access.allows("/1/5/")).isFalse();

        // And a branch that leads nowhere near the grant stays hidden entirely.
        assertThat(access.visible("/1/6/")).isFalse();
        assertThat(access.isOnPathTo("/1/5/27/")).as("a sibling of the grant").isFalse();
    }

    @Test
    @DisplayName("the granted folder itself is readable, not merely on the way to something")
    void theGrantItselfIsNotJustASignpost() {
        FolderAccess access = FolderAccess.of(List.of("/1/5/26/"));

        assertThat(access.allows("/1/5/26/")).isTrue();
        assertThat(access.isOnPathTo("/1/5/26/")).as("a folder is not an ancestor of itself").isFalse();
        assertThat(access.visible("/1/5/26/")).isTrue();
    }

    @Test
    @DisplayName("an administrator is unrestricted without holding any grant")
    void unrestrictedAllowsEverything() {
        FolderAccess access = FolderAccess.everything();

        assertThat(access.unrestricted()).isTrue();
        assertThat(access.grantedPaths()).isEmpty();
        assertThat(access.allows("/1/anything/")).isTrue();
        assertThat(access.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("someone with no grants reaches nothing")
    void noGrantsReachesNothing() {
        FolderAccess access = FolderAccess.nothing();

        assertThat(access.isEmpty()).isTrue();
        assertThat(access.allows("/1/")).isFalse();
    }
}
