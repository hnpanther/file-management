package com.hnp.filemanagement.support;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.TagGroup;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;

/**
 * A three-deep chain for a test - still called category → sub-category → tag, the names the
 * tests grew up with, though since {@code V2.9} they are three folders like any other - written
 * through the repositories the way {@code FolderService} writes it (path after the insert, a tag
 * group on the top-level one). Every name carries a sequence number, so two fixtures never
 * collide under the root.
 *
 * <p>Repositories rather than the service so that a test which is <em>about</em> the service can
 * set the stage without going through what it tests, and so that repository-slice tests can use
 * it too. Nothing here needs a principal or a permission.
 */
public final class FolderFixture {

    private FolderFixture() {
    }

    /** The three folders and their names, outermost first. */
    public record Chain(Folder category, Folder subCategory, Folder tag) {

        public int categoryId() {
            return category.getId();
        }

        public int subCategoryId() {
            return subCategory.getId();
        }

        public int tagId() {
            return tag.getId();
        }

        /** {@code {category}/{subCategory}}: where a file stored before V2.9 lived; since then the directory is the file's own id. */
        public String directory() {
            return category.getName() + "/" + subCategory.getName();
        }
    }

    /** A new chain under the root, with a tag group of its own. */
    public static Chain chain(FolderRepository folders, TagGroupRepository groups, User creator) {
        int n = TestData.nextSequence();
        TagGroup group = groups.save(TestData.tagGroup(creator, "gt" + n));
        Folder category = save(folders, TestData.folder(creator, root(folders), "Cat" + n, group));
        Folder subCategory = save(folders, TestData.folder(creator, category, "Sub" + n, null));
        Folder tag = save(folders, TestData.folder(creator, subCategory, "Tag" + n, null));
        return new Chain(category, subCategory, tag);
    }

    /** Another tag under an existing sub-category. */
    public static Folder tag(FolderRepository folders, Folder subCategory, User creator, String name) {
        return save(folders, TestData.folder(creator, subCategory, name, null));
    }

    /** Another sub-category under an existing category. */
    public static Folder subCategory(FolderRepository folders, Folder category, User creator, String name) {
        return save(folders, TestData.folder(creator, category, name, null));
    }

    /** Another category under the root, in the given group. */
    public static Folder category(FolderRepository folders, User creator, String name, TagGroup group) {
        return save(folders, TestData.folder(creator, root(folders), name, group));
    }

    public static Folder root(FolderRepository folders) {
        return folders.findRoots().getFirst();
    }

    /**
     * Two writes, as in FolderService: the path holds the row's own id, which only the insert
     * assigns. The second is a flush so that the path is in the database for a test that runs
     * outside a transaction (a web test), where nothing else would write it.
     */
    private static Folder save(FolderRepository folders, Folder folder) {
        return folders.saveAndFlush(TestData.placed(folders.save(folder)));
    }
}
