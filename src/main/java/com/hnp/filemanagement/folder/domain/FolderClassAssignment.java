package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.persistence.FolderClassRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.util.SearchKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A folder's class, set by a person (roadmap Phase 14) - and the classes' rules applied to what already exists
 * below a folder. Both need {@code SET_FOLDER_CLASS} and write access on the folder: giving a folder a class
 * gives it to every role granted that class.
 *
 * <p><b>Setting a class</b> changes that folder alone - its class, the class path of everything beneath it,
 * and the folders its new class's template holds where they are missing. Nothing below changes class.
 *
 * <p><b>Applying the rules below a folder</b> is how classes reach a tree made before them: each folder below
 * that has no class takes the one the rules give it under its parent's class - top down, so a company classed
 * by its container's rule classes its {@code Contract} by its template, and so on - and each folder with a class
 * gets the template folders it is missing. A folder that has a class keeps it; a name its parent's class refuses
 * is left as it is and listed. Shown first as counts ({@link #preview}); run in the background ({@link #start}) -
 * the classes in one transaction, then each folder's missing template folders in one of its own, so a failure
 * leaves that folder as it was and the run goes on. Run again, it finds only what is still to do.
 */
@Service
public class FolderClassAssignment {

    private static final Logger logger = LoggerFactory.getLogger(FolderClassAssignment.class);

    /** At most this many folders a rule refuses, and failures, are kept to be shown; all are counted. */
    static final int LISTED = 200;

    private static final String SUBTREE = """
            SELECT f.id, f.parent_id, f.name, f.class_id, f.depth, f.key_path
            FROM folder f
            WHERE f.path LIKE :path || '%'
            ORDER BY f.depth, f.id
            """;

    private final FolderRepository folderRepository;
    private final FolderClassRepository folderClassRepository;
    private final FolderService folderService;
    private final FolderClassService folderClassService;
    private final FolderAccessService folderAccessService;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Run last;

    public FolderClassAssignment(FolderRepository folderRepository, FolderClassRepository folderClassRepository,
                                 FolderService folderService, FolderClassService folderClassService,
                                 FolderAccessService folderAccessService,
                                 UserRepository userRepository, ActionHistoryService actionHistoryService,
                                 NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.folderRepository = folderRepository;
        this.folderClassRepository = folderClassRepository;
        this.folderService = folderService;
        this.folderClassService = folderClassService;
        this.folderAccessService = folderAccessService;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ one folder

    /** What the folder page shows: the folder, its class, and the classes to choose from. */
    public record View(int folderId, String title, String keyPath, Integer classId, String classTitle,
                       boolean settable, List<FolderClassService.ClassView> classes) {
    }

    @Transactional(readOnly = true)
    public View view(int folderId, int principalId) {
        Folder folder = requireFolder(folderId);
        FolderAccess access = folderAccessService.accessFor(principalId);
        if (!access.canRead(folder.getPath())) {
            throw new org.springframework.security.access.AccessDeniedException("no read access to folder id=" + folderId);
        }
        FolderClass current = folder.getFolderClass();
        return new View(folder.getId(), folder.getDisplayName(), folder.getKeyPath(),
                current == null ? null : current.getId(), current == null ? null : current.getTitle(),
                !FolderService.isSystemFolder(folder) && access.canWrite(folder.getPath()), folderClassService.all());
    }

    /** What setting a class did: the template folders it made. */
    public record Assigned(int templateFoldersMade) {
    }

    /**
     * Gives the folder this class, or none ({@code classId} null): its class path and its subtree's rewritten,
     * the template folders the class holds made where missing. Refused for the root, {@code Profiles} and a home.
     */
    @Transactional
    public Assigned set(int folderId, Integer classId, int principalId) {
        Folder folder = requireFolder(folderId);
        if (FolderService.isSystemFolder(folder)) {
            throw new InvalidDataException("a " + folder.getKind() + " folder takes no class: id=" + folderId,
                    "folderClass.systemFolder");
        }
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), folder);
        FolderClass before = folder.getFolderClass();
        FolderClass after = classId == null ? null : folderClassRepository.findById(classId).orElseThrow(
                () -> new InvalidDataException("folder class id=" + classId + " does not exist", "folderClass.notFound"));
        Integer beforeId = before == null ? null : before.getId();
        if (java.util.Objects.equals(beforeId, classId)) {
            return new Assigned(0);
        }
        folder.setFolderClass(after);
        folder.setUpdatedBy(userRepository.getReferenceById(principalId));
        // From the database, not from entities this transaction may have read before (Folder#classPath).
        String oldClassPath = folderRepository.classPathOf(folderId);
        String newClassPath = folderRepository.classPathOf(folder.getParent().getId()) + (after == null ? "" : after.getId() + "/");
        folderRepository.rewriteClassPaths(folder.getPath(), newClassPath, oldClassPath.length());
        // And on the entity, for the template's folders made in it below.
        folder.setClassPath(newClassPath);
        actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.UPDATE_VALUES, principalId,
                "SET FOLDER_CLASS", "SET class of folder id=" + folderId + " from " + (beforeId == null ? "none" : "class id=" + beforeId)
                        + " to " + (classId == null ? "none" : "class id=" + classId));
        int made = after == null ? 0 : folderService.makeTemplate(folder, principalId);
        return new Assigned(made);
    }

    // ------------------------------------------------------------------ the rules applied below a folder

    /** A folder left as it is: the name its parent's class refuses. */
    public record Refused(String keyPath, String classTitle) {
    }

    /** A folder the run could not change, and why - the exception's class, nothing a person wrote. */
    public record Failed(int folderId, String reason) {
    }

    /**
     * What applying the rules below the folder would do: the folders below; those with a class already; how many
     * would take each class; those a class refuses (the first {@value #LISTED}); and the template folders made.
     */
    public record Plan(int folderId, String keyPath, int folders, int classedAlready, Map<String, Integer> toClassify,
                       int refusedCount, List<Refused> refused, int templateFoldersToMake) {

        public int toClassifyTotal() {
            return toClassify.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    @Transactional(readOnly = true)
    public Plan preview(int folderId, int principalId) {
        Folder root = requireApplicable(folderId, principalId);
        Computed computed = compute(root);
        Map<String, Integer> byTitle = new LinkedHashMap<>();
        computed.assignments().forEach((id, classId) ->
                byTitle.merge(computed.classes().get(classId).getTitle(), 1, Integer::sum));
        return new Plan(root.getId(), root.getKeyPath(), computed.rows().size() - 1, computed.classedAlready(), byTitle,
                computed.refused().size(), computed.refused().stream().limit(LISTED).toList(), computed.templateFoldersToMake());
    }

    /** Starts applying the rules below the folder on a thread of its own, unless a run is going on: false then. */
    public boolean start(int folderId, int principalId) {
        Folder root = transactions.execute(status -> requireApplicable(folderId, principalId));
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            Thread.ofPlatform().name("folder-class-apply").daemon(true).start(() -> {
                try {
                    run(root.getId(), principalId);
                } catch (RuntimeException e) {
                    logger.error("folder classes applied below folder id={} stopped", root.getId(), e);
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    /** The run going on, or the last one since the application started - null when there was none. */
    public Progress progress() {
        Run run = last;
        return run == null ? null : run.snapshot(running.get());
    }

    /** A run's progress, as the page shows it; {@code stopped}: not running, not finished - an error, in the log. */
    public record Progress(int folderId, String keyPath, boolean running, Instant startedAt, Instant finishedAt,
                           int classified, int foldersToComplete, int foldersCompleted, int templateFoldersMade,
                           int failedCount, List<Failed> failed) {

        public boolean stopped() {
            return !running && finishedAt == null;
        }
    }

    /** The run itself, in the calling thread. */
    Progress run(int folderId, int principalId) {
        Run run = transactions.execute(status -> {
            Folder root = requireFolder(folderId);
            Computed computed = compute(root);
            Run started = new Run(folderId, root.getKeyPath(), Instant.now(clock));
            last = started;
            // The classes, top down, in one transaction - then the class paths of the subtree, in one statement.
            for (Map.Entry<Integer, Integer> assignment : computed.assignments().entrySet()) {
                jdbc.update("UPDATE folder SET class_id = :classId, updated_by = :by WHERE id = :id AND class_id IS NULL",
                        new MapSqlParameterSource().addValue("classId", assignment.getValue())
                                .addValue("by", principalId).addValue("id", assignment.getKey()));
                actionHistoryService.saveActionHistory(EntityEnum.Folder, assignment.getKey(), ActionEnum.UPDATE_VALUES,
                        principalId, "SET FOLDER_CLASS", "SET class of folder id=" + assignment.getKey()
                                + " to class id=" + assignment.getValue() + " (rules applied below folder id=" + folderId + ")");
            }
            folderRepository.repairClassPaths();
            started.classified(computed.assignments().size(), computed.toComplete());
            actionHistoryService.saveActionHistory(EntityEnum.Folder, folderId, ActionEnum.UPDATE_VALUES, principalId,
                    "APPLY FOLDER_CLASSES", "APPLY folder classes below folder id=" + folderId + ": "
                            + computed.assignments().size() + " classified, " + computed.toComplete().size()
                            + " to complete with their template");
            return started;
        });
        logger.info("folder classes applied below folder id={}: {} folder(s) classified, {} to complete with their template",
                folderId, run.classified, run.foldersToComplete);
        for (int id : run.toComplete) {
            try {
                int made = transactions.execute(status -> folderService.makeTemplate(requireFolder(id), principalId));
                run.completed(made);
            } catch (RuntimeException e) {
                logger.warn("folder classes: the template of folder id={} not made: {}", id, e.getClass().getSimpleName());
                run.failed(id, e.getClass().getSimpleName());
            }
        }
        run.finish(Instant.now(clock));
        Progress result = run.snapshot(false);
        logger.info("folder classes applied below folder id={} finished: {} template folder(s) made, {} folder(s) failed",
                folderId, result.templateFoldersMade(), result.failedCount());
        return result;
    }

    private Folder requireApplicable(int folderId, int principalId) {
        Folder root = requireFolder(folderId);
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), root);
        return root;
    }

    private Folder requireFolder(int folderId) {
        return folderRepository.findById(folderId).orElseThrow(
                () -> new ResourceNotFoundException("folder not found, id=" + folderId));
    }

    private record Row(int id, Integer parentId, String name, Integer classId, int depth, String keyPath) {
    }

    /**
     * What the rules make of the subtree, worked out in memory over one read of it: the class each unclassified
     * folder takes (folder id to class id, parents before children), the folders a rule refuses, and the classed
     * folders - already or now - whose template is not all there.
     */
    private record Computed(List<Row> rows, Map<Integer, FolderClass> classes, Map<Integer, Integer> assignments,
                            int classedAlready, List<Refused> refused, List<Integer> toComplete, int templateFoldersToMake) {
    }

    private Computed compute(Folder root) {
        Map<Integer, FolderClass> classes = new HashMap<>();
        folderClassRepository.findAllWithTemplates().forEach(c -> classes.put(c.getId(), c));
        List<Row> rows = jdbc.query(SUBTREE, new MapSqlParameterSource("path", root.getPath()), (rs, n) -> new Row(
                rs.getInt("id"), (Integer) rs.getObject("parent_id"), rs.getString("name"), (Integer) rs.getObject("class_id"),
                rs.getInt("depth"), rs.getString("key_path")));

        Map<Integer, Integer> effective = new HashMap<>();
        Map<Integer, Set<String>> childNames = new HashMap<>();
        Map<Integer, Integer> assignments = new LinkedHashMap<>();
        List<Refused> refused = new ArrayList<>();
        int classedAlready = 0;
        for (Row row : rows) {
            if (row.parentId() != null) {
                childNames.computeIfAbsent(row.parentId(), id -> new HashSet<>()).add(SearchKey.of(row.name(), SearchKey.NAME_LENGTH));
            }
            if (row.classId() != null) {
                effective.put(row.id(), row.classId());
                if (row.id() != root.getId()) {
                    classedAlready++;
                }
                continue;
            }
            Integer parentClass = row.id() == root.getId() ? null : effective.get(row.parentId());
            if (parentClass == null) {
                continue;
            }
            try {
                FolderClass taken = FolderClassRules.classFor(classes.get(parentClass), row.name());
                if (taken != null) {
                    effective.put(row.id(), taken.getId());
                    assignments.put(row.id(), taken.getId());
                }
            } catch (InvalidDataException refusedHere) {
                refused.add(new Refused(row.keyPath(), classes.get(parentClass).getTitle()));
            }
        }

        List<Integer> toComplete = new ArrayList<>();
        int templateFoldersToMake = 0;
        Map<Integer, Integer> sizes = new HashMap<>();
        for (Row row : rows) {
            Integer classId = effective.get(row.id());
            if (classId == null) {
                continue;
            }
            Set<String> present = childNames.getOrDefault(row.id(), Set.of());
            int missing = 0;
            for (FolderClassChild template : classes.get(classId).getChildren()) {
                if (!present.contains(SearchKey.of(template.getName(), SearchKey.NAME_LENGTH))) {
                    missing += 1 + templateSize(template.getChildClass().getId(), classes, sizes);
                }
            }
            if (missing > 0) {
                toComplete.add(row.id());
                templateFoldersToMake += missing;
            }
        }
        return new Computed(rows, classes, assignments, classedAlready, refused, toComplete, templateFoldersToMake);
    }

    /** The folders a new folder of this class makes, all the way down - templates have no cycle. */
    private static int templateSize(int classId, Map<Integer, FolderClass> classes, Map<Integer, Integer> sizes) {
        Integer known = sizes.get(classId);
        if (known != null) {
            return known;
        }
        int size = 0;
        for (FolderClassChild template : classes.get(classId).getChildren()) {
            size += 1 + templateSize(template.getChildClass().getId(), classes, sizes);
        }
        sizes.put(classId, size);
        return size;
    }

    /** One run's counts, written by its thread and read by the page. */
    private static final class Run {

        private final int folderId;
        private final String keyPath;
        private final Instant startedAt;
        private Instant finishedAt;
        private int classified;
        private int foldersToComplete;
        private List<Integer> toComplete = List.of();
        private int foldersCompleted;
        private int templateFoldersMade;
        private int failedCount;
        private final List<Failed> failed = new ArrayList<>();

        Run(int folderId, String keyPath, Instant startedAt) {
            this.folderId = folderId;
            this.keyPath = keyPath;
            this.startedAt = startedAt;
        }

        synchronized void classified(int classified, List<Integer> toComplete) {
            this.classified = classified;
            this.toComplete = List.copyOf(toComplete);
            this.foldersToComplete = toComplete.size();
        }

        synchronized void completed(int made) {
            foldersCompleted++;
            templateFoldersMade += made;
        }

        synchronized void failed(int folderId, String reason) {
            foldersCompleted++;
            failedCount++;
            if (failed.size() < LISTED) {
                failed.add(new Failed(folderId, reason));
            }
        }

        synchronized void finish(Instant at) {
            finishedAt = at;
        }

        synchronized Progress snapshot(boolean running) {
            return new Progress(folderId, keyPath, running && finishedAt == null, startedAt, finishedAt, classified,
                    foldersToComplete, foldersCompleted, templateFoldersMade, failedCount, List.copyOf(failed));
        }
    }
}
