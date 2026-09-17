package com.hnp.filemanagement.config.bootstrap;

import com.hnp.filemanagement.repository.FileInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Says at start-up how far the data is from Phase 7 step 4 - the migration that makes
 * {@code file_info.folder_id} mandatory, drops the taxonomy tables and puts a unique constraint
 * over (folder, name). Each figure is one of the pre-flight queries {@code docs/deployment.md}
 * lists for the operator, and the same ones the test suite asks on every build; here they are
 * asked once per start against the real data, so that the answer is in the log before anyone
 * decides.
 *
 * <p>All zeros is the only acceptable answer and is logged at INFO. Anything else is a WARN
 * naming the figure, and nothing is changed: the backfill statements in {@code V2.3} and
 * {@code V2.4} are re-runnable, and a shared name is a decision for a person.
 */
@Component
public class FolderReadinessReport {

    private static final Logger logger = LoggerFactory.getLogger(FolderReadinessReport.class);

    private final FileInfoRepository fileInfoRepository;

    public FolderReadinessReport(FileInfoRepository fileInfoRepository) {
        this.fileInfoRepository = fileInfoRepository;
    }

    /** The three figures, all of which must be zero before step 4. */
    public record Figures(long filesWithoutFolder, long filesWhoseFolderDisagrees,
                          long filesWhoseTagsDisagree, long namesSharedWithinAFolder) {
        public boolean ready() {
            return filesWithoutFolder == 0 && filesWhoseFolderDisagrees == 0
                    && filesWhoseTagsDisagree == 0 && namesSharedWithinAFolder == 0;
        }
    }

    @Transactional(readOnly = true)
    public Figures figures() {
        return new Figures(
                fileInfoRepository.countByFolderIsNull(),
                fileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror().size(),
                fileInfoRepository.findIdsWhoseTagsDisagreeWithTheTaxonomy().size(),
                fileInfoRepository.findFileNamesSharedWithinAFolder().size());
    }

    public void report() {
        Figures figures = figures();
        if (figures.ready()) {
            logger.info("Phase 7 step 4 readiness: every file has its folder and its tags, and no folder holds two files of one name");
            return;
        }
        logger.warn("Phase 7 step 4 readiness: NOT ready - files without a folder={}, files whose folder is not their tag's mirror={}, "
                        + "files whose tags disagree with the taxonomy={}, names shared within a folder={}. "
                        + "See docs/deployment.md, 'Readiness for Phase 7 step 4'.",
                figures.filesWithoutFolder(), figures.filesWhoseFolderDisagrees(),
                figures.filesWhoseTagsDisagree(), figures.namesSharedWithinAFolder());
    }
}
