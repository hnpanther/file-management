package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FileUploadDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.MainTagFile;
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
    private MainTagFileService mainTagFileService;
    @Mock
    private ActionHistoryService actionHistoryService;
    /**
     * Lenient and silent by default, so the guard clauses below are tested on their own. What it
     * does when it refuses is {@link #refusesAWriteOutsideTheGrantBeforeAnythingElse()}.
     */
    @Mock
    private FolderAccessService folderAccessService;
    /**
     * Answers the tag's folder when a test stubs it - the upload resolves its target folder before
     * the access check and the taxonomy check, so the guards below need one to exist.
     */
    @Mock
    private FolderMirrorService folderMirrorService;
    @Mock
    private TagMirrorService tagMirrorService;
    /** Lenient and silent: a mock refuses nothing, so the guards below are tested on their own. */
    @Mock
    private UploadPolicyService uploadPolicyService;

    @InjectMocks
    private FileService underTest;

    private MainTagFile mainTag;

    /** The folder mirroring {@code mainTag}: a TAG folder whose source is the tag. */
    private com.hnp.filemanagement.entity.Folder tagFolder() {
        var folder = new com.hnp.filemanagement.entity.Folder();
        folder.setId(70);
        folder.setKind(com.hnp.filemanagement.entity.FolderKind.TAG);
        folder.setSourceType(com.hnp.filemanagement.entity.FolderSourceType.MAIN_TAG);
        folder.setSourceId(mainTag.getId());
        folder.setPath("/1/2/3/70/");
        return folder;
    }

    @BeforeEach
    void setUp() {
        var user = TestData.user();
        user.setId(1);
        var generalTag = TestData.generalTag(user, "tag");
        generalTag.setId(1);
        var category = TestData.category(user, generalTag, "documents");
        category.setId(1);
        var subCategory = TestData.subCategory(user, category, "invoices");
        subCategory.setId(2);
        mainTag = TestData.mainTag(user, subCategory, "tag");
        mainTag.setId(3);
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

        verifyNoInteractions(fileStorageService, mainTagFileService, actionHistoryService);
    }

    @Test
    @DisplayName("an unstorable file name is refused before the tag is even looked up")
    void refusesAnUnstorableName() {
        FileInfoDTO request = uploadRequest("has space.txt");

        assertThatThrownBy(() -> underTest.createNewFile(request, 1, 1))
                .isInstanceOf(InvalidDataException.class);

        verifyNoInteractions(fileStorageService, mainTagFileService);
    }

    @Test
    @DisplayName("a mismatched taxonomy is refused, and nothing is written to storage")
    void refusesAMismatchedTaxonomy() {
        when(mainTagFileService.getMainTagFileEntity(anyInt())).thenReturn(mainTag);
        when(folderMirrorService.folderOf(mainTag)).thenReturn(tagFolder());

        FileInfoDTO request = uploadRequest("report.txt");
        request.setFileCategoryId(999);

        assertThatThrownBy(() -> underTest.createNewFile(request, 1, 1))
                .isInstanceOf(InvalidDataException.class);

        verifyNoInteractions(fileStorageService);
        verify(fileInfoRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * The write check runs before the category/sub-category/tag chain is validated, and this is the
     * test that pins that order. Answering "your triple is inconsistent" to somebody who may not
     * write here would tell them which triples are consistent — a slow way to map a taxonomy they
     * were never shown.
     */
    @Test
    @DisplayName("a write outside the grant is refused before the taxonomy is even checked")
    void refusesAWriteOutsideTheGrantBeforeAnythingElse() {
        when(mainTagFileService.getMainTagFileEntity(anyInt())).thenReturn(mainTag);
        when(folderMirrorService.folderOf(mainTag)).thenReturn(tagFolder());
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("no"))
                .when(folderAccessService).requireWriteAccess(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(com.hnp.filemanagement.entity.Folder.class));

        FileInfoDTO request = uploadRequest("report.txt");
        // Also inconsistent, which is what makes the order observable: the taxonomy check would
        // otherwise answer first.
        request.setFileCategoryId(999);

        assertThatThrownBy(() -> underTest.createNewFile(request, 1, 1))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(fileStorageService);
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
        FileInfo fileInfo = TestData.fileInfo(mainTag.getCreatedBy(), mainTag, "report");
        fileInfo.setId(10);
        fileInfo.setLastVersion(1);
        return fileInfo;
    }

    private FileInfoDTO uploadRequest(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setFileCategoryId(1);
        request.setFileSubCategoryId(2);
        request.setMainTagFileId(3);
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
