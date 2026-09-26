package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The local filesystem as a {@link BlobStore} - today the only implementation (roadmap 2.2).
 *
 * <p>It replaces {@code FileStorageFileSystemService}, whose interface had two shapes: a
 * key-shaped half that every read and write of a stored object already went through, and a
 * path-shaped half that built file names out of a directory, a version and an extension. The
 * second had no caller left in the application once files became addressed by key (roadmap 7.1),
 * and it is what an object store could never implement - so it is gone, and what remains is one
 * shape that a second store can satisfy.
 *
 * <p><b>The boundary.</b> {@link #within} is the one place a key becomes an absolute path: the
 * root is resolved and normalised, the key is resolved beneath it and normalised - which is what
 * folds {@code ..} - and the result must still start with the root and must not be the root
 * itself. Anything else is refused before a filesystem call, whatever the caller spelled
 * ({@code docs/issues.md} issue 16). {@link StorageKey} refuses the same spellings a step
 * earlier, on the key's own shape; both checks stay, because a key can be well-formed and still
 * name somewhere it may not.
 *
 * <p><b>The root's trailing separator</b> is no longer load-bearing: nothing here concatenates a
 * path any more, it resolves. The normalisation is kept for the messages, and because a root
 * spelled both ways must mean one directory - the first production deployment of 1.1.0 found out
 * what happens when the two halves disagree.
 */
@Service
public class FilesystemBlobStore implements BlobStore {

    private static final Logger logger = LoggerFactory.getLogger(FilesystemBlobStore.class);

    private final Path root;

    public FilesystemBlobStore(FileManagementProperties properties) {
        this.root = Paths.get(properties.baseDir()).toAbsolutePath().normalize();
    }

    @Override
    public StoredBlob put(StorageKey key, InputStream data) {
        if (data == null) {
            throw new BusinessException("can not save null file!");
        }
        Path target = within(key.value());
        logger.debug("put key={}", key);

        if (Files.exists(target)) {
            // Never overwrite. A revision is immutable here, so an existing object at the key
            // means the caller believes it is writing something new and is not.
            throw new DuplicateResourceException("file already exists=" + target);
        }

        MessageDigest digest = sha256();
        long size;
        try (InputStream in = data) {
            Files.createDirectories(target.getParent());
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(target), digest)) {
                size = in.transferTo(out);
            }
        } catch (IOException e) {
            logger.error("put key=" + key + " failed", e);
            // A half-written object is worse than none: the key would then be taken, and the
            // next attempt would be refused as a duplicate of something unreadable.
            deleteQuietly(target);
            throw new BusinessException("error in saving file, check logs");
        }
        return new StoredBlob(key, size, HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT));
    }

    @Override
    public Resource open(StorageKey key) {
        Path target = within(key.value());
        logger.debug("open key={}", key);

        if (!Files.exists(target)) {
            throw new ResourceNotFoundException("file not found");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            logger.debug("open key=" + key + " failed", e);
            throw new BusinessException("can not load file, please check logs");
        }
    }

    @Override
    public boolean exists(StorageKey key) {
        return Files.exists(within(key.value()));
    }

    @Override
    public void delete(StorageKey key) {
        Path target = within(key.value());
        logger.debug("delete key={}", key);

        if (!Files.exists(target)) {
            throw new ResourceNotFoundException("file not found, file=" + target);
        }
        try {
            Files.delete(target);
        } catch (IOException e) {
            logger.error("delete key=" + key + " failed", e);
            throw new BusinessException("can not delete file=" + target + ", please check logs");
        }
    }

    @Override
    public void deleteDirectory(String prefix) {
        Path directory = within(prefix);
        if (!Files.exists(directory)) {
            throw new ResourceNotFoundException("directory not exists=" + directory);
        }
        // Deepest first, so a directory is empty by the time its own turn comes.
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            logger.error("deleteDirectory " + directory + " failed", e);
            throw new BusinessException("can not delete directory=" + directory + ", please check logs");
        }
    }

    /**
     * The one place a relative key becomes an absolute path, and the one place a path that would
     * leave the root is refused.
     */
    private Path within(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new BusinessException("storage path is empty");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("storage path escapes the storage root: " + relative);
        }
        if (target.equals(root)) {
            throw new BusinessException("storage path names the storage root itself: " + relative);
        }
        return target;
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            logger.warn("could not remove the half-written {}", target);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; this cannot happen and must not be swallowed if it does.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
