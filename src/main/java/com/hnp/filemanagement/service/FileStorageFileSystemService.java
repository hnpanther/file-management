package com.hnp.filemanagement.service;

import com.hnp.filemanagement.exception.BusinessException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * The disk implementation of {@link FileStorageService}, and today the only one.
 *
 * <p>Layout under {@code base-dir} is
 * {@code {category}/{subCategory}/{fileName}/v{n}/{fileName}.{extension}} — a version is a
 * <em>directory</em>, and the stored file inside it keeps the original name. (This comment used to
 * say the opposite, that a revision was stored as {@code <name>-v<version>.<extension>} and that a
 * version was a file name rather than a directory. It never was: {@code save} has always built
 * {@code level2Dir = level1Dir + "/v" + version}.)
 *
 * <p>Every path this class touches goes through {@link #within}: the root is resolved to an
 * absolute, normalised path once, the relative part is resolved beneath it and normalised, and a
 * result that is not still under the root - or that <em>is</em> the root - is refused before any
 * filesystem call. That is the containment check issue 16 asked for, in one place, for both
 * halves of the class; it does not depend on how a name is spelled, which is why it sits
 * alongside the spelling rules ({@code checkCorrectFileName}, {@code checkCorrectDirectoryName})
 * rather than replacing them. The spelling rules still reject rather than sanitise, and the
 * address rule is applied per segment, which is what the guard issue 4 described was meant to do.
 *
 * <p>{@code base-dir} is still concatenated, not resolved, by the path-shaped half. The
 * constructor appends a separator when the configured value lacks one, so {@code E:\files\main}
 * and {@code E:\files\main\} mean the same directory - they did not, and the first production
 * deployment of 1.1.0 found out: uploads went through the key-shaped half, which resolves, into
 * {@code main\IMS\...}, while a delete went through this half into {@code mainIMS/...} and
 * answered 404. The key-shaped methods added for roadmap 7.1 need no separator convention and no
 * spelling rule; the path-shaped half goes with the taxonomy in Phase 7 step 4.
 */
@Service("fileSystem")
@Primary
public class FileStorageFileSystemService implements FileStorageService {

    private final Logger logger = LoggerFactory.getLogger(FileStorageFileSystemService.class);

    private final String baseDir;


    public FileStorageFileSystemService(@Value("${file.management.base-dir}") String baseDir) {
        this.baseDir = withTrailingSeparator(baseDir);
    }

    /**
     * The path-shaped methods below build paths by string concatenation, so the root must end with
     * a separator or the first path segment fuses with the directory name. The key-shaped methods
     * resolve and do not care either way. Making the two halves agree here is what keeps a file
     * that one half stored findable by the other, whatever the operator typed.
     */
    static String withTrailingSeparator(String baseDir) {
        if (baseDir == null || baseDir.isEmpty()) {
            return baseDir;
        }
        char last = baseDir.charAt(baseDir.length() - 1);
        return last == '/' || last == '\\' ? baseDir : baseDir + java.io.File.separator;
    }

    // ---------------------------------------------------------------- the boundary

    /**
     * The one place a relative path becomes an absolute one. The root is resolved to an absolute,
     * normalised path, the relative part is resolved beneath it and normalised - which is what
     * folds {@code ..} - and the result must still start with the root and must not be the root
     * itself. Anything else is refused here, before a filesystem call, whatever the caller spelled.
     *
     * <p>{@code resolve} joins with a separator whether or not the root ends with one, so nothing
     * here depends on the trailing-separator convention the string-built messages still follow.
     */
    private Path within(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new BusinessException("storage path is empty");
        }
        Path root = Paths.get(baseDir).toAbsolutePath().normalize();
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("storage path escapes the storage root: " + relative);
        }
        if (target.equals(root)) {
            throw new BusinessException("storage path names the storage root itself: " + relative);
        }
        return target;
    }

    /**
     * An address is {@code {category}/{subCategory}[/{fileName}]}: every segment must be a
     * directory name by the same rule the taxonomy services apply when they create one. Empty
     * segments (a doubled or trailing slash) are tolerated, since {@link #within} normalises them
     * away; a segment with a dot or a space in it is not.
     */
    private void requireCorrectAddress(String address) {
        if (address == null) {
            throw new BusinessException("address is null");
        }
        for (String segment : address.split("/")) {
            if (!segment.isEmpty() && !checkCorrectDirectoryName(segment)) {
                throw new BusinessException("character '.' and space and '/' not allow in directory name, address=" + address);
            }
        }
    }

    // ---------------------------------------------------------------- key-shaped (roadmap 7.1)

    /** A storage key is a relative path with no spelling rule of its own; containment is the test. */
    private Path resolveKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException("storage key is empty");
        }
        return within(storageKey);
    }

    @Override
    public void saveByKey(String storageKey, MultipartFile file) {
        if (file == null) {
            throw new BusinessException("can not save null file!");
        }
        Path target = resolveKey(storageKey);
        logger.debug("FileStorageFileSystemService.saveByKey() -> saving key={}", storageKey);

        if (Files.exists(target)) {
            // Never overwrite. A version is immutable here, so an existing object at the key means
            // the caller believes it is writing something new and is not.
            throw new DuplicateResourceException("file already exists=" + target);
        }

        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target);
        } catch (IOException e) {
            logger.error("FileStorageFileSystemService.saveByKey() -> IOException for key=" + storageKey, e);
            throw new BusinessException("error in saving file, check logs");
        }
    }

    @Override
    public Resource loadByKey(String storageKey) {
        Path target = resolveKey(storageKey);
        logger.debug("FileStorageFileSystemService.loadByKey() -> loading key={}", storageKey);

        if (!Files.exists(target)) {
            throw new ResourceNotFoundException("file not found");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            logger.debug("FileStorageFileSystemService.loadByKey() -> can not load key=" + storageKey, e);
            throw new BusinessException("can not load file, please check logs");
        }
    }

    @Override
    public void deleteByKey(String storageKey) {
        Path target = resolveKey(storageKey);
        logger.debug("FileStorageFileSystemService.deleteByKey() -> deleting key={}", storageKey);

        try {
            if (!Files.exists(target)) {
                throw new ResourceNotFoundException("file not found, file=" + target);
            }
            Files.delete(target);
        } catch (IOException e) {
            logger.error("FileStorageFileSystemService.deleteByKey() -> IOException for key=" + storageKey, e);
            throw new BusinessException("can not delete file=" + target + ", please check logs");
        }
    }

    // ---------------------------------------------------------------- path-shaped (directories)

    @Override
    public void save(String address, MultipartFile file, int version, String extension) {

        if(file == null) {
            throw new BusinessException("can not save null file!");
        }
        if(version < 1) {
            throw new BusinessException("version must be greater than 0");
        }

        String fileName = file.getOriginalFilename();
        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");

        if(!checkCorrectFileName(fileName)) {
            throw new BusinessException("file name should contain just one '.' and no space and no '/', your file name=" + fileName);
        }
        requireCorrectAddress(address);

        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }

        if(!checkCorrectDirectoryName(fileNameWithoutExtension)) {
            throw new BusinessException("character '.' and '/' and space not allow in directory name");
        }

        Path level1Dir = within(address + "/" + fileNameWithoutExtension);
        Path level2Dir = within(address + "/" + fileNameWithoutExtension + "/v" + version);
        Path targetPath = within(address + "/" + fileNameWithoutExtension + "/v" + version + "/" + fileName);
        String completePath = targetPath.toString();

        logger.debug("FileStorageFileSystemService.save() -> saving new file=" + completePath);
        logger.debug("FileStorageFileSystemService.save() -> level1Dir=" + level1Dir);
        logger.debug("FileStorageFileSystemService.save() -> level2Dir=" + level2Dir);

        //create directories - level1
        if(Files.notExists(level1Dir)) {
            try {
                Files.createDirectory(level1Dir);
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.save() -> IOException in create level1Dir=" + level1Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }

        }
        if(Files.notExists(level2Dir)) {
            try {
                Files.createDirectory(level2Dir);
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.save() -> IOException in create level2Dir=" + level2Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }
        }


        if(Files.notExists(targetPath)) {
            try {
                Files.copy(file.getInputStream(), targetPath);
            } catch (IOException e) {

                logger.error("FileStorageFileSystemService.save() -> IOException in FileStorageFileSystemService.save(...) method: " + e.getMessage(), e);
                throw new BusinessException("error in saving file, check logs");
            }

        } else {
            throw new DuplicateResourceException("file already exists=" + completePath);
        }


    }

    @Override
    public Resource load(String address, String fileName, int version, String extension) {
        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }

        // Was `&&` until issue 4: the address always holds a '/', so the directory half was always
        // "wrong" and the condition collapsed to the file-name check alone. Each is a reason on its own.
        if(!checkCorrectFileName(fileName)) {
            throw new BusinessException("invalid directory and file name, directory=" + address + ", file name=" + fileName + "." + extension);
        }
        requireCorrectAddress(address);

        if(version < 1) {
            throw new BusinessException("version must be greater than 0");
        }

        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");
        Path path = within(address + "/" + fileNameWithoutExtension + "/v" + version + "/" + fileName);
        String completePath = path.toString();
        logger.debug("FileStorageFileSystemService.load() -> loading file=" + completePath);

        Path foundFile = null;
        if(Files.exists(path)) {
            foundFile = path;
        } else {
            throw new ResourceNotFoundException("file not found");
        }

        try {
            Resource resource = new UrlResource(foundFile.toUri());
            return resource;
        } catch (MalformedURLException e) {
            logger.debug("FileStorageFileSystemService.load() -> can not loading file=" + completePath, e);
            throw new BusinessException("can not load file, please check logs");
        }

    }

    @Override
    public void delete(String address, String fileName, int version, String extension, boolean isFile) {

        if(isFile) {
            deleteFile(address, fileName, version, extension);
        } else {
            deleteDirectoryRecursive(address);
        }
    }

    private void deleteDirectoryRecursive(String address) {



        requireCorrectAddress(address);
        Path pathDir = within(address);
        String completePath = pathDir.toString();
        if(Files.exists(pathDir)) {

            try {
                Files.walk(pathDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                logger.info("deleting: " + path);
                                Files.delete(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            } catch (Exception e) {
                logger.error("FileStorageFileSystemService.deleteDirectoryRecursive() -> IOException in FileStorageFileSystemService.deleteDirectoryRecursive(...) method: " + e.getMessage(), e);
                throw new BusinessException("can not delete directory=" + completePath + ", please check logs");
            }

        } else {
            throw new ResourceNotFoundException("directory not exists=" + completePath);
        }

    }

    private void deleteFile(String address, String fileName, int version, String extension) {

        if(version < 1) {
            throw new BusinessException("version must be greater than 0");
        }
        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }
        if(!checkCorrectFileName(fileName)) {
            throw new BusinessException("file name should contain just one '.' and no space and no '/', your file name=" + fileName);
        }

        requireCorrectAddress(address);

        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");
        Path path = within(address + "/" + fileNameWithoutExtension + "/v" + version + "/" + fileName);
        String completePath = path.toString();

        try {

            if(Files.exists(path)) {
                Files.delete(path);
            } else {
                throw new ResourceNotFoundException("file not found, file=" + completePath);
            }

        } catch (IOException e) {
            logger.error("FileStorageFileSystemService.deleteFile() -> IOException in FileStorageFileSystemService.deleteFile(...) method: " + e.getMessage(), e);
            throw new BusinessException("can not delete file=" + completePath + ", please check logs");
        }

    }


    @Override
    public void createDirectory(String title, boolean isSubDirectory) {

        if(!isSubDirectory) {
            if(!checkCorrectDirectoryName(title)) {
                throw new BusinessException("character '.' and space and '/' not allow in directory name, your directory name=" + title);
            }
        }


        requireCorrectAddress(title);
        Path path = within(title);
        String directoryPath = path.toString();
        logger.debug("FileStorageFileSystemService.createDirectory() -> creating new directory: " + directoryPath);
        if(Files.notExists(path)) {
            try {
                Files.createDirectory(path);
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.createDirectory() -> IOException in FileStorageFileSystemService.createDirectory(...) method: " + e.getMessage(), e);
                throw new BusinessException("can not create directory=" + directoryPath + ", please check logs");
            }
        } else {
            throw new DuplicateResourceException("directory name '" + title + "' exists");
        }
    }


    private boolean checkCorrectDirectoryName(String directoryName) {
        int count1 = (int) directoryName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) directoryName.chars().filter(ch -> ch == ' ').count();
        int count3 = (int) directoryName.chars().filter(ch -> ch == '/').count();
        return count1 == 0 && count2 == 0 && count3 == 0;
    }

    private boolean checkCorrectFileName(String fileName) {
        int count1 = (int) fileName.chars().filter(ch -> ch == '.').count();
        int count2 = (int) fileName.chars().filter(ch -> ch == ' ').count();
        int count3 = (int) fileName.chars().filter(ch -> ch == '/').count();
        return count1 == 1 && count2 == 0 && count3 == 0;
    }
}
