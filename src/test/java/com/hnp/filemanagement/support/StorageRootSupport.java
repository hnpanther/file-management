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
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Base class for tests that touch the file storage root.
 * <p>
 * The tests in this project create {@code ${file.management.base-dir}} themselves in their own
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

    @Value("${file.management.base-dir}")
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

    /** Deletes {@code root} and everything under it. Does nothing when it does not exist. */
    protected static void deleteRecursively(Path root) {
        if (Files.notExists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
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
