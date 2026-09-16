package com.hnp.filemanagement.support;

import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builders for the entity graph the tests need.
 *
 * <p>Every test class used to open with sixty lines of {@code new User(); setUsername(...);
 * setNationalCode(...); ...} repeated with small, undocumented differences — which is how two of
 * them ended up asserting against fixtures that were not comparable. These builders set every
 * {@code NOT NULL} column to something valid and leave the test to override only what it is
 * actually about.
 *
 * <p>Nothing here saves. The caller decides what to persist and in which order, because the order
 * is often the point — a foreign key has to exist before the row that references it, and several
 * tests exist to prove exactly that.
 *
 * <p>Unique columns are given generated values. Fixtures with hardcoded usernames and national
 * codes collided as soon as two tests ran in the same transaction, and the failure looked like a
 * bug in the code under test rather than in the fixture.
 */
public final class TestData {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private TestData() {
    }

    /** A distinct small integer, for the columns that must not collide between fixtures. */
    public static int nextSequence() {
        return SEQUENCE.incrementAndGet();
    }

    /**
     * Bytes that pass for the kind of file the name promises. Uploads are judged by their first
     * bytes as well as their extension (issue 12), so a fixture named {@code report.pdf} has to
     * start like a PDF. The payload after the signature is the name, so two fixtures differ.
     */
    public static byte[] bytesFor(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        byte[] body = ("content of " + fileName).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = switch (extension) {
            case "pdf" -> "%PDF-1.4 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            case "png" -> new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
            case "jpg", "jpeg" -> new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
            case "docx", "xlsx", "pptx" -> new byte[]{'P', 'K', 0x03, 0x04};
            case "mp4" -> new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
            case "mp3" -> new byte[]{'I', 'D', '3'};
            default -> new byte[0];
        };
        byte[] bytes = new byte[signature.length + body.length];
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        System.arraycopy(body, 0, bytes, signature.length, body.length);
        return bytes;
    }

    public static User user() {
        int n = nextSequence();
        User user = new User();
        user.setUsername("user" + n);
        user.setPersonelCode(100_000 + n);
        user.setNationalCode(String.format("%010d", n));
        user.setPhoneNumber("0912" + String.format("%07d", n));
        user.setEmail("user" + n + "@example.test");
        user.setPassword("{noop}irrelevant");
        user.setFirstName("First" + n);
        user.setLastName("Last" + n);
        user.setEnabled(1);
        user.setState(0);
        user.setLoginType(0);
        return user;
    }

    /**
     * Creates the directory a category or sub-category row claims to own.
     *
     * <p>A fixture that inserts the row without the directory produces a state the application
     * cannot: {@code FileCategoryService.createCategory} always makes both. Tests that go on to
     * delete or write into that category need the directory to exist, because the storage layer
     * refuses to remove or descend into one that does not.
     */
    public static void createStorageDirectory(String baseDir, String... segments) {
        Path path = Paths.get(baseDir, segments);
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not create " + path, e);
        }
    }

    public static Role role(String roleName) {
        Role role = new Role();
        role.setRoleName(roleName);
        return role;
    }

    public static Permission permission(PermissionEnum name) {
        Permission permission = new Permission();
        permission.setPermissionName(name);
        permission.setDescription(name.name());
        return permission;
    }

    public static GeneralTag generalTag(User creator, String tagName) {
        GeneralTag generalTag = new GeneralTag();
        generalTag.setTagName(tagName);
        generalTag.setTagNameDescription(tagName + " description");
        generalTag.setDescription(tagName + " long description");
        generalTag.setType(0);
        generalTag.setEnabled(1);
        generalTag.setState(0);
        generalTag.setCreatedBy(creator);
        return generalTag;
    }

    public static FileCategory category(User creator, GeneralTag generalTag, String categoryName) {
        FileCategory category = new FileCategory();
        category.setCategoryName(categoryName);
        category.setCategoryNameDescription(categoryName + " description");
        category.setDescription(categoryName + " long description");
        category.setPath("/base/" + categoryName);
        category.setRelativePath(categoryName);
        category.setEnabled(1);
        category.setState(0);
        category.setCreatedBy(creator);
        category.setGeneralTag(generalTag);
        return category;
    }

    public static FileSubCategory subCategory(User creator, FileCategory category, String name) {
        FileSubCategory subCategory = new FileSubCategory();
        subCategory.setSubCategoryName(name);
        subCategory.setSubCategoryNameDescription(name + " description");
        subCategory.setDescription(name + " long description");
        subCategory.setPath(category.getPath() + "/" + name);
        subCategory.setRelativePath(category.getRelativePath() + "/" + name);
        subCategory.setEnabled(1);
        subCategory.setState(0);
        subCategory.setCreatedBy(creator);
        subCategory.setFileCategory(category);
        return subCategory;
    }

    public static MainTagFile mainTag(User creator, FileSubCategory subCategory, String tagName) {
        MainTagFile mainTag = new MainTagFile();
        mainTag.setTagName(tagName);
        mainTag.setTagNameDescription(tagName + " description");
        mainTag.setDescription(tagName + " long description");
        mainTag.setType(0);
        mainTag.setEnabled(1);
        mainTag.setState(0);
        mainTag.setCreatedBy(creator);
        mainTag.setFileSubCategory(subCategory);
        return mainTag;
    }

    /** A file with no versions yet; add them with {@link #fileDetails}. */
    public static FileInfo fileInfo(User creator, MainTagFile mainTag, String fileName) {
        FileSubCategory subCategory = mainTag.getFileSubCategory();
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setCodeName(fileName);
        fileInfo.setFileNameDescription(fileName + " description");
        fileInfo.setDescription(fileName + " long description");
        fileInfo.setFilePath(subCategory.getPath() + "/" + fileName);
        fileInfo.setRelativePath(subCategory.getRelativePath() + "/" + fileName);
        fileInfo.setLastVersion(0);
        fileInfo.setEnabled(1);
        fileInfo.setState(0);
        fileInfo.setCreatedBy(creator);
        fileInfo.setMainTagFile(mainTag);
        fileInfo.setFileSubCategory(subCategory);
        return fileInfo;
    }

    /**
     * One stored revision, linked to its parent on both sides and reflected in
     * {@code lastVersion} — which is what the production code maintains, so a fixture that skipped
     * it would let a test pass against state the application can never produce.
     */
    public static FileDetails fileDetails(User creator, FileInfo fileInfo, int version, String extension) {
        String fileName = fileInfo.getFileName() + "." + extension;
        FileDetails fileDetails = new FileDetails();
        fileDetails.setFileName(fileName);
        fileDetails.setHashId(UUID.randomUUID().toString());
        fileDetails.setFileExtension(extension);
        fileDetails.setContentType("application/octet-stream");
        fileDetails.setDescription(fileName + " description");
        // One expression for both, as FileService does: the two columns hold the same string and
        // a fixture that let them differ would be testing state the application cannot produce.
        String storageKey = fileInfo.getRelativePath() + "/v" + version + "/" + fileName;
        fileDetails.setFilePath(fileInfo.getFilePath() + "/v" + version + "/" + fileName);
        fileDetails.setRelativePath(storageKey);
        fileDetails.setStorageKey(storageKey);
        fileDetails.setFileSize(1024);
        fileDetails.setVersion(version);
        fileDetails.setVersionName("V" + version);
        fileDetails.setEnabled(1);
        fileDetails.setState(0);
        fileDetails.setCreatedBy(creator);

        fileInfo.addFileDetails(fileDetails);
        if (version > fileInfo.getLastVersion()) {
            fileInfo.setLastVersion(version);
        }
        return fileDetails;
    }
}
