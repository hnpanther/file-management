package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.repository.FolderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The only thing that writes {@code folder} — see {@code docs/roadmap.md} Phase 6.4.
 *
 * <p>The taxonomy stays authoritative for this phase and {@code folder} is a mirror of it. Three
 * rules keep that honest, and together they are why this is one class rather than three services
 * each writing folder rows of their own:
 *
 * <ul>
 *   <li><b>One writer.</b> Every insert, rename and delete goes through here, so there is one place
 *       to audit and one place to change when roadmap 6.8 makes {@code folder} authoritative.</li>
 *   <li><b>The same transaction as the source.</b> Every method is {@link Propagation#MANDATORY},
 *       so calling one outside a transaction fails loudly rather than committing a mirror row for a
 *       category that then rolls back. {@code ActionHistoryService} is declared the same way, for
 *       the same reason.</li>
 *   <li><b>Converging, not failing.</b> Every operation is get-or-create along the whole ancestry.
 *       Rows written straight through a repository — which the fixtures of most service tests do,
 *       and which a data migration could do in production — have no mirror; the roadmap names that
 *       as a known risk. Rather than let that gap fail an unrelated, legitimate taxonomy write, the
 *       missing ancestors are created on the spot. A mirror must never be able to break the thing it
 *       mirrors.</li>
 * </ul>
 *
 * <p>Self-healing does not make the reconciliation check redundant: it only repairs the ancestry of
 * a row somebody is touching right now. {@code FolderMirrorReconciliationTest} is what proves the
 * mirror describes the <em>whole</em> taxonomy, and it runs on every build.
 *
 * <p>The audit columns are copied from the source row rather than taken from the caller, so a
 * mirrored folder always says exactly what the row it mirrors says. That is also why nothing here
 * takes a {@code principalId}.
 *
 * <p>Deletes never cascade: all three taxonomy services refuse to remove a node that still has
 * children, so a mirrored folder is only ever deleted once it is already a leaf.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class FolderMirrorService {

    private final FolderRepository folderRepository;

    public FolderMirrorService(FolderRepository folderRepository) {
        this.folderRepository = folderRepository;
    }

    // ------------------------------------------------------------------ categories

    public void created(FileCategory category) {
        mirrorOf(category);
    }

    /** A category's directory name never changes; only the label a person reads does. */
    public void renamed(FileCategory category) {
        refresh(mirrorOf(category), category.getCategoryName(), category.getCategoryNameDescription(),
                category.getUpdatedBy());
    }

    public void deletedCategory(int categoryId) {
        delete(FolderSourceType.CATEGORY, categoryId);
    }

    // ------------------------------------------------------------------ sub-categories

    public void created(FileSubCategory subCategory) {
        mirrorOf(subCategory);
    }

    public void renamed(FileSubCategory subCategory) {
        refresh(mirrorOf(subCategory), subCategory.getSubCategoryName(),
                subCategory.getSubCategoryNameDescription(), subCategory.getUpdatedBy());
    }

    public void deletedSubCategory(int subCategoryId) {
        delete(FolderSourceType.SUB_CATEGORY, subCategoryId);
    }

    // ------------------------------------------------------------------ main tags

    public void created(MainTagFile mainTag) {
        mirrorOf(mainTag);
    }

    /**
     * A main tag is the one level whose directory-safe name can actually change
     * ({@code MainTagFileService.updateMainTagFile}), so this moves the folder's {@code name} as
     * well as its label. It never changes the parent: moving a tag between sub-categories is
     * rejected upstream, which is what keeps every mirrored path stable.
     */
    public void renamed(MainTagFile mainTag) {
        refresh(mirrorOf(mainTag), mainTag.getTagName(), mainTag.getTagNameDescription(),
                mainTag.getUpdatedBy());
    }

    public void deletedMainTag(int mainTagId) {
        delete(FolderSourceType.MAIN_TAG, mainTagId);
    }

    // ------------------------------------------------------------------ the root

    /**
     * The folder every other one descends from, created by migration {@code V1.4}.
     *
     * <p>A missing or duplicated root is a broken installation rather than a bad request, so it is
     * worth saying exactly that instead of letting some later query quietly return nothing. The
     * schema cannot express "only one row with a null parent" — MySQL treats nulls in a unique index
     * as distinct — so this is where that rule is actually enforced.
     */
    public Folder root() {
        List<Folder> roots = folderRepository.findRoots();
        if (roots.size() != 1) {
            throw new BusinessException("the folder tree must have exactly one root, found " + roots.size());
        }
        return roots.getFirst();
    }

    // ------------------------------------------------------------------ get-or-create, per level

    private Folder mirrorOf(FileCategory category) {
        return folderRepository.findBySourceTypeAndSourceId(FolderSourceType.CATEGORY, category.getId())
                .orElseGet(() -> create(root(), FolderKind.CATEGORY, FolderSourceType.CATEGORY, category.getId(),
                        category.getCategoryName(), category.getCategoryNameDescription(),
                        category.getGeneralTag(), category.getEnabled(), category.getState(),
                        category.getCreatedBy()));
    }

    private Folder mirrorOf(FileSubCategory subCategory) {
        return folderRepository.findBySourceTypeAndSourceId(FolderSourceType.SUB_CATEGORY, subCategory.getId())
                .orElseGet(() -> create(mirrorOf(subCategory.getFileCategory()), FolderKind.SUB_CATEGORY,
                        FolderSourceType.SUB_CATEGORY, subCategory.getId(),
                        subCategory.getSubCategoryName(), subCategory.getSubCategoryNameDescription(),
                        null, subCategory.getEnabled(), subCategory.getState(), subCategory.getCreatedBy()));
    }

    private Folder mirrorOf(MainTagFile mainTag) {
        return folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, mainTag.getId())
                .orElseGet(() -> create(mirrorOf(mainTag.getFileSubCategory()), FolderKind.TAG,
                        FolderSourceType.MAIN_TAG, mainTag.getId(),
                        mainTag.getTagName(), mainTag.getTagNameDescription(),
                        null, mainTag.getEnabled(), mainTag.getState(), mainTag.getCreatedBy()));
    }

    // ------------------------------------------------------------------ the three operations

    private Folder create(Folder parent, FolderKind kind, FolderSourceType sourceType, int sourceId,
                          String name, String displayName, GeneralTag generalTag,
                          Integer enabled, Integer state, User createdBy) {

        Folder folder = new Folder();
        folder.setParent(parent);
        folder.setName(name);
        folder.setDisplayName(displayName);
        folder.setDepth(parent.getDepth() + 1);
        folder.setKind(kind);
        folder.setSourceType(sourceType);
        folder.setSourceId(sourceId);
        folder.setGeneralTag(generalTag);
        folder.setEnabled(enabled);
        folder.setState(state);
        folder.setCreatedBy(createdBy);

        // A path contains the row's own id, which AUTO_INCREMENT only assigns at insert, so it is
        // written in two steps. The empty string never leaves this transaction, and the second write
        // is the dirty check rather than a second save.
        folder.setPath("");
        Folder saved = folderRepository.save(folder);
        saved.setPath(parent.childPath(saved.getId()));
        return saved;
    }

    private void refresh(Folder folder, String name, String displayName, User updatedBy) {
        folder.setName(name);
        folder.setDisplayName(displayName);
        folder.setUpdatedBy(updatedBy);
    }

    /**
     * Lenient by design: the end state a delete asks for is "no folder row for this source", and if
     * there is none already then that is satisfied. Failing here would let a gap in the mirror block
     * a legitimate taxonomy delete, which is the one thing a mirror must never do.
     */
    private void delete(FolderSourceType sourceType, int sourceId) {
        folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId)
                .ifPresent(folderRepository::delete);
    }
}
