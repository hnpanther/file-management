package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every night, the class paths made true where they are not (2.17.2) - one statement, touching only the rows that
 * disagree, under {@link FolderClassLock} so that no folder is made meanwhile. {@link ClassPathRepair} does the same
 * at a start; this is for what a running service could get wrong: nothing should, so a row set here is logged as a
 * warning - a way of writing folders that does not keep {@code class_path} - and access is right again by morning.
 */
@Component
public class ClassPathCheck {

    private static final Logger logger = LoggerFactory.getLogger(ClassPathCheck.class);

    private final FolderRepository folderRepository;
    private final FolderClassLock folderClassLock;
    private final TransactionTemplate transactions;

    public ClassPathCheck(FolderRepository folderRepository, FolderClassLock folderClassLock, TransactionTemplate transactions) {
        this.folderRepository = folderRepository;
        this.folderClassLock = folderClassLock;
        this.transactions = transactions;
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "${filemanagement.time-zone:Asia/Tehran}")
    public void scheduledRun() {
        try {
            repair();
        } catch (RuntimeException e) {
            logger.error("checking the class paths failed; tomorrow's run tries again", e);
        }
    }

    /** Sets what disagrees; returns how many folders were set. */
    public int repair() {
        Integer repaired = transactions.execute(status -> {
            folderClassLock.exclusive();
            return folderRepository.repairClassPaths();
        });
        int count = repaired == null ? 0 : repaired;
        if (count > 0) {
            logger.warn("set the class path of {} folder(s) that disagreed with the classes above them - something wrote"
                    + " folders without keeping it; access by class was wrong for them until now", count);
        }
        return count;
    }
}
