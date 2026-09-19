package com.hnp.filemanagement.support;

import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.TagGroup;
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
            case "gif" -> "GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            case "docx", "xlsx", "pptx", "zip" -> new byte[]{'P', 'K', 0x03, 0x04};
            case "doc", "xls", "ppt" -> new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
            case "mp4" -> new byte[]{0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
            case "mp3" -> new byte[]{'I', 'D', '3'};
            case "rar" -> "Rar!".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            case "7z" -> new byte[]{'7', 'z', (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};
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

    public static TagGroup tagGroup(User creator, String name) {
        TagGroup group = new TagGroup();
        group.setName(name);
        group.setTitle(name + " description");
        group.setEnabled(1);
        group.setCreatedBy(creator);
        return group;
    }

    /**
     * One folder under a parent, unsaved, with the kind the level implies. The path holds the
     * row's own id, which only the insert assigns, so a caller that saves it must call
     * {@link #placed(Folder)} afterwards - {@link FolderFixture} does both.
     */
    public static Folder folder(User creator, Folder parent, String name, TagGroup group) {
        Folder folder = new Folder();
        folder.setParent(parent);
        folder.setName(name);
        folder.setDisplayName(name + " description");
        folder.setDepth(parent.getDepth() + 1);
        folder.setKind(switch (parent.getKind()) {
            case ROOT -> FolderKind.CATEGORY;
            case CATEGORY -> FolderKind.SUB_CATEGORY;
            case SUB_CATEGORY -> FolderKind.TAG;
            default -> throw new IllegalArgumentException("a " + parent.getKind() + " folder holds no folders");
        });
        folder.setTagGroup(group);
        folder.setEnabled(1);
        folder.setState(0);
        folder.setCreatedBy(creator);
        folder.setPath("");
        return folder;
    }

    /** Writes the materialised path of a just-saved folder, as FolderService does. */
    public static Folder placed(Folder saved) {
        saved.setPath(saved.getParent().childPath(saved.getId()));
        return saved;
    }

    /** A file with no versions yet, in a tag folder; add versions with {@link #fileDetails}. */
    public static FileInfo fileInfo(User creator, Folder tagFolder, String fileName) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileName(fileName);
        fileInfo.setCodeName(fileName);
        fileInfo.setFileNameDescription(fileName + " description");
        fileInfo.setDescription(fileName + " long description");
        fileInfo.setLastVersion(0);
        fileInfo.setEnabled(1);
        fileInfo.setState(0);
        fileInfo.setCreatedBy(creator);
        fileInfo.setFolder(tagFolder);
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
        // The key as FileService writes it: the two upper folder names, the file, the version.
        Folder tag = fileInfo.getFolder();
        String storageKey = tag.getParent().getParent().getName() + "/" + tag.getParent().getName()
                + "/" + fileInfo.getFileName() + "/v" + version + "/" + fileName;
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
