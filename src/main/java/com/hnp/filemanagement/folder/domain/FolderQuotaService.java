package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The quota a folder may carry ({@code folder.quota_bytes}, {@code V2.11}, roadmap 10.4): a cap
 * on the total size of every revision of every file anywhere beneath it.
 *
 * <p><b>Where it is asked.</b> Before anything is inserted, on every path that adds bytes under a
 * folder: a new file, a new version, a new format, a file moved in, a folder moved in. Every
 * folder on the way up from the target - the target itself included, the root excluded - that
 * carries a quota must have room for what is coming: {@code used + incoming <= quota}. A move
 * <em>within</em> a quota's subtree adds nothing to it, so a quota folder the source already sits
 * under is skipped; a move out never asks.
 *
 * <p><b>Usage is computed, not kept.</b> {@code SUM(file_details.file_size)} over the subtree,
 * each time. A maintained counter would have to follow every upload, delete, move and format
 * change and be right after every one of them, and a counter that has drifted is worse than a
 * query that is slower; the index on {@code folder.path} is what the sum runs over.
 */
@Service
public class FolderQuotaService {

    private static final Logger logger = LoggerFactory.getLogger(FolderQuotaService.class);

    private final FolderRepository folderRepository;
    private final FileDetailsRepository fileDetailsRepository;

    public FolderQuotaService(FolderRepository folderRepository, FileDetailsRepository fileDetailsRepository) {
        this.folderRepository = folderRepository;
        this.fileDetailsRepository = fileDetailsRepository;
    }

    /** The bytes stored beneath a folder, itself included. */
    public long usageOf(Folder folder) {
        return fileDetailsRepository.sumSizeUnder(folder.getPath());
    }

    /**
     * Refuses the bytes if any quota above the target has no room for them.
     *
     * @param target        the folder the bytes are going into
     * @param incomingBytes how many
     * @throws QuotaExceededException 409, naming the folder, its quota, its usage and the size
     */
    public void requireRoom(Folder target, long incomingBytes) {
        requireRoom(target, incomingBytes, null);
    }

    /**
     * The same, for a move: a quota folder the source already sits under gains nothing.
     *
     * @param sourcePath the path of the file's folder, or of the folder being moved; null for an upload
     */
    public void requireRoom(Folder target, long incomingBytes, String sourcePath) {
        if (incomingBytes <= 0) {
            return;
        }
        for (Folder quotaFolder : quotaFoldersAbove(target)) {
            if (sourcePath != null && sourcePath.startsWith(quotaFolder.getPath())) {
                continue;
            }
            long quota = quotaFolder.getQuotaBytes();
            long used = usageOf(quotaFolder);
            if (used + incomingBytes > quota) {
                logger.info("quota refused: folder={} quota={} used={} incoming={}",
                        quotaFolder.getId(), quota, used, incomingBytes);
                throw new QuotaExceededException(quotaFolder, quota, used, incomingBytes);
            }
        }
    }

    /**
     * The folders on the way up from the target that carry a quota, the target included, nearest
     * first. One query over the path's ids; the root never carries one.
     */
    List<Folder> quotaFoldersAbove(Folder target) {
        List<Integer> ids = FolderService.idsIn(target.getPath());
        return folderRepository.findAllById(ids).stream()
                .filter(folder -> folder.getQuotaBytes() != null)
                .sorted((a, b) -> Integer.compare(b.getDepth(), a.getDepth()))
                .toList();
    }
}
