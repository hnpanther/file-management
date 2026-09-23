package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileService}'s guard clauses, with every collaborator mocked.
 *
 * <p>These complement {@code FileServiceTest} rather than repeat it. The integration tests prove
 * what a valid upload writes; these prove what an <em>invalid</em> one does <b>not</b> write — that
 * a rejected request never reaches storage, which is the property that keeps the disk and the
 * database from drifting apart on the failure path.
 *
 * <p>They also run without Docker or a database, so a mistake in a validation rule fails in
 * milliseconds instead of after a container start.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceUnitTest {

    @Mock
    private FileInfoRepository fileInfoRepository;
    @Mock
    private FileDetailsRepository fileDetailsRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private ActionHistoryService actionHistoryService;
    /**
     * Lenient and silent by default, so the guard clauses below are tested on their own. What it
     * does when it refuses is {@link #refusesAWriteOutsideTheGrantBeforeAnythingElse()}.
     */
    @Mock
    private FolderAccessService folderAccessService;
    /** Answers the target folder when a test stubs it - the upload resolves it first. */
    @Mock
    private FolderService folderService;
    @Mock
    private TagMirrorService tagMirrorService;
    /** Lenient and silent: a mock refuses nothing, so the guards below are tested on their own. */
    @Mock
    private UploadPolicyService uploadPolicyService;
    @Mock
    private FolderQuotaService folderQuotaService;
    @Mock
    private com.hnp.filemanagement.repository.FileShareLinkRepository fileShareLinkRepository;

    @InjectMocks
    private FileService underTest;

    private User user;
    private Folder tagFolder;

    @BeforeEach
    void setUp() {
        user = TestData.user();
        user.setId(1);
        Folder root = new Folder();
        root.setId(1);
        root.setKind(FolderKind.ROOT);
        root.setDepth(0);
        root.setPath("/1/");
        Folder category = TestData.folder(user, root, "documents", TestData.tagGroup(user, "gt"));
        category.setId(2);
        category.setPath("/1/2/");
        Folder subCategory = TestData.folder(user, category, "invoices", null);
        subCategory.setId(3);
        subCategory.setPath("/1/2/3/");
        tagFolder = TestData.folder(user, subCategory, "tag", null);
        tagFolder.setId(70);
        tagFolder.setPath("/1/2/3/70/");
    }

    @Test
    @DisplayName("a multipart with no usable file name is refused before anything else runs")
    void refusesAMultipartWithNoName() {
        FileInfoDTO request = new FileInfoDTO();
        // MockMultipartFile normalises a null original name to "", so this exercises the empty
        // case; the explicit null branch in the service covers a real multipart, which can be null.
        request.setMultipartFile(new MockMultipartFile("f", null, "text/plain", new byte[0]));

        assertThatThrownBy(() -> underTest.createNewFile(request, 1, 1))
                .isInstanceOf(InvalidDataException.class);

        verifyNoInteractions(fileStorageService, folderService, actionHistoryService);
    }

    @Test
    @DisplayName("an unstorable file name is refused before the folder is even looked up")
    void refusesAnUnstorableName() {
        FileInfoDTO request = uploadRequest("has/slash.txt");

        assertThatThrownBy(() -> underTest.createNewFile(request, 1, 1))
                .isInstanceOf(InvalidDataException.class);

        verifyNoInteractions(fileStorageService, folderService);
    }

    @Test
    @DisplayName("a request without a folderId is refused, and nothing is written to storage")
    void refusesARequestWithoutAFolder() {
        FileInfoDTO request = uploadRequest("report.txt");
        request.setFolderId(null);

        assertThatThrownBy(() -> underTest.createNewFile(request, 1, 1))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("folderId");

        verifyNoInteractions(fileStorageService, folderService);
        verify(fileInfoRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the root is refused: a document is filed into a folder beneath it")
    void refusesTheRoot() {
        Folder root = tagFolder.getParent().getParent().getParent();
        when(folderService.requireWithTagGroup(anyInt())).thenReturn(root);

        assertThatThrownBy(() -> underTest.createNewFile(uploadRequest("report.txt"), 1, 1))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("ROOT");

        verifyNoInteractions(fileStorageService);
    }

    /** The write check runs before the duplicate check, and this is the test that pins that order. */
    @Test
    @DisplayName("a write outside the grant is refused before anything about the file is looked at")
    void refusesAWriteOutsideTheGrantBeforeAnythingElse() {
        when(folderService.requireWithTagGroup(anyInt())).thenReturn(tagFolder);
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("no"))
                .when(folderAccessService).requireWriteAccess(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(com.hnp.filemanagement.entity.Folder.class));

        assertThatThrownBy(() -> underTest.createNewFile(uploadRequest("report.txt"), 1, 1))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(fileStorageService, fileInfoRepository);
    }

    @Test
    @DisplayName("an unknown upload type is refused, and stores neither a row nor a file")
    void refusesAnUnknownUploadType() {
        FileInfo fileInfo = existingFile();
        when(fileInfoRepository.findByIdAndFetchFileDetails(anyInt())).thenReturn(Optional.of(fileInfo));

        FileUploadDTO request = uploadDetailsRequest("report.txt", 2);
        request.setType("neither");

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, 1))
                .isInstanceOf(InvalidDataException.class)
                .hasMessageContaining("unknown upload type");

        verifyNoInteractions(fileStorageService);
        verify(fileDetailsRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("a version that is not the next one is refused, and nothing is written")
    void refusesAVersionThatSkipsAhead() {
        FileInfo fileInfo = existingFile();
        when(fileInfoRepository.findByIdAndFetchFileDetails(anyInt())).thenReturn(Optional.of(fileInfo));

        FileUploadDTO request = uploadDetailsRequest("report.txt", 7);
        request.setType("version");

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, 1))
                .isInstanceOf(InvalidDataException.class);

        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("a version whose name is not the file's own is refused")
    void refusesAVersionWithTheWrongName() {
        FileInfo fileInfo = existingFile();
        when(fileInfoRepository.findByIdAndFetchFileDetails(anyInt())).thenReturn(Optional.of(fileInfo));

        FileUploadDTO request = uploadDetailsRequest("different.txt", 2);
        request.setType("version");

        assertThatThrownBy(() -> underTest.createNewFileDetails(request, 1))
                .isInstanceOf(InvalidDataException.class);

        verifyNoInteractions(fileStorageService);
    }

    @Test
    @DisplayName("a state outside {0, -1} is refused before the row is loaded")
    void refusesAnInvalidState() {
        assertThatThrownBy(() -> underTest.changeFileInfoState(1, 5, 1))
                .isInstanceOf(InvalidDataException.class);

        verify(fileInfoRepository, never()).findById(anyInt());
    }

    @Test
    @DisplayName("an empty description is refused before the row is loaded")
    void refusesAnEmptyDescription() {
        assertThatThrownBy(() -> underTest.updateFileInfoDescription(1, "", 1))
                .isInstanceOf(InvalidDataException.class);

        verify(fileInfoRepository, never()).findById(anyInt());
    }

    // ---------------------------------------------------------------- helpers

    private FileInfo existingFile() {
        FileInfo fileInfo = TestData.fileInfo(user, tagFolder, "report");
        fileInfo.setId(10);
        TestData.fileDetails(user, fileInfo, 1, "txt");
        return fileInfo;
    }

    private FileInfoDTO uploadRequest(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setFolderId(70);
        request.setDescription("a description");
        request.setMultipartFile(multipart(fileName));
        return request;
    }

    private FileUploadDTO uploadDetailsRequest(String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(10);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(request.getFileName());
        request.setVersion(version);
        request.setMultipartFile(multipart(fileName));
        return request;
    }

    private static MultipartFile multipart(String fileName) {
        return new MockMultipartFile(fileName, fileName, "text/plain",
                "contents".getBytes(StandardCharsets.UTF_8));
    }
}
