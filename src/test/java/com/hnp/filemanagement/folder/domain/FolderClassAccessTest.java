package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.FolderSearchRepository;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a class grant reaches (roadmap Phase 14), with folder access enforced - the security-bearing half of the
 * feature. The contracts department granted {@code documents} reads every contract's {@code Documents} in every
 * company and nothing else: not a statement, not a company's other folder, not by the file list, a download, the
 * explorer, its search or the SQL every report and search shares. Finance, granted the statements, the reverse.
 * A grant within a scope reaches only there; a read grant writes nothing; an API key has no class grants.
 *
 * <p>And the one property everything rests on, asked of random trees: the Java answer ({@code FolderAccess}, from
 * the class grants as paths) and the SQL answer ({@code ReadScopeSql}, from {@code class_path}) are the same, and
 * both are what an oracle written from the definitions alone says.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.folders.max-depth=8"})
class FolderClassAccessTest extends DatabaseSupport {

    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private RoleClassGrantService roleClassGrantService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FolderSearchRepository folderSearchRepository;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private ClassFixture.Classes classes;
    private ClassFixture.Root root;
    private ClassFixture.Company a1;
    private ClassFixture.Company b2;
    private int statementFolder;
    private int letters;

    private int contractsDept;
    private int finance;
    private int scoped;
    private int writer;
    private int nobody;

    private Map<String, FileDetailsDTO> files;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        classes = ClassFixture.classes(classService, adminId);
        root = ClassFixture.root(folderService, folderRepository, assignment, classes, adminId);
        a1 = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1", "C2");
        b2 = ClassFixture.company(folderService, folderRepository, root, "B2", adminId, "C3");
        statementFolder = folderService.create(a1.contracts().getFirst().contractStateId(), "5", "5", null, null, adminId).id();
        letters = folderService.create(a1.id(), "Letters", "Letters", null, null, adminId).id();

        files = new HashMap<>();
        files.put("c1doc", upload(a1.contracts().get(0).documentsId(), "c1doc.pdf"));
        files.put("c2doc", upload(a1.contracts().get(1).documentsId(), "c2doc.pdf"));
        files.put("c3doc", upload(b2.contracts().getFirst().documentsId(), "c3doc.pdf"));
        files.put("statement", upload(statementFolder, "statement.pdf"));
        files.put("letter", upload(letters, "letter.pdf"));

