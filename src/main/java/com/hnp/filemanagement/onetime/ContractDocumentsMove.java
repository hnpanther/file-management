package com.hnp.filemanagement.onetime;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>A one-off, removed in the release after 2.16.1</b> - with {@link ContractDocumentsController}, its page
 * ({@code settings/contract-documents.html}), its {@code contractDocuments.*} messages and its test.
 *
 * <p>The ERP files a contract's documents through n8n as {@code <root>/<company>/Contract/<number>/<file>}; a
 * contract's statements sit beside them in {@code ContractState/}. So a contract folder mixed two audiences,
 * and folder access - a grant covers everything beneath it - could not give one without the other. Since
 * 2026-10-17 n8n writes to {@code <number>/Documents/<file>}; this moves what it wrote before the same way:
 * every file directly in a contract folder into that contract's {@code Documents}, created where it is missing.
 * A contract with no file of its own is not touched, and nothing below a contract is.
 *
 * <p>Each file goes through {@link FileService#moveFile} - its checks, its history event, its queue settled;
 * no byte and no stored key moves. A file whose name {@code Documents} already holds is left where it is and
 * listed. One contract is one transaction: a failure leaves that contract as it was and the run goes on, and a
 * second run finds only what the first did not move. The run is a thread of its own; its progress is kept in
 * memory and lost with a restart, which loses nothing else.
 */
@Service
public class ContractDocumentsMove {

    private static final Logger logger = LoggerFactory.getLogger(ContractDocumentsMove.class);

    /** The folder under each company that holds its contracts, and the one a contract's own files go into. */
    public static final String CONTAINER = "Contract";
    public static final String TARGET = "Documents";

    /** At most this many skipped files and failed contracts are kept to be shown; all are counted. */
    static final int LISTED = 500;

    /**
     * The contracts under the root ({@code <root>/<company>/Contract/<contract>}), each file of their own, and the
     * contract's {@code Documents} when it has one - names compared in upper case, as siblings' are.
     */
    private static final String PLAN = """
            SELECT COUNT(DISTINCT c.id) AS contracts,
                   COUNT(DISTINCT c.id) FILTER (WHERE fi.id IS NOT NULL) AS contracts_with_files,
                   COUNT(DISTINCT c.id) FILTER (WHERE fi.id IS NOT NULL AND d.id IS NULL) AS to_create,
                   COUNT(fi.id) AS files,
                   COUNT(fi.id) FILTER (WHERE d.id IS NOT NULL) AS files_into_existing,
                   COALESCE(SUM(CASE WHEN EXISTS (SELECT 1 FROM file_info x
                                                  WHERE x.folder_id = d.id AND x.search_name = fi.search_name)
                                     THEN 1 ELSE 0 END), 0) AS clashes
            FROM folder co
            JOIN folder k ON k.parent_id = co.id AND UPPER(k.name) = UPPER(:container)
            JOIN folder c ON c.parent_id = k.id
            LEFT JOIN file_info fi ON fi.folder_id = c.id
            LEFT JOIN folder d ON d.parent_id = c.id AND UPPER(d.name) = UPPER(:target)
            WHERE co.parent_id = :root
            """;

    private static final String CLASHES = """
            SELECT c.key_path, fi.file_name
            FROM folder co
            JOIN folder k ON k.parent_id = co.id AND UPPER(k.name) = UPPER(:container)
            JOIN folder c ON c.parent_id = k.id
            JOIN file_info fi ON fi.folder_id = c.id
            JOIN folder d ON d.parent_id = c.id AND UPPER(d.name) = UPPER(:target)
            WHERE co.parent_id = :root
              AND EXISTS (SELECT 1 FROM file_info x WHERE x.folder_id = d.id AND x.search_name = fi.search_name)
            ORDER BY c.key_path COLLATE "C", fi.file_name
            LIMIT :limit
            """;

    private static final String CONTRACTS_WITH_FILES = """
            SELECT c.id
            FROM folder co
            JOIN folder k ON k.parent_id = co.id AND UPPER(k.name) = UPPER(:container)
            JOIN folder c ON c.parent_id = k.id
            WHERE co.parent_id = :root
              AND EXISTS (SELECT 1 FROM file_info fi WHERE fi.folder_id = c.id)
            ORDER BY c.id
            """;

    private static final String FILES_OF = """
            SELECT fi.id, fi.file_name, c.key_path
            FROM file_info fi JOIN folder c ON c.id = fi.folder_id
            WHERE fi.folder_id = :contract
            ORDER BY fi.id
            """;

    private final FolderRepository folderRepository;
    private final FolderService folderService;
    private final FileService fileService;
    private final ActionHistoryService actionHistoryService;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Run last;

    public ContractDocumentsMove(FolderRepository folderRepository, FolderService folderService, FileService fileService,
                                 ActionHistoryService actionHistoryService, NamedParameterJdbcTemplate jdbc,
                                 TransactionTemplate transactions, Clock clock) {
        this.folderRepository = folderRepository;
        this.folderService = folderService;
        this.fileService = fileService;
        this.actionHistoryService = actionHistoryService;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** What a run would do under the folder this path names. Nothing is written. */
    public Plan preview(String rootPath) {
        Folder root = resolve(rootPath);
        MapSqlParameterSource parameters = parameters(root.getId()).addValue("limit", LISTED);
        List<Skipped> clashes = jdbc.query(CLASHES, parameters,
                (rs, n) -> new Skipped(rs.getString("key_path"), rs.getString("file_name")));
        return jdbc.queryForObject(PLAN, parameters, (rs, n) -> new Plan(root.getKeyPath(),
                rs.getInt("contracts"), rs.getInt("contracts_with_files"), rs.getInt("to_create"),
                rs.getInt("files"), rs.getInt("files_into_existing"), rs.getInt("clashes"), clashes));
    }

    /**
     * Starts a run on a thread of its own, unless one is running: false then, and nothing is started. The
     * folder is resolved first, so a wrong path is refused here, not in the thread.
     */
    public boolean start(String rootPath, int principalId) {
        Folder root = resolve(rootPath);
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            transactions.executeWithoutResult(status -> actionHistoryService.saveActionHistory(EntityEnum.Folder,
                    root.getId(), ActionEnum.UPDATE_VALUES, principalId, "MOVE CONTRACT FILES",
                    "MOVE the files of each contract under folder id=" + root.getId() + " into its " + TARGET));
            Thread.ofPlatform().name("contract-documents-move").daemon(true).start(() -> {
                try {
                    run(root.getId(), root.getKeyPath(), principalId);
                } catch (RuntimeException e) {
                    logger.error("contract documents move under folder id={} stopped", root.getId(), e);
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

    /** The run itself, in the calling thread: one transaction per contract. */
    Progress run(int rootId, String rootTitle, int principalId) {
        List<Integer> contracts = jdbc.queryForList(CONTRACTS_WITH_FILES, parameters(rootId), Integer.class);
        Run run = new Run(rootTitle, contracts.size(), Instant.now(clock));
        last = run;
        logger.info("contract documents move under folder id={}: {} contract(s) with files of their own",
                rootId, contracts.size());
        for (int contractId : contracts) {
            try {
                run.done(transactions.execute(status -> moveOne(contractId, principalId)));
            } catch (RuntimeException e) {
                logger.warn("contract documents move: contract folder id={} left as it was: {}", contractId,
                        e.getClass().getSimpleName());
                run.failed(contractId, e.getClass().getSimpleName());
            }
            if (run.contractsDone() % 100 == 0) {
                logger.info("contract documents move: {} of {} contract(s), {} file(s) moved",
                        run.contractsDone(), contracts.size(), run.filesMoved());
            }
        }
        run.finish(Instant.now(clock));
        Progress result = run.snapshot(false);
        logger.info("contract documents move under folder id={} finished: {} file(s) moved, {} folder(s) created,"
                        + " {} file(s) left for a name already taken, {} contract(s) failed", rootId,
                result.filesMoved(), result.foldersCreated(), result.skippedCount(), result.failedCount());
        return result;
    }

    private Moved moveOne(int contractId, int principalId) {
        List<Loose> files = jdbc.query(FILES_OF, new MapSqlParameterSource("contract", contractId),
                (rs, n) -> new Loose(rs.getInt("id"), rs.getString("file_name"), rs.getString("key_path")));
        if (files.isEmpty()) {
            return new Moved(false, 0, List.of());
        }
        Folder existing = folderRepository.findByParentIdAndNameIgnoreCase(contractId, TARGET).orElse(null);
        int targetId = existing != null ? existing.getId()
                : folderService.create(contractId, TARGET, TARGET, null, null, principalId).id();

        int moved = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (Loose file : files) {
            if (fileService.isDuplicate(file.name(), targetId)) {
                skipped.add(new Skipped(file.contract(), file.name()));
                continue;
            }
            fileService.moveFile(file.id(), targetId, principalId);
            moved++;
        }
        return new Moved(existing == null, moved, skipped);
    }

    /** The folder named by a path of names below the root, each compared as siblings' names are (upper case). */
    Folder resolve(String rootPath) {
        String path = rootPath == null ? "" : rootPath.strip();
        Folder folder = folderRepository.findFirstByKind(FolderKind.ROOT)
                .orElseThrow(() -> new IllegalStateException("there is no root folder"));
        boolean named = false;
        for (String name : path.split("/")) {
            if (name.isBlank()) {
                continue;
            }
            int parentId = folder.getId();
            folder = folderRepository.findByParentIdAndNameIgnoreCase(parentId, name.strip()).orElseThrow(
                    () -> new InvalidDataException("no folder at the path given", "contractDocuments.invalid.root", path));
            named = true;
        }
        if (!named) {
            throw new InvalidDataException("no path given", "contractDocuments.invalid.root", path);
        }
        return folder;
    }

    private static MapSqlParameterSource parameters(int rootId) {
        return new MapSqlParameterSource().addValue("root", rootId).addValue("container", CONTAINER)
                .addValue("target", TARGET);
    }

    /**
     * What a run would do: the contracts under the root, those with files of their own, the {@code Documents}
     * folders it would create, the files - those going into a {@code Documents} already there and those into one
     * it makes - and those it would leave, their name already in {@code Documents} (the first {@value #LISTED}
     * listed).
     */
    public record Plan(String root, int contracts, int contractsWithFiles, int foldersToCreate, int files,
                       int filesIntoExisting, int clashes, List<Skipped> clashesListed) {

        /** Contracts with files of their own whose {@code Documents} is already there: their files are only moved. */
        public int contractsWithDocuments() {
            return contractsWithFiles - foldersToCreate;
        }

        /** Files moved into a {@code Documents} already there - a name it holds stays where it is. */
        public int filesIntoExistingToMove() {
            return filesIntoExisting - clashes;
        }

        /** Files moved into a {@code Documents} made for them. */
        public int filesIntoNew() {
            return files - filesIntoExisting;
        }

        public int filesToMove() {
            return files - clashes;
        }
    }

    /** A file left in its contract: {@code Documents} there already holds its name. */
    public record Skipped(String contract, String fileName) {
    }

    /** A contract the run could not change, and why - the exception's class, nothing a person wrote. */
    public record Failed(int contractFolderId, String reason) {
    }

    /**
     * A run's progress, as the page shows it. {@code stopped}: not running and not finished - its thread
     * ended on an error (in the log).
     */
    public record Progress(String root, boolean running, Instant startedAt, Instant finishedAt,
                           int contracts, int contractsDone, int filesMoved, int foldersCreated,
                           int skippedCount, List<Skipped> skipped, int failedCount, List<Failed> failed) {

        public boolean stopped() {
            return !running && finishedAt == null;
        }
    }

    private record Loose(int id, String name, String contract) {
    }

    private record Moved(boolean created, int moved, List<Skipped> skipped) {
    }

    /** One run's counts, written by its thread and read by the page. */
    private static final class Run {

        private final String root;
        private final int contracts;
        private final Instant startedAt;
        private Instant finishedAt;
        private int contractsDone;
        private int filesMoved;
        private int foldersCreated;
        private int skippedCount;
        private final List<Skipped> skipped = new ArrayList<>();
        private int failedCount;
        private final List<Failed> failed = new ArrayList<>();

        Run(String root, int contracts, Instant startedAt) {
            this.root = root;
            this.contracts = contracts;
            this.startedAt = startedAt;
        }

        synchronized void done(Moved contract) {
            contractsDone++;
            filesMoved += contract.moved();
            foldersCreated += contract.created() ? 1 : 0;
            skippedCount += contract.skipped().size();
            for (Skipped one : contract.skipped()) {
                if (skipped.size() < LISTED) {
                    skipped.add(one);
                }
            }
        }

        synchronized void failed(int contractId, String reason) {
            contractsDone++;
            failedCount++;
            if (failed.size() < LISTED) {
                failed.add(new Failed(contractId, reason));
            }
        }

        synchronized int contractsDone() {
            return contractsDone;
        }

        synchronized int filesMoved() {
            return filesMoved;
        }

        synchronized void finish(Instant at) {
            finishedAt = at;
        }

        synchronized Progress snapshot(boolean running) {
            return new Progress(root, running && finishedAt == null, startedAt, finishedAt, contracts, contractsDone,
                    filesMoved, foldersCreated, skippedCount, List.copyOf(skipped), failedCount, List.copyOf(failed));
        }
    }
}
