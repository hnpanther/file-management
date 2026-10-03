package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.shared.exception.BusinessException;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.shared.exception.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The local filesystem as a {@link BlobStore} (roadmap 2.2) - the default backend; the other is
 * {@link S3BlobStore}, and {@link BlobStoreConfig} makes whichever one is configured.
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
 *
 * <p><b>For the storage copy</b> ({@link CopyableStore}, roadmap 4.4): {@link #copyIn} writes to a
 * temporary name beside the target and renames it into place only once the bytes hash to what was
 * expected - so the key holds the whole, verified file or nothing, even if the process is killed
 * half-way. A temporary file a killed copy leaves ({@code .{name}.{uuid}.copying}) is listed like
 * any other object, for the copy's prune to remove; no row ever names one.
 */
public class FilesystemBlobStore implements CopyableStore {

    /** The suffix of {@link #copyIn}'s temporary file beside its target. */
    static final String COPYING_SUFFIX = ".copying";

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
            throw new StorageUnavailableException("error in saving file, check logs");
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
            throw new StorageUnavailableException("can not delete file=" + target + ", please check logs");
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
            throw new StorageUnavailableException("can not delete directory=" + directory + ", please check logs");
        }
    }

    // ---------------------------------------------------------------- CopyableStore

    /** Its length only: a file's checksum is known by reading it. */
    @Override
    public Optional<ObjectFacts> facts(StorageKey key) {
        Path target = within(key.value());
        try {
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
            return attributes.isRegularFile()
                    ? Optional.of(new ObjectFacts(attributes.size(), null, null, null))
                    : Optional.empty();
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            logger.error("facts key=" + key + " failed", e);
            throw new StorageUnavailableException("can not read the attributes of " + key + ", please check logs");
        }
    }

    @Override
    public CopiedObject copyIn(StorageKey key, InputStream data, String expectedSha256) {
        Path target = within(key.value());
        if (Files.exists(target)) {
            throw new DuplicateResourceException("file already exists=" + target);
        }
        Path temporary = null;
        try (InputStream in = data) {
            Files.createDirectories(target.getParent());
            temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + COPYING_SUFFIX);
            MessageDigest digest = sha256();
            long size;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
                size = in.transferTo(out);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!actual.equals(expectedSha256)) {
                deleteQuietly(temporary);
                throw new ChecksumMismatchException(key, expectedSha256, actual, size);
            }
            // No REPLACE_EXISTING: a file that appeared at the key meanwhile is refused, never replaced.
            Files.move(temporary, target);
            return new CopiedObject(size, actual, null);
        } catch (FileAlreadyExistsException e) {
            deleteQuietly(temporary);
            throw new DuplicateResourceException("file already exists=" + target);
        } catch (IOException e) {
            logger.error("copyIn key=" + key + " failed", e);
            if (temporary != null) {
                deleteQuietly(temporary);
            }
            throw new StorageUnavailableException("error in copying file " + key + ", check logs");
        }
    }

    @Override
    public void forEachObject(Consumer<ListedObject> consumer) {
        if (!Files.isDirectory(root)) {
            throw new StorageUnavailableException("the storage root " + root + " is not a directory");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.forEach(path -> {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                    if (!attributes.isRegularFile()) {
                        return;
                    }
                    String relative = root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                    StorageKey key;
                    try {
                        key = StorageKey.of(relative);
                    } catch (BusinessException notAKey) {
                        // No row can name it, and no key can reach it to delete it: left alone, and said.
                        logger.warn("listing: {} is not a storage key and is left out: {}", path, notAKey.getMessage());
                        return;
                    }
                    consumer.accept(new ListedObject(key, attributes.size(), attributes.lastModifiedTime().toInstant()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            logger.error("listing " + root + " failed", e);
            throw new StorageUnavailableException("can not list the storage root " + root + ", please check logs");
        }
    }

    /**
     * Whether the storage root is a directory this process may write to - for the readiness check
     * (issue 104). A share that is not mounted, a root that was never created, one made read-only:
     * every file would then be missing or unwritable, so the instance is not ready.
     */
    public boolean rootUsable() {
        boolean usable = Files.isDirectory(root) && Files.isWritable(root);
        if (!usable) {
            logger.warn("the storage root {} is not a writable directory", root);
        }
        return usable;
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
