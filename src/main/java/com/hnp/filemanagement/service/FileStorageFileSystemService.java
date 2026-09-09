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
 * <p>Two properties of this class are worth knowing before changing it:
 *
 * <ul>
 *   <li>{@code base-dir} is concatenated, not resolved, so the configured value must end with a
 *       separator;</li>
 *   <li>there is no path-containment check, so a name containing {@code ..} would escape the root.
 *       {@code checkCorrectFileName} and {@code checkCorrectDirectoryName} are what stand between
 *       the caller and that, which is why they reject rather than sanitise.</li>
 * </ul>
 *
 * <p>Both are catalogued in {@code docs/issues.md} and fixed in Phase 2 along with the storage
 * port. Neither applies to the key-shaped methods added for roadmap 7.1: {@code resolveKey}
 * resolves against an absolute, normalised root and refuses a key that would leave it, so those
 * three need no separator convention and no name spelling rule.
 */
@Service("fileSystem")
@Primary
public class FileStorageFileSystemService implements FileStorageService {

    private final Logger logger = LoggerFactory.getLogger(FileStorageFileSystemService.class);

    private final String baseDir;


    public FileStorageFileSystemService(@Value("${file.management.base-dir}") String baseDir) {
        this.baseDir = baseDir;

    }

    // ---------------------------------------------------------------- key-shaped (roadmap 7.1)

    /**
     * Resolves a storage key to a path inside the root, and refuses anything that would leave it.
     *
     * <p><b>This check does not exist on the path-shaped methods below</b>, which is catalogued: a
     * name containing {@code ..} escapes the root there, and only {@code checkCorrectFileName} and
     * {@code checkCorrectDirectoryName} stand in the way. The new methods do not inherit that. The
     * root is resolved to an absolute, normalised path and the target must still be beneath it after
     * normalisation, which is the containment test rather than a spelling test on the input.
     *
     * <p>It also removes the {@code base-dir} trailing-separator trap for these methods: {@code
     * resolve} joins with a separator whether or not the configured value ends with one.
     */
    private Path resolveKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new BusinessException("storage key is empty");
        }
        Path root = Paths.get(baseDir).toAbsolutePath().normalize();
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("storage key escapes the storage root: " + storageKey);
        }
        if (target.equals(root)) {
            throw new BusinessException("storage key names the storage root itself: " + storageKey);
        }
        return target;
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

        String directoryPath = baseDir + address;
        String fileName = file.getOriginalFilename();
        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");

        if(!checkCorrectFileName(fileName)) {
            throw new BusinessException("file name should contain just one '.' and no space and no '/', your file name=" + fileName);
        }

        String level1Dir = directoryPath + "/" + fileNameWithoutExtension;
        String level2Dir = level1Dir + "/v" + version;
        String completePath = level2Dir + "/" + fileName;

        int index = fileName.lastIndexOf(".");
        if(!extension.equals(fileName.substring(index + 1))) {
            throw new BusinessException("file extension and parameter extension is different: file name=" + fileName + ",extension=" + extension);
        }

        logger.debug("FileStorageFileSystemService.save() -> saving new file=" + completePath);
        logger.debug("FileStorageFileSystemService.save() -> level1Dir=" + level1Dir);
        logger.debug("FileStorageFileSystemService.save() -> level2Dir=" + level2Dir);

//        if(!checkCorrectDirectoryName(address) || !checkCorrectDirectoryName(fileNameWithoutExtension)) {
//            throw new BusinessException("character '.' and '/' and space not allow in directory name");
//        }

        if(!checkCorrectDirectoryName(fileNameWithoutExtension)) {
            throw new BusinessException("character '.' and '/' and space not allow in directory name");
        }


        //create directories - level1
        if(Files.notExists(Paths.get(level1Dir))) {
            try {
                Files.createDirectory(Paths.get(level1Dir));
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.save() -> IOException in create level1Dir=" + level1Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }

        }
        if(Files.notExists(Paths.get(level2Dir))) {
            try {
                Files.createDirectory(Paths.get(level2Dir));
            } catch (IOException e) {
                logger.error("FileStorageFileSystemService.save() -> IOException in create level2Dir=" + level2Dir, e);
                throw new BusinessException("can not create file:" + fileName + ", please check logs");
            }
        }


        Path targetPath = Paths.get(completePath);
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

        if(!checkCorrectDirectoryName(address) && !checkCorrectFileName(fileName)) {
            throw new BusinessException("invalid directory and file name, directory=" + address + ", file name=" + fileName + "." + extension);
        }

        if(version < 1) {
            throw new BusinessException("version must be greater than 0");
        }

        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");
        String completePath = baseDir + address + "/" + fileNameWithoutExtension + "/v" + version + "/" + fileName;
        logger.debug("FileStorageFileSystemService.load() -> loading file=" + completePath);

        Path path = Paths.get(completePath);
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



        String completePath = baseDir + address;
        Path pathDir = Paths.get(completePath);
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

        String fileNameWithoutExtension = fileName.replaceFirst("[.][^.]+$", "");
        String completePath = baseDir + address + "/" + fileNameWithoutExtension + "/v" + version + "/" + fileName;


        try {

            Path path = Paths.get(completePath);
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


//        String directoryPath = baseDir + "/" + title;
        String directoryPath = baseDir +  title;
        logger.debug("FileStorageFileSystemService.createDirectory() -> creating new directory: " + directoryPath);
        Path path = Paths.get(directoryPath);
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
