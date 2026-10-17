package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.folder.domain.FolderClassAssignment;
import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyKind;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folder classes on the S3 surface (roadmap Phase 14), as n8n files the ERP's contracts - through the AWS SDK
 * against the application on a port. A key naming a company that does not exist yet makes the company, its
 * {@code Contract}, the contract, and the contract's {@code Documents} and {@code ContractState} from the
 * classes' templates - all in the upload - and the file lands in the {@code Documents} the contract made. A
 * file sent to the contract itself, or a folder the contract's class refuses, is refused before anything is
 * made - a {@code PUT} and a multipart upload alike.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "filemanagement.folders.max-depth=8")
class S3FolderClassTest extends DatabaseSupport {

    @LocalServerPort
    private int port;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private int ownerId;
    private ClassFixture.Classes classes;
    private ClassFixture.Root root;
    private String bucket;
    private S3Client s3;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        classes = ClassFixture.classes(classService, ownerId);
        root = ClassFixture.root(folderService, folderRepository, assignment, classes, ownerId);
        bucket = S3ObjectService.bucketName(root.bucket());

        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("n8n " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setMayCreateFolders(true);
        request.setFolderGrants(List.of(root.bucketId() + ":WRITE"));
        ApiKeyCreatedDTO created = apiKeyService.create(request, ownerId);
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port + S3Controller.MOUNT))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(created.keyId(), created.credential())))
                .build();
    }

    @AfterEach
    void close() {
        s3.close();
    }

    @Test
    @DisplayName("a contract of a company not there yet: every folder made with its class, the file in the Documents the contract made")
    void aNewCompanysContract() {
        s3.putObject(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/Documents/contract.pdf"),
                RequestBody.fromBytes(pdf()));

        int company = child(root.partyCompanyId(), "5678");
        int contracts = child(company, "Contract");
        int contract = child(contracts, "99-014");
        int documents = child(contract, "Documents");
        assertThat(classOf(company)).isEqualTo(classes.company());
        assertThat(classOf(contracts)).isEqualTo(classes.contracts());
        assertThat(classOf(contract)).isEqualTo(classes.contract());
        assertThat(classOf(documents)).isEqualTo(classes.documents());
        assertThat(classOf(child(contract, "ContractState"))).isEqualTo(classes.statements());
        assertThat(jdbc.queryForList("SELECT name FROM folder WHERE parent_id = ? ORDER BY name", String.class, contract))
                .containsExactly("ContractState", "Documents");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ?", Integer.class, documents)).isEqualTo(1);

        // a statement, as n8n sends one: ContractState/<n>/ - the statement class from the container's rule
        s3.putObject(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/ContractState/3/s.pdf"), RequestBody.fromBytes(pdf()));
        assertThat(classOf(child(child(contract, "ContractState"), "3"))).isEqualTo(classes.statement());
        assertThat(jdbc.queryForList("SELECT id FROM folder f WHERE f.class_path <> COALESCE((SELECT p.class_path FROM folder p"
                + " WHERE p.id = f.parent_id), '/') || COALESCE(f.class_id || '/', '')", Integer.class)).isEmpty();
    }

    @Test
    @DisplayName("a file sent to the contract itself is refused - a PUT and a multipart upload - and nothing is stored")
    void aContractTakesNoFile() {
        s3.putObject(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/Documents/a.pdf"), RequestBody.fromBytes(pdf()));
        int contract = child(child(child(root.partyCompanyId(), "5678"), "Contract"), "99-014");

        int put = code(() -> s3.putObject(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/loose.pdf"),
                RequestBody.fromBytes(pdf())));
        int multipart = code(() -> s3.createMultipartUpload(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/big.pdf")));
        assertThat(put).isBetween(400, 499);
        assertThat(multipart).isBetween(400, 499);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ?", Integer.class, contract)).isZero();
    }

    @Test
    @DisplayName("a folder the contract's class refuses is not made, nor anything under it")
    void aRefusedName() {
        s3.putObject(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/Documents/a.pdf"), RequestBody.fromBytes(pdf()));
        int contract = child(child(child(root.partyCompanyId(), "5678"), "Contract"), "99-014");

        int refused = code(() -> s3.putObject(r -> r.bucket(bucket).key("PartyCompany/5678/Contract/99-014/Misc/deep/x.pdf"),
                RequestBody.fromBytes(pdf())));
        assertThat(refused).isBetween(400, 499);
        assertThat(jdbc.queryForList("SELECT name FROM folder WHERE parent_id = ? ORDER BY name", String.class, contract))
                .containsExactly("ContractState", "Documents");
    }

    private int child(int parentId, String name) {
        return folderRepository.findByParentIdAndNameIgnoreCase(parentId, name).orElseThrow().getId();
    }

    private Integer classOf(int folderId) {
        return jdbc.queryForObject("SELECT class_id FROM folder WHERE id = ?", Integer.class, folderId);
    }

    private static int code(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e.statusCode();
        }
        throw new AssertionError("the call succeeded");
    }

    private static byte[] pdf() {
        return "%PDF-1.4\nclasses".getBytes(StandardCharsets.US_ASCII);
    }
}
