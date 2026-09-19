package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.TagGroupDTO;
import com.hnp.filemanagement.dto.TagGroupForm;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.TagRepository;
import com.hnp.filemanagement.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * The tag groups as a settings page manages them: the "general tags" of the old taxonomy, each a
 * name and a title that a top-level folder carries and the tags beneath it are grouped in.
 *
 * <p>Creating one here and naming a new one on the create-a-folder form are the same thing
 * ({@code FolderService.tagGroupFor} get-or-creates by name); this page is where a group is
 * re-titled, renamed, or removed once nothing uses it. A rename changes no tag: tags hang off the
 * group's id. Deleting is refused while a folder carries the group or a tag sits in it - the
 * foreign keys say so, and this says it first with a message.
 */
@Service
@Transactional
public class TagGroupService {

    /** One group as the page lists it, with what stands in the way of deleting it. */
    public record TagGroupRow(int id, String name, String title, long folders, long tags) {
        public boolean deletable() {
            return folders == 0 && tags == 0;
        }
    }

    private final TagGroupRepository tagGroupRepository;
    private final TagRepository tagRepository;
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;

    public TagGroupService(TagGroupRepository tagGroupRepository, TagRepository tagRepository,
                           FolderRepository folderRepository, UserRepository userRepository,
                           ActionHistoryService actionHistoryService) {
        this.tagGroupRepository = tagGroupRepository;
        this.tagRepository = tagRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
    }

    @Transactional(readOnly = true)
    public List<TagGroupRow> rows() {
        return tagGroupRepository.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(g -> new TagGroupRow(g.getId(), g.getName(), g.getTitle(),
                        folderRepository.countByTagGroupId(g.getId()), tagRepository.countByGroupId(g.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TagGroupForm formOf(int id) {
        TagGroup group = require(id);
        TagGroupForm form = new TagGroupForm();
        form.setId(group.getId());
        form.setName(group.getName());
        form.setTitle(group.getTitle());
        return form;
    }

    public TagGroupDTO create(TagGroupForm form, int principalId) {
        String name = requireName(form.getName());
        String title = requireTitle(form.getTitle(), name);
        tagGroupRepository.findByName(name).ifPresent(taken -> {
            throw new DuplicateResourceException("a tag group named " + name + " already exists");
        });

        TagGroup group = new TagGroup();
        group.setName(name);
        group.setTitle(title);
        group.setEnabled(1);
        group.setCreatedBy(userRepository.getReferenceById(principalId));
        TagGroup saved = tagGroupRepository.save(group);

        actionHistoryService.saveActionHistory(EntityEnum.TagGroup, saved.getId(), ActionEnum.CREATE, principalId,
                "CREATE TAG_GROUP", "CREATE tag group " + name);
        return new TagGroupDTO(saved.getId(), saved.getName(), saved.getTitle());
    }

    public TagGroupDTO update(int id, TagGroupForm form, int principalId) {
        TagGroup group = require(id);
        String name = requireName(form.getName());
        String title = requireTitle(form.getTitle(), name);
        tagGroupRepository.findByName(name)
                .filter(other -> !Objects.equals(other.getId(), group.getId()))
                .ifPresent(taken -> {
                    throw new DuplicateResourceException("a tag group named " + name + " already exists");
                });

        group.setName(name);
        group.setTitle(title);
        group.setUpdatedBy(userRepository.getReferenceById(principalId));

        actionHistoryService.saveActionHistory(EntityEnum.TagGroup, group.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE TAG_GROUP", "UPDATE tag group id=" + id + " to " + name);
        return new TagGroupDTO(group.getId(), group.getName(), group.getTitle());
    }

    public void delete(int id, int principalId) {
        TagGroup group = require(id);
        long folders = folderRepository.countByTagGroupId(id);
        if (folders > 0) {
            List<Folder> carriers = folderRepository.findByTagGroupId(id);
            throw new DependencyResourceException("tag group id=" + id + " is carried by " + folders
                    + " top-level folder(s), e.g. " + carriers.getFirst().getName());
        }
        long tags = tagRepository.countByGroupId(id);
        if (tags > 0) {
            throw new DependencyResourceException("tag group id=" + id + " still holds " + tags + " tag(s)");
        }

        String name = group.getName();
        tagGroupRepository.delete(group);
        actionHistoryService.saveActionHistory(EntityEnum.TagGroup, id, ActionEnum.DELETE, principalId,
                "DELETE TAG_GROUP", "DELETE tag group id=" + id + " (" + name + ")");
    }

    private TagGroup require(int id) {
        return tagGroupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("tag group not found, id=" + id));
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new InvalidDataException("a tag group name is 1-100 characters");
        }
        return trimmed;
    }

    private static String requireTitle(String title, String fallback) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.length() > 200) {
            throw new InvalidDataException("a tag group title is at most 200 characters");
        }
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
