package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The only thing that writes {@code tag_group}, {@code tag} and {@code file_tag} while the
 * taxonomy is still authoritative (roadmap 7.2 step 2, 7.3).
 *
 * <p>Same shape and same rules as {@link FolderMirrorService}, for the same reasons: one writer,
 * {@link Propagation#MANDATORY} so a tag can never be committed for a file that then rolls back,
 * and get-or-create everywhere so that data written behind the services - which most test
 * fixtures and any data migration do - is converged on rather than failed on.
 *
 * <p><b>A file's tags are a function of its taxonomy</b>, nothing more, for this whole phase:
 * the category, the sub-category and the main tag it sits under, each as a tag in the group of
 * its general tag. {@link #retag} makes a file's set exactly that, and is called wherever the
 * function's inputs change - an upload, and a main-tag rename, which is the one level whose
 * name can change. {@code FileInfoRepository.findRowsWhoseTagsDisagreeWithTheTaxonomy} is the
 * check that this held.
 *
 * <p>A tag's title is copied when the tag is created and not followed afterwards. Names merge
 * within a group (a sub-category and a main tag both called {@code HSED} are one tag), so "which
 * row's label wins" has no answer until tags are edited as tags, which is step 5.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TagMirrorService {

    private final TagGroupRepository tagGroupRepository;
    private final TagRepository tagRepository;
    private final FileInfoRepository fileInfoRepository;

    public TagMirrorService(TagGroupRepository tagGroupRepository, TagRepository tagRepository,
                            FileInfoRepository fileInfoRepository) {
        this.tagGroupRepository = tagGroupRepository;
        this.tagRepository = tagRepository;
        this.fileInfoRepository = fileInfoRepository;
    }

    /** Makes this file's tags exactly the ones its taxonomy says. */
    public void retag(FileInfo file) {
        Set<Tag> derived = tagsFor(file.getMainTagFile());
        file.getTags().retainAll(derived);
        file.getTags().addAll(derived);
    }

    /**
     * Every file under this main tag, after its name changed. The tag with the old name is left
     * where it is: a label nothing carries is not a fault, and another branch may still carry it.
     */
    public void retagFilesUnder(MainTagFile mainTag) {
        List<FileInfo> files = fileInfoRepository.findByMainTagFileIdIn(List.of(mainTag.getId()));
        for (FileInfo file : files) {
            retag(file);
        }
    }

    /** The tags a file under this main tag carries: one per level, deduplicated by name. */
    public Set<Tag> tagsFor(MainTagFile mainTag) {
        FileSubCategory subCategory = mainTag.getFileSubCategory();
        FileCategory category = subCategory.getFileCategory();
        TagGroup group = groupOf(category.getGeneralTag());

        // Order matters for a name two levels share: the first to claim it sets the title, and
        // that is the higher level, as in the migration's backfill.
        Set<Tag> tags = new LinkedHashSet<>();
        tags.add(tagOf(group, category.getCategoryName(), category.getCategoryNameDescription(), category.getCreatedBy()));
        tags.add(tagOf(group, subCategory.getSubCategoryName(), subCategory.getSubCategoryNameDescription(), subCategory.getCreatedBy()));
        tags.add(tagOf(group, mainTag.getTagName(), mainTag.getTagNameDescription(), mainTag.getCreatedBy()));
        return tags;
    }

    // ------------------------------------------------------------------ get-or-create

    public TagGroup groupOf(GeneralTag generalTag) {
        return tagGroupRepository.findByName(generalTag.getTagName()).orElseGet(() -> {
            TagGroup group = new TagGroup();
            group.setName(generalTag.getTagName());
            group.setTitle(generalTag.getTagNameDescription());
            group.setEnabled(1);
            group.setCreatedBy(generalTag.getCreatedBy());
            return tagGroupRepository.save(group);
        });
    }

    private Tag tagOf(TagGroup group, String name, String title, User createdBy) {
        return tagRepository.findByGroupIdAndName(group.getId(), name).orElseGet(() -> {
            Tag tag = new Tag();
            tag.setGroup(group);
            tag.setName(name);
            tag.setTitle(title);
            tag.setEnabled(1);
            tag.setCreatedBy(createdBy);
            return tagRepository.save(tag);
        });
    }
}