        contractsDept = userWith(new RoleClassGrantService.Requested(classes.documents(), "READ", ""));
        finance = userWith(new RoleClassGrantService.Requested(classes.statements(), "READ", ""));
        scoped = userWith(new RoleClassGrantService.Requested(classes.documents(), "READ", root.path() + "/A1"));
        writer = userWith(new RoleClassGrantService.Requested(classes.documents(), "WRITE", ""));
        nobody = userRepository.save(TestData.user()).getId();
        flush();
    }

    // ---------------------------------------------------------------- what each one reaches

    @Test
    @DisplayName("the file list: the contracts department every contract's documents, finance the statements, a scope its company's")
    void fileList() {
        assertThat(listed(contractsDept)).containsExactlyInAnyOrder("c1doc", "c2doc", "c3doc");
        assertThat(listed(finance)).containsExactlyInAnyOrder("statement");
        assertThat(listed(scoped)).containsExactlyInAnyOrder("c1doc", "c2doc");
        assertThat(listed(nobody)).isEmpty();
        assertThat(listed(adminId)).contains("c1doc", "c2doc", "c3doc", "statement", "letter");
    }

    @Test
    @DisplayName("a download and a file page: a document yes, a statement and a letter no")
    void downloadsAndFilePages() {
        assertThat(fileService.downloadFile(files.get("c3doc").getId(), contractsDept).getResource().exists()).isTrue();
        assertThat(fileService.getFileInfoDtoWithFileDetails(files.get("c1doc").getFileInfoId(), contractsDept)).isNotNull();
        for (String refused : List.of("statement", "letter")) {
            assertThatThrownBy(() -> fileService.downloadFile(files.get(refused).getId(), contractsDept))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> fileService.getFileInfoDtoWithFileDetails(files.get(refused).getFileInfoId(), contractsDept))
                    .isInstanceOf(AccessDeniedException.class);
        }
        assertThatThrownBy(() -> fileService.downloadFile(files.get("c1doc").getId(), finance)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fileService.downloadFile(files.get("c3doc").getId(), scoped)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the explorer: the way down shown, a contract opened shows only its Documents, and Documents its files")
    void explorer() {
        FolderAccess access = folderAccessService.accessFor(contractsDept);
        ClassFixture.Contract c1 = a1.contracts().getFirst();
        assertThat(access.visibleChildIdsUnder(pathOf(root.partyCompanyId()))).contains(Set.of(a1.id(), b2.id()));
        assertThat(access.visible(pathOf(a1.id()))).isTrue();
        assertThat(access.canRead(pathOf(a1.id()))).isFalse();
        assertThat(access.visible(pathOf(letters))).isFalse();
        assertThat(access.visible(pathOf(c1.contractStateId()))).isFalse();

        FolderContentDTO contract = folderContentService.contentOf(c1.id(), 0, 50, contractsDept);
        assertThat(contract.readable()).isFalse();
        assertThat(contract.folders()).extracting(FolderContentDTO.FolderEntry::id).containsExactly(c1.documentsId());

        FolderContentDTO documents = folderContentService.contentOf(c1.documentsId(), 0, 50, contractsDept);
        assertThat(documents.readable()).isTrue();
        assertThat(documents.writable()).isFalse();
        assertThat(documents.files()).hasSize(1);

        assertThatThrownBy(() -> folderContentService.contentOf(c1.contractStateId(), 0, 50, contractsDept))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> folderContentService.contentOf(letters, 0, 50, contractsDept))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("the explorer's folder search finds the documents and the way to them, never a statement")
    void folderSearch() {
        FolderReadScope scope = folderAccessService.readScope(contractsDept);
        String all = pathOf(root.bucketId());
        assertThat(names(folderSearchRepository.visibleMatches(scope, null, "Documents", all, 50))).hasSize(3);
        assertThat(folderSearchRepository.visibleMatches(scope, null, "ContractState", all, 50)).isEmpty();
        assertThat(folderSearchRepository.visibleMatches(scope, null, "Letters", all, 50)).isEmpty();
        assertThat(names(folderSearchRepository.visibleMatches(scope, null, "A1", all, 50))).containsExactly("A1");

        FolderReadScope financeScope = folderAccessService.readScope(finance);
        assertThat(names(folderSearchRepository.visibleMatches(financeScope, null, "ContractState", all, 50))).hasSize(3);
        assertThat(folderSearchRepository.visibleMatches(financeScope, null, "Documents", all, 50)).isEmpty();
        assertThat(folderSearchRepository.visibleMatches(folderAccessService.readScope(nobody), null, "A1", all, 50)).isEmpty();
    }

    @Test
    @DisplayName("a write grant files into Documents; a read grant does not; a class is set only where one may write")
    void writes() {
        int documents = a1.contracts().getFirst().documentsId();
        assertThat(uploadAs(writer, documents, "new.pdf")).isNotNull();
        assertThatThrownBy(() -> uploadAs(contractsDept, documents, "nope.pdf")).isInstanceOf(AccessDeniedException.class);
        // a class is set where one may write (SET_FOLDER_CLASS is the page's): not by a read grant, not above it
        assertThatThrownBy(() -> assignment.set(documents, classes.statement(), contractsDept)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> assignment.set(a1.contracts().getFirst().id(), null, writer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> assignment.preview(root.partyCompanyId(), writer)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an API key's rows of the grants are its folder grants alone: a class grant is a person's roles'")
    void keysHaveNoClassGrants() {
        Integer keyRowsWithAClass = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + GrantedFolderPath.SQL
                + ") g WHERE g.class_id <> 0 AND g.api_key_id <> 0", Integer.class);
        assertThat(keyRowsWithAClass).isZero();
        assertThat(folderAccessService.accessForApiKey(999_999).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Java and SQL agree for each person, folder by folder")
    void javaAndSqlAgreeHere() {
        for (int user : List.of(contractsDept, finance, scoped, writer, nobody)) {
            assertAgree(user, root.bucketId());
        }
    }

    // ---------------------------------------------------------------- the property, on random trees

    @Test
    @DisplayName("on random trees, classes, moves and grants: Java, SQL and an oracle from the definitions agree on every folder")
    void randomTreesAgree() {
        for (long seed = 1; seed <= 4; seed++) {
            randomCase(new Random(seed));
        }
    }

    private void randomCase(Random random) {
        int n = TestData.nextSequence();
        List<Integer> randomClasses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            randomClasses.add(classService.create(ClassFixture.definition("r" + n + "x" + i, "r" + i, false, "NONE", null, List.of()), adminId));
        }
        int bucket = folderService.create(folderRepository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId(),
                "R" + n, "R" + n, null, "r" + n, adminId).id();
        List<Integer> folders = new ArrayList<>(List.of(bucket));
        for (int i = 0; i < 90; i++) {
            int parent = folders.get(random.nextInt(folders.size()));
            if (depthOf(parent) >= 7) {
                continue;
            }
            folders.add(folderService.create(parent, "f" + i, "f" + i, null, null, adminId).id());
        }
        // classes set, changed and taken away in any order: each rewrites the class paths below
        for (int i = 0; i < 40; i++) {
            int folder = folders.get(1 + random.nextInt(folders.size() - 1));
            Integer classId = random.nextInt(5) == 0 ? null : randomClasses.get(random.nextInt(randomClasses.size()));
            assignment.set(folder, classId, adminId);
        }
        // and moves, which rewrite them too
        for (int i = 0; i < 10; i++) {
            int folder = folders.get(1 + random.nextInt(folders.size() - 1));
            int target = folders.get(random.nextInt(folders.size()));
            try {
                folderService.move(folder, target, adminId);
            } catch (RuntimeException refused) {
                // into itself, too deep, a name taken: not what this asks
            }
            flush();
        }
        flush();
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();

        List<Integer> users = new ArrayList<>();
        for (int u = 0; u < 5; u++) {
            List<RoleClassGrantService.Requested> grants = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (int g = 0; g < 1 + random.nextInt(3); g++) {
                int classId = randomClasses.get(random.nextInt(randomClasses.size()));
                Integer scope = random.nextBoolean() ? null : folders.get(random.nextInt(folders.size()));
                if (used.add(classId + ":" + scope)) {
                    grants.add(new RoleClassGrantService.Requested(classId, random.nextBoolean() ? "READ" : "WRITE",
                            scope == null ? "" : namesOf(scope)));
                }
            }
            int user = userWith(grants.toArray(RoleClassGrantService.Requested[]::new));
            if (random.nextBoolean()) {
                grantDirectly(user, folders.get(random.nextInt(folders.size())), random.nextBoolean());
            }
            users.add(user);
        }
        flush();
        for (int user : users) {
            assertAgree(user, bucket);
        }
    }

    // ---------------------------------------------------------------- the comparison

    /** Read: oracle = Java = SQL; write: oracle = Java; visible: oracle = Java = SQL - for every folder below {@code bucket}. */
    private void assertAgree(int userId, int bucket) {
        Tree tree = treeUnder(bucket);
        Oracle oracle = oracleFor(userId, tree);
        FolderAccess access = folderAccessService.accessFor(userId);
        FolderReadScope scope = folderAccessService.readScope(access, userId);
        Set<Integer> sqlReadable = sql(ReadScopeSql.granted(scope), scope, ReadScopeSql.readable(scope, "f"), tree.bucketPath);
        Set<Integer> sqlVisible = sql(ReadScopeSql.grantedWithSignposts(scope), scope, ReadScopeSql.visible(scope, "f"), tree.bucketPath);
        for (Node node : tree.below()) {
            boolean read = oracle.readable.contains(node.id);
            assertThat(access.canRead(node.path)).as("Java read of %s for user %s", node.path, userId).isEqualTo(read);
            assertThat(sqlReadable.contains(node.id)).as("SQL read of %s for user %s", node.path, userId).isEqualTo(read);
            assertThat(access.canWrite(node.path)).as("Java write of %s for user %s", node.path, userId)
                    .isEqualTo(oracle.writable.contains(node.id));
            boolean visible = oracle.visible.contains(node.id);
            assertThat(access.visible(node.path)).as("Java visible %s for user %s", node.path, userId).isEqualTo(visible);
            assertThat(sqlVisible.contains(node.id)).as("SQL visible %s for user %s", node.path, userId).isEqualTo(visible);
        }
    }

    private Set<Integer> sql(String with, FolderReadScope scope, String condition, String below) {
        if (scope.nothing()) {
            return Set.of();
        }
        String statement = with + "\nSELECT f.id FROM folder f WHERE f.path LIKE :below || '%' AND " + condition;
        return new HashSet<>(namedJdbc.queryForList(statement, ReadScopeSql.parameters(scope).addValue("below", below), Integer.class));
    }

    private record Node(int id, Integer parentId, String path, Integer classId) {
    }

    /** Every folder from the root down to the bucket and below it - a class or a scope above the bucket counts too. */
    private record Tree(String bucketPath, Map<Integer, Node> all) {

        List<Node> below() {
            return all.values().stream().filter(node -> node.path().startsWith(bucketPath)).toList();
        }

        /** The node and every node above it. */
        List<Node> selfAndAbove(Node node) {
            List<Node> chain = new ArrayList<>();
            for (Node at = node; at != null; at = at.parentId() == null ? null : all.get(at.parentId())) {
                chain.add(at);
            }
            return chain;
        }
    }

    private Tree treeUnder(int bucket) {
        String bucketPath = pathOf(bucket);
        Map<Integer, Node> nodes = new HashMap<>();
        jdbcTemplate.query("SELECT id, parent_id, path, class_id FROM folder WHERE path LIKE ? || '%' OR ? LIKE path || '%'",
                rs -> {
                    nodes.put(rs.getInt("id"), new Node(rs.getInt("id"), (Integer) rs.getObject("parent_id"), rs.getString("path"),
                            (Integer) rs.getObject("class_id")));
                }, bucketPath, bucketPath);
        return new Tree(bucketPath, nodes);
    }

    private record Oracle(Set<Integer> readable, Set<Integer> writable, Set<Integer> visible) {
    }

    /**
     * From the definitions alone: a folder grant covers its folder and what is below it; a class grant covers a
     * folder within its scope (or anywhere) whose own class or an ancestor's is the class. Visible: readable, or
     * above something readable.
     */
    private Oracle oracleFor(int userId, Tree tree) {
        Set<Integer> readable = new TreeSet<>();
        Set<Integer> writable = new TreeSet<>();
        List<Map<String, Object>> folderGrants = jdbcTemplate.queryForList("""
                SELECT f.path, uf.permission FROM user_folder uf JOIN folder f ON f.id = uf.folder_id WHERE uf.user_id = ?
                UNION ALL
                SELECT f.path, rf.permission FROM user_role ur JOIN role_folder rf ON rf.role_id = ur.role_id
                JOIN folder f ON f.id = rf.folder_id WHERE ur.user_id = ?""", userId, userId);
        List<Map<String, Object>> classGrants = jdbcTemplate.queryForList("""
                SELECT g.class_id, g.permission, s.path AS scope FROM user_role ur JOIN role_class_grant g ON g.role_id = ur.role_id
                LEFT JOIN folder s ON s.id = g.scope_folder_id WHERE ur.user_id = ?""", userId);
        for (Node node : tree.below()) {
            boolean read = false;
            boolean write = false;
            for (Map<String, Object> grant : folderGrants) {
                if (node.path().startsWith((String) grant.get("path"))) {
                    read = true;
                    write |= "WRITE".equals(grant.get("permission"));
                }
            }
            for (Map<String, Object> grant : classGrants) {
                String scope = (String) grant.get("scope");
                int classId = ((Number) grant.get("class_id")).intValue();
                boolean inScope = scope == null || node.path().startsWith(scope);
                boolean ofClass = tree.selfAndAbove(node).stream()
                        .anyMatch(at -> at.classId() != null && at.classId() == classId);
                if (inScope && ofClass) {
                    read = true;
                    write |= "WRITE".equals(grant.get("permission"));
                }
            }
            if (read) {
                readable.add(node.id());
            }
            if (write) {
                writable.add(node.id());
            }
        }
        Set<Integer> visible = new TreeSet<>(readable);
        for (Node node : tree.below()) {
            if (readable.contains(node.id())) {
                for (Node above : tree.selfAndAbove(node)) {
                    if (above.path().startsWith(tree.bucketPath())) {
                        visible.add(above.id());
                    }
                }
            }
        }
        return new Oracle(readable, writable, visible);
    }

    // ---------------------------------------------------------------- fixtures

    private int userWith(RoleClassGrantService.Requested... grants) {
        Role role = roleRepository.save(TestData.role("ClassRole" + TestData.nextSequence()));
        roleClassGrantService.replace(role.getId(), List.of(grants), adminId);
        User user = TestData.user();
        user.getRoles().add(role);
        return userRepository.save(user).getId();
    }

    private void grantDirectly(int userId, int folderId, boolean write) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(),
                write ? FolderPermission.WRITE : FolderPermission.READ));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }

    /** The scope a role page would name it by: the bucket, then its key path. */
    private String namesOf(int folderId) {
        Folder folder = folderRepository.findById(folderId).orElseThrow();
        String[] ids = folder.getPath().split("/");
        String bucket = folderRepository.findById(Integer.valueOf(ids[2])).orElseThrow().getName();
        return folder.getDepth() <= 1 ? bucket : bucket + "/" + folder.getKeyPath();
    }

    private int depthOf(int folderId) {
        return jdbcTemplate.queryForObject("SELECT depth FROM folder WHERE id = ?", Integer.class, folderId);
    }

    private String pathOf(int folderId) {
        return jdbcTemplate.queryForObject("SELECT path FROM folder WHERE id = ?", String.class, folderId);
    }

    private static List<String> names(List<FolderSearchRepository.Match> matches) {
        return matches.stream().map(FolderSearchRepository.Match::name).toList();
    }

    private List<String> listed(int userId) {
        return fileService.getPageFileInfo(100, 0, null, userId).content().stream()
                .map(FileInfoDTO::getFileName).collect(Collectors.toList());
    }

    private FileDetailsDTO upload(int folderId, String fileName) {
        return uploadAs(adminId, folderId, fileName);
    }

    private FileDetailsDTO uploadAs(int userId, int folderId, String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, userId, FileService.PUBLIC);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
