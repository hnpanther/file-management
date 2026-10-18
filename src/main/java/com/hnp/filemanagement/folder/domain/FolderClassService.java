package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.persistence.FolderClassRepository;
import com.hnp.filemanagement.folder.persistence.RoleClassGrantRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.util.SearchKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The folder classes themselves (roadmap Phase 14): defined, changed and deleted on the settings page, by
 * whoever holds {@code SAVE_FOLDER_CLASS} / {@code DELETE_FOLDER_CLASS}.
 *
 * <p>What is checked, so that a class can never do more than its page showed: a name unique without case, of
 * letters, digits, {@code _} and {@code -}; a title; a template of at most {@value #MAX_TEMPLATE} folders whose
 * names are valid folder names, unique folded, each with a class that exists; the class other children take
 * existing when there is one; and no template that would make a folder of a class inside a folder of the same
 * class without end - a cycle through templates is refused, since a folder made of any class on it would make
 * folders until the depth limit stopped it. A change applies to what is made from then on; nothing already made
 * changes ({@code FolderClassAssignment} applies a class to what exists, on request). A class is deleted only
 * when nothing uses it - no folder, no grant, no other class.
 */
@Service
@Transactional(readOnly = true)
public class FolderClassService {

    static final int MAX_TEMPLATE = 50;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final FolderClassRepository folderClassRepository;
    private final RoleClassGrantRepository roleClassGrantRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;

    public FolderClassService(FolderClassRepository folderClassRepository, RoleClassGrantRepository roleClassGrantRepository,
                              UserRepository userRepository, ActionHistoryService actionHistoryService) {
        this.folderClassRepository = folderClassRepository;
        this.roleClassGrantRepository = roleClassGrantRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
    }

    /** A template folder as a form posts it: its name, label and the id of its class. */
    public record TemplateFolder(String name, String displayName, Integer classId) {
    }

    /** A class as a form posts it. {@code otherChildren} is {@code NONE}, {@code CLASS} or {@code REFUSE}. */
    public record Definition(String name, String title, boolean containerOnly, String otherChildren,
                             Integer otherChildrenClassId, List<TemplateFolder> template) {
    }

    /** A class as the settings page lists it, with how many folders carry it. */
    public record ClassView(int id, String name, String title, boolean containerOnly, OtherChildren otherChildren,
                            Integer otherChildrenClassId, String otherChildrenClassTitle, List<TemplateView> template,
                            long folders) {
    }

    /** A template folder as the page shows it; {@code classId} and {@code classTitle} null for one of no class. */
    public record TemplateView(String name, String displayName, Integer classId, String classTitle) {
    }

    public List<ClassView> all() {
        Map<Integer, Long> counts = new HashMap<>();
        for (Object[] row : folderClassRepository.countFoldersByClass()) {
            counts.put(((Number) row[0]).intValue(), ((Number) row[1]).longValue());
        }
        return folderClassRepository.findAllWithTemplates().stream().map(c -> view(c, counts.getOrDefault(c.getId(), 0L))).toList();
    }

    public ClassView one(int classId) {
        FolderClass folderClass = require(classId);
        return view(folderClass, folderClassRepository.countFoldersOf(classId));
    }

    @Transactional
    public int create(Definition definition, int principalId) {
        FolderClass folderClass = new FolderClass();
        String name = requireName(definition.name(), null);
        folderClass.setName(name);
        folderClass.setCreatedBy(userRepository.getReferenceById(principalId));
        apply(folderClass, definition);
        // Saved before its template, which may name the class itself as the class of other children.
        FolderClass saved = folderClassRepository.save(folderClass);
        replaceTemplate(saved, definition);
        requireNoTemplateCycle(saved);
        actionHistoryService.saveActionHistory(EntityEnum.FolderClass, saved.getId(), ActionEnum.CREATE, principalId,
                "CREATE FOLDER_CLASS", "CREATE folder class " + name + " (template of " + saved.getChildren().size() + ")");
        return saved.getId();
    }

    @Transactional
    public void update(int classId, Definition definition, int principalId) {
        FolderClass folderClass = require(classId);
        folderClass.setName(requireName(definition.name(), classId));
        apply(folderClass, definition);
        folderClass.setUpdatedBy(userRepository.getReferenceById(principalId));
        replaceTemplate(folderClass, definition);
        folderClassRepository.flush();
        requireNoTemplateCycle(folderClass);
        actionHistoryService.saveActionHistory(EntityEnum.FolderClass, classId, ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE FOLDER_CLASS", "UPDATE folder class id=" + classId + " (template of " + folderClass.getChildren().size()
                        + ", container only " + folderClass.isContainerOnly() + ", other children " + folderClass.getOtherChildren() + ")");
    }

    /** Deletes a class nothing uses: no folder carries it, no role is granted it, no other class names it. */
    @Transactional
    public void delete(int classId, int principalId) {
        FolderClass folderClass = require(classId);
        long folders = folderClassRepository.countFoldersOf(classId);
        if (folders > 0) {
            throw new InvalidDataException("folder class id=" + classId + " is carried by " + folders + " folder(s)",
                    "folderClass.inUse.folders", folderClass.getTitle(), folders);
        }
        long grants = roleClassGrantRepository.countByFolderClassId(classId);
        if (grants > 0) {
            throw new InvalidDataException("folder class id=" + classId + " is granted to " + grants + " role(s)",
                    "folderClass.inUse.grants", folderClass.getTitle(), grants);
        }
        if (folderClassRepository.isNamedByAnotherClass(classId)) {
            throw new InvalidDataException("folder class id=" + classId + " is named by another class",
                    "folderClass.inUse.classes", folderClass.getTitle());
        }
        folderClassRepository.delete(folderClass);
        actionHistoryService.saveActionHistory(EntityEnum.FolderClass, classId, ActionEnum.DELETE, principalId,
                "DELETE FOLDER_CLASS", "DELETE folder class id=" + classId + " (" + folderClass.getName() + ")");
    }

    FolderClass require(int classId) {
        return folderClassRepository.findByIdWithTemplate(classId).orElseThrow(
                () -> new ResourceNotFoundException("folder class with id=" + classId + " doesn't exists"));
    }

    private String requireName(String name, Integer selfId) {
        String trimmed = name == null ? "" : name.strip();
        if (!NAME.matcher(trimmed).matches()) {
            throw new InvalidDataException("a class name is 1-100 letters, digits, _ or -", "folderClass.invalid.name");
        }
        boolean taken = selfId == null ? folderClassRepository.existsByNameIgnoreCase(trimmed)
                : folderClassRepository.existsByNameIgnoreCaseAndIdNot(trimmed, selfId);
        if (taken) {
            throw new DuplicateResourceException("a folder class named " + trimmed + " exists");
        }
        return trimmed;
    }

    private void apply(FolderClass folderClass, Definition definition) {
        String title = definition.title() == null ? "" : definition.title().strip();
        if (title.isEmpty() || title.length() > 200) {
            throw new InvalidDataException("a class title is 1-200 characters", "folderClass.invalid.title");
        }
        folderClass.setTitle(title);
        folderClass.setContainerOnly(definition.containerOnly());
        OtherChildren other = otherChildrenOf(definition.otherChildren());
        folderClass.setOtherChildren(other);
        if (other == OtherChildren.CLASS) {
            if (definition.otherChildrenClassId() == null) {
                throw new InvalidDataException("other children take a class: name it", "folderClass.invalid.otherClass");
            }
            // The class itself may be named: a folder made by hand below one of it is of it too. That makes
            // nothing by itself - only a template does - so it is no cycle.
            folderClass.setOtherChildrenClass(folderClass.getId() != null && folderClass.getId().equals(definition.otherChildrenClassId())
                    ? folderClass : requireOther(definition.otherChildrenClassId()));
        } else {
            folderClass.setOtherChildrenClass(null);
        }
    }

    private static OtherChildren otherChildrenOf(String value) {
        if (value == null || value.isBlank()) {
            return OtherChildren.NONE;
        }
        try {
            return OtherChildren.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidDataException("other children are NONE, CLASS or REFUSE", "folderClass.invalid.otherChildren");
        }
    }

    private FolderClass requireOther(int classId) {
        return folderClassRepository.findById(classId).orElseThrow(
                () -> new InvalidDataException("folder class id=" + classId + " does not exist", "folderClass.invalid.otherClass"));
    }

    /** The template exactly as posted - each folder checked as a folder name is, unique folded, with its class. */
    private void replaceTemplate(FolderClass folderClass, Definition definition) {
        List<TemplateFolder> posted = definition.template() == null ? List.of() : definition.template().stream()
                .filter(folder -> folder != null && folder.name() != null && !folder.name().isBlank())
                .toList();
        if (posted.size() > MAX_TEMPLATE) {
            throw new InvalidDataException("a template holds at most " + MAX_TEMPLATE + " folders",
                    "folderClass.invalid.templateSize", MAX_TEMPLATE);
        }
        List<FolderClassChild> children = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int position = 0;
        for (TemplateFolder folder : posted) {
            String name;
            try {
                name = FolderService.requireDirectoryName(folder.name(), false);
            } catch (InvalidDataException e) {
                throw new InvalidDataException("a template folder's name is not a folder name", "folderClass.invalid.templateName",
                        folder.name());
            }
            if (!keys.add(SearchKey.of(name, SearchKey.NAME_LENGTH))) {
                throw new InvalidDataException("a template names one folder twice", "folderClass.invalid.templateDuplicate", name);
            }
            String label = folder.displayName() == null || folder.displayName().isBlank() ? name : folder.displayName().strip();
            if (label.length() > 200) {
                throw new InvalidDataException("a template folder's label is at most 200 characters",
                        "folderClass.invalid.templateLabel", name);
            }
            // No class is allowed (2.17.1): a folder made for order alone, reached through the grants above it.
            FolderClass childClass = folder.classId() == null ? null
                    : folder.classId().equals(folderClass.getId()) ? folderClass
                    : folderClassRepository.findById(folder.classId()).orElseThrow(() -> new InvalidDataException(
                    "folder class id=" + folder.classId() + " does not exist", "folderClass.invalid.templateClass", name));
            FolderClassChild child = new FolderClassChild();
            child.setFolderClass(folderClass);
            child.setName(name);
            child.setDisplayName(label);
            child.setChildClass(childClass);
            child.setPosition(position++);
            children.add(child);
        }
        // Orphan removal deletes what is gone; the unique index is per (class, name), so the old rows are flushed
        // away before the new ones go in.
        folderClass.getChildren().clear();
        folderClassRepository.flush();
        folderClass.getChildren().addAll(children);
    }

    /**
     * Refuses a template through which a folder of this class would, folder by folder, make another folder of
     * this class: each would make the next until the depth limit refused the last, and with it every upload that
     * made the first. The refusal names the template folder that closes the cycle and its class - the usual cause
     * is a class made before the classes of its template, its own name the only one there was to choose.
     */
    private void requireNoTemplateCycle(FolderClass start) {
        Map<Integer, List<Integer>> edges = new HashMap<>();
        for (FolderClass each : folderClassRepository.findAllWithTemplates()) {
            edges.put(each.getId(), classesOf(each));
        }
        edges.put(start.getId(), classesOf(start));
        for (FolderClassChild child : start.getChildren()) {
            if (child.getChildClass() != null && reaches(child.getChildClass().getId(), start.getId(), edges)) {
                throw new InvalidDataException("folder class id=" + start.getId() + " would make itself through its template",
                        "folderClass.invalid.templateCycle", start.getTitle(), child.getName(), child.getChildClass().getTitle());
            }
        }
    }

    /** The classes a class's template makes, a template folder of no class left out. */
    private static List<Integer> classesOf(FolderClass folderClass) {
        return folderClass.getChildren().stream().filter(child -> child.getChildClass() != null)
                .map(child -> child.getChildClass().getId()).toList();
    }

    /** Whether a folder of class {@code from} makes, at some depth, a folder of class {@code target}. */
    private static boolean reaches(int from, int target, Map<Integer, List<Integer>> edges) {
        Deque<Integer> toVisit = new ArrayDeque<>(List.of(from));
        Set<Integer> seen = new HashSet<>();
        while (!toVisit.isEmpty()) {
            int next = toVisit.pop();
            if (next == target) {
                return true;
            }
            if (seen.add(next)) {
                toVisit.addAll(edges.getOrDefault(next, List.of()));
            }
        }
        return false;
    }

    private static ClassView view(FolderClass c, long folders) {
        FolderClass other = c.getOtherChildrenClass();
        return new ClassView(c.getId(), c.getName(), c.getTitle(), c.isContainerOnly(), c.getOtherChildren(),
                other == null ? null : other.getId(), other == null ? null : other.getTitle(),
                c.getChildren().stream().map(ch -> new TemplateView(ch.getName(), ch.getDisplayName(),
                        ch.getChildClass() == null ? null : ch.getChildClass().getId(),
                        ch.getChildClass() == null ? null : ch.getChildClass().getTitle())).toList(), folders);
    }
}
