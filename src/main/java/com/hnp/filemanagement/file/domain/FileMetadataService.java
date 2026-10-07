package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A file's metadata after its upload (roadmap 12.2): read, set where there is none, replaced,
 * cleared - through v1 and the file page.
 *
 * <p><b>Whose.</b> A document is a revision's. A file's - its current one - is its newest revision's
 * ({@link FileService#newestRevisionOf}); setting a file's sets every format of its newest version,
 * so the formats of one version never disagree, and older versions keep what they were filed with.
 *
 * <p><b>Who.</b> Reading asks {@code READ} on the file's folder, as opening the file does; writing
 * asks {@code WRITE} there, as a new version does - and the caller's permission, which the
 * controller holds. Nothing about metadata widens or narrows folder access.
 *
 * <p><b>One writer at a time.</b> The row a condition is asked of is read again under a row lock
 * ({@code SELECT ... FOR UPDATE}) before it is asked: two writers conditioned on one state - two
 * integrations filling in what is missing, two people who read one version - are serialised, and the
 * second answers to what the first wrote ({@code MetadataConcurrencyTest}).
 *
 * <p><b>How safely.</b> A write may be conditioned ({@link MetadataPrecondition}): on there being
 * none ({@code If-None-Match: *}) - what an integration filling in what is missing sends, so it never
 * overwrites what a person wrote - or on the document read ({@code If-Match}). Each change is a
 * {@code METADATA_CHANGED} event with both documents, and an audit row; a write that changes
 * nothing records nothing. The documents are never logged - a log line says how many keys.
 */
@Service
public class FileMetadataService {

    private static final Logger logger = LoggerFactory.getLogger(FileMetadataService.class);

    /** A revision's metadata, as an answer gives it. */
    public record RevisionMetadata(int fileInfoId, String fileExternalId, String fileName, int fileDetailsId,
                                   String fileDetailsExternalId, int version, String fileExtension,
                                   Optional<MetadataDocument> document) {

        public String etag() {
            return MetadataDocument.etagOf(document);
        }

        /** The document as a tree to render, or null for none. */
        public JsonNode tree() {
            return document.map(MetadataRules::treeOf).orElse(null);
        }
    }

    /** What a write did: the revisions it wrote and whether anything changed. */
    public record Written(List<RevisionMetadata> revisions, boolean changed) {

        /** The revision whose document is the file's - the newest of those written. */
        public RevisionMetadata current() {
            return revisions.getLast();
        }
    }

    private final FileInfoRepository fileInfoRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final FolderAccessService folderAccessService;
    private final FileHistoryService fileHistoryService;
    private final ActionHistoryService actionHistoryService;
    private final MetadataRules metadataRules;
    private final EntityManager entityManager;

    public FileMetadataService(FileInfoRepository fileInfoRepository, FileDetailsRepository fileDetailsRepository,
                               FolderAccessService folderAccessService, FileHistoryService fileHistoryService,
                               ActionHistoryService actionHistoryService, MetadataRules metadataRules,
                               EntityManager entityManager) {
        this.fileInfoRepository = fileInfoRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.folderAccessService = folderAccessService;
        this.fileHistoryService = fileHistoryService;
        this.actionHistoryService = actionHistoryService;
        this.metadataRules = metadataRules;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ reading

    /** A file's current metadata: its newest revision's. */
    @Transactional(readOnly = true)
    public RevisionMetadata ofFile(int fileInfoId, int principalId) {
        FileInfo file = fileWithRevisions(fileInfoId);
        folderAccessService.requireReadAccess(folderAccessService.accessFor(principalId), file);
        return answerOf(FileService.newestRevisionOf(file).orElseThrow(
                () -> new ResourceNotFoundException("file id=" + fileInfoId + " has no revision")));
    }

    /** One revision's metadata. */
    @Transactional(readOnly = true)
    public RevisionMetadata ofRevision(int fileDetailsId, int principalId) {
        FileDetails revision = revision(fileDetailsId);
        folderAccessService.requireReadAccess(folderAccessService.accessFor(principalId), revision.getFileInfo());
        return answerOf(revision);
    }

    // ------------------------------------------------------------------ writing

    /**
     * Sets the file's metadata: every format of its newest version takes the document, conditioned
     * on the file's current one (its newest revision's).
     *
     * @param sent the document as sent; {@code {}}, blank or null clears it
     */
    @Transactional
    public Written replaceOnFile(int fileInfoId, String sent, MetadataPrecondition precondition, int principalId) {
        FileInfo file = fileWithRevisions(fileInfoId);
        FolderAccess access = folderAccessService.accessFor(principalId);
        folderAccessService.requireWriteAccess(access, file);
        Optional<MetadataDocument> document = metadataRules.parse(sent);

        FileDetails newest = FileService.newestRevisionOf(file).orElseThrow(
                () -> new ResourceNotFoundException("file id=" + fileInfoId + " has no revision"));
        // Read again, held: a second writer waits here for this one to commit, then answers to it.
        entityManager.refresh(newest, LockModeType.PESSIMISTIC_WRITE);
        precondition.check(MetadataDocument.ofStored(newest.getMetadata()));
        List<FileDetails> newestVersion = file.getFileDetailsList().stream()
                .filter(each -> each.getVersion().equals(newest.getVersion()))
                .sorted(java.util.Comparator.comparing(FileDetails::getId))
                .toList();
        return write(newestVersion, document, principalId);
    }

    /**
     * Sets one revision's metadata, conditioned on that revision's.
     *
     * @param sent the document as sent; {@code {}}, blank or null clears it
     */
    @Transactional
    public Written replaceOnRevision(int fileDetailsId, String sent, MetadataPrecondition precondition, int principalId) {
        FileDetails revision = revision(fileDetailsId);
        folderAccessService.requireWriteAccess(folderAccessService.accessFor(principalId), revision.getFileInfo());
        Optional<MetadataDocument> document = metadataRules.parse(sent);
        entityManager.refresh(revision, LockModeType.PESSIMISTIC_WRITE);
        precondition.check(MetadataDocument.ofStored(revision.getMetadata()));
        return write(List.of(revision), document, principalId);
    }

    private Written write(List<FileDetails> revisions, Optional<MetadataDocument> document, int principalId) {
        String after = MetadataDocument.columnOf(document);
        boolean changed = false;
        for (FileDetails revision : revisions) {
            String before = revision.getMetadata();
            if (sameDocument(before, after)) {
                continue;
            }
            changed = true;
            revision.setMetadata(after);
            entityManager.flush();
            // Read back as jsonb wrote it: the form every later read has, and the entity tag's.
            entityManager.refresh(revision);
            String stored = revision.getMetadata();
            String detail = keysOf(before) + " -> " + keysOf(stored) + " keys";
            fileHistoryService.recordWithMetadata(FileEvent.METADATA_CHANGED, revision, detail, before, stored, principalId);
            actionHistoryService.saveActionHistory(EntityEnum.FileDetails, revision.getId(), ActionEnum.UPDATE_VALUES,
                    principalId, "CHANGE METADATA", "metadata of file details id=" + revision.getId() + ": " + detail);
            logger.info("metadata of file details id={} set: {} keys, {} bytes", revision.getId(), keysOf(stored),
                    stored == null ? 0 : new MetadataDocument(stored).bytes());
        }
        return new Written(revisions.stream().map(FileMetadataService::answerOf).toList(), changed);
    }

    /** Whether two documents are one - compared as JSON, so spacing and key order do not count. */
    private static boolean sameDocument(String a, String b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        return Objects.equals(MetadataRules.treeOf(new MetadataDocument(a)), MetadataRules.treeOf(new MetadataDocument(b)));
    }

    /** How many keys a document has at its top - what a log line or a history detail may say of it. */
    static int keysOf(String document) {
        return document == null ? 0 : MetadataRules.treeOf(new MetadataDocument(document)).size();
    }

    private static RevisionMetadata answerOf(FileDetails revision) {
        FileInfo file = revision.getFileInfo();
        return new RevisionMetadata(file.getId(), file.getExternalId(), file.getFileName(), revision.getId(), revision.getExternalId(),
                revision.getVersion(), revision.getFileExtension(), MetadataDocument.ofStored(revision.getMetadata()));
    }

    private FileInfo fileWithRevisions(int fileInfoId) {
        return fileInfoRepository.findByIdAndFetchFileDetails(fileInfoId).orElseThrow(
                () -> new ResourceNotFoundException("file info not exists, id=" + fileInfoId));
    }

    private FileDetails revision(int fileDetailsId) {
        return fileDetailsRepository.findByIdWithFileInfo(fileDetailsId).orElseThrow(
                () -> new ResourceNotFoundException("file details with id=" + fileDetailsId + " not exists"));
    }
}
