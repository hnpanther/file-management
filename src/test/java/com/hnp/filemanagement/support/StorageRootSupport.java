package com.hnp.filemanagement.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Base class for tests that touch the file storage root.
 * <p>
 * The tests in this project create {@code ${filemanagement.base-dir}} themselves in their own
 * {@code @BeforeEach} and delete it in their own {@code @AfterEach}. That only works if the
 * directory is absent to begin with, so a crashed or interrupted run used to leave the whole suite
 * failing until the directory was removed by hand.
 * <p>
 * JUnit runs a superclass {@code @BeforeEach} before the subclass one and a superclass
 * {@code @AfterEach} after the subclass one, so the hooks here guarantee a clean root on the way in
 * and no leftovers on the way out, without any subclass having to care.
 */
public abstract class StorageRootSupport {

    private static final Logger log = LoggerFactory.getLogger(StorageRootSupport.class);

    @Value("${filemanagement.base-dir}")
    private String storageRoot;

    @BeforeEach
    void clearStorageRootBeforeTest() {
        Path root = Paths.get(storageRoot);
        deleteRecursively(root);
        // Recreated empty, because the storage layer creates directories one level at a time and
        // refuses to create a child whose parent is missing. Tests used to do this themselves in
        // their own @BeforeEach, which is why every one of them had to remember to.
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + root, e);
        }
    }

    @AfterEach
    void clearStorageRootAfterTest() {
        deleteRecursively(Paths.get(storageRoot));
    }

    /** How long a file still held open may keep the root from being cleared. */
    private static final Duration PATIENCE = Duration.ofSeconds(2);

    /**
     * Deletes {@code root} and everything under it. Does nothing when it does not exist.
     *
     * <p>Patient for a moment: a test on a port has its answers sent by the server's threads, and
     * the client can have the last byte of a download before that thread has closed the file - on
     * Windows a file still open cannot be deleted (a virus scanner reading a file just written holds
     * it the same way). A file still held after {@link #PATIENCE} is a real leak, and fails as before.
     */
    protected static void deleteRecursively(Path root) {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (true) {
            try {
                deleteOnce(root);
                return;
            } catch (UncheckedIOException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                log.debug("storage root not cleared yet, retrying: {}", e.getMessage());
                try {
                    Thread.sleep(50);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void deleteOnce(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("could not delete " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk " + root, e);
        }
        log.debug("cleared storage root {}", root);
    }
}
