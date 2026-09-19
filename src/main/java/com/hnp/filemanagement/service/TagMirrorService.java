package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The only thing that writes {@code tag} and {@code file_tag} (roadmap 7.2 step 2, 7.3).
 *
 * <p>{@link Propagation#MANDATORY}, so a tag can never be committed for a file that then rolls
 * back, and get-or-create everywhere so that data written behind the services is converged on
 * rather than failed on.
 *
 * <p><b>A file's tags are a function of its folder chain</b>, nothing more: every folder from
 * the top level down to the one it sits in, each as a tag in the group the top-level folder
 * carries. {@link #retag} makes a file's set exactly that, and is called wherever the function's
 * inputs change - an upload, a folder rename, a folder move, a change of a top-level folder's
 * group. {@code FileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders} is the check that this
 * held.
 *
 * <p>A tag's title is copied when the tag is created and not followed afterwards. Names merge
 * within a group (two folders on one chain both called {@code HSED} are one tag), so "which
 * folder's label wins" has no answer until tags are edited as tags.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TagMirrorService {

    private final TagRepository tagRepository;
    private final FileInfoRepository fileInfoRepository;
    private final FolderRepository folderRepository;

    public TagMirrorService(TagRepository tagRepository, FileInfoRepository fileInfoRepository,
                            FolderRepository folderRepository) {
        this.tagRepository = tagRepository;
        this.fileInfoRepository = fileInfoRepository;
        this.folderRepository = folderRepository;
    }

    /** Makes this file's tags exactly the ones its folder chain says. */
    public void retag(FileInfo file) {
        Set<Tag> derived = tagsFor(file.getFolder());
        file.getTags().retainAll(derived);
        file.getTags().addAll(derived);
    }

    /**
     * Every file beneath this folder, after a name in the chain changed. The tag with the old name
     * is left where it is: a label nothing carries is not a fault, and another branch may still
     * carry it.
     */
    public void retagFilesUnder(Folder folder) {
        List<FileInfo> files = fileInfoRepository.findBySubtree(folder.getPath());
        for (FileInfo file : files) {
            retag(file);
        }
    }

    /** The tags a file in this folder carries: one per folder on the chain, deduplicated by name. */
    public Set<Tag> tagsFor(Folder folder) {
        List<Folder> chain = chainOf(folder);
        if (chain.isEmpty()) {
            throw new BusinessException("folder id=" + folder.getId() + " is the root; a file cannot be filed there");
        }
        TagGroup group = chain.getFirst().getTagGroup();
        if (group == null) {
            throw new BusinessException("top-level folder id=" + chain.getFirst().getId()
                    + " has no tag group; the tags of the files beneath it cannot be derived");
        }

        // Order matters for a name two levels share: the first to claim it sets the title, and
        // that is the higher level, as in the migration's backfill.
        Set<Tag> tags = new LinkedHashSet<>();
        for (Folder each : chain) {
            tags.add(tagOf(group, each));
        }
        return tags;
    }

    /** The folders on this one's path, outermost first, itself last; the root left out. */
    private List<Folder> chainOf(Folder folder) {
        List<Integer> ids = FolderService.idsIn(folder.getPath());
        Map<Integer, Folder> byId = folderRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Folder::getId, Function.identity()));
        return FolderService.chainFrom(folder.getPath(), byId);
    }

    // ------------------------------------------------------------------ get-or-create

    private Tag tagOf(TagGroup group, Folder folder) {
        return tagRepository.findByGroupIdAndName(group.getId(), folder.getName()).orElseGet(() -> {
            Tag tag = new Tag();
            tag.setGroup(group);
            tag.setName(folder.getName());
            tag.setTitle(folder.getDisplayName());
            tag.setEnabled(1);
            User creator = folder.getCreatedBy();
            tag.setCreatedBy(creator);
            return tagRepository.save(tag);
        });
    }
}
