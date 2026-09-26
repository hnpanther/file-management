package com.hnp.filemanagement.web;

import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.ApiKeyService;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API v2 end to end, driven by an API key exactly as an integration would drive it (roadmap 9.3).
 *
 * <p>Every request authenticates with {@code Authorization: Bearer fmk_…} rather than a mock
 * principal, and folder access is switched on. Both matter: the key's own scopes are what decides
 * each answer, and a mocked principal with the flag off would exercise the handlers while skipping
 * the thing they exist to enforce.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class ObjectStoreApiTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;


    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private com.hnp.filemanagement.repository.FileInfoRepository fileInfoRepository;

    private int creatorId;
    private String bucket;
    private String prefix;
    private int bucketFolderId;
    private int tagFolderId;
    private int otherTagFolderId;
    private String otherTagName;

    /** One category (the bucket), one sub-category, two tags — one to reach and one to be refused. */
    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        creatorId = owner.getId();

        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        bucket = chain.category().getName();
        bucketFolderId = chain.categoryId();
        tagFolderId = chain.tagId();
        Folder other = FolderFixture.tag(folderRepository, chain.subCategory(), owner, "Other" + TestData.nextSequence());
        otherTagName = other.getName();
        otherTagFolderId = other.getId();

        prefix = chain.subCategory().getName() + "/" + chain.tag().getName();
    }

    // ---------------------------------------------------------------- the whole lifecycle

    @Test
    @DisplayName("a document is written, listed, downloaded and deleted through one key")
    void theWholeObjectLifecycle() throws Exception {
        String credential = keyWith(tagFolderId + ":WRITE");
        String writeKey = prefix + "/report/report.txt";

        // 1. PUT with no version segment. The server assigns the version and answers with the
        //    canonical key, which is where the object can then be read from.
        String created = mockMvc.perform(put("/api/v2/" + bucket + "/" + writeKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(header().string("x-fm-version", "1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.key").value(prefix + "/report/v1/report.txt"))
                .andReturn().getResponse().getContentAsString();

        String storedKey = prefix + "/report/v1/report.txt";

        // 2. It appears in the listing, under the key the write reported.
        mockMvc.perform(get("/api/v2/{bucket}", bucket)
                        .param("prefix", prefix + "/")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contents[0].key").value(storedKey))
                .andExpect(jsonPath("$.contents[0].size").value(5));

        // 3. Its metadata, and then its bytes.
        mockMvc.perform(get("/api/v2/" + bucket + "/" + storedKey).param("metadata", "")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.size").value(5));

        mockMvc.perform(get("/api/v2/" + bucket + "/" + storedKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(header().string("x-fm-version", "1"))
                .andExpect(content().string("hello"));

        mockMvc.perform(head("/api/v2/" + bucket + "/" + storedKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(header().string("x-fm-version", "1"))
                .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, "5"))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
                .andExpect(content().string(""));

        // 4. A second write is a second version, not an overwrite.
        mockMvc.perform(put("/api/v2/" + bucket + "/" + writeKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello again".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/v2/" + bucket + "/" + storedKey)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(content().string("hello"));

        // 5. Deleting names the version, because that is what a stored object is.
        mockMvc.perform(delete("/api/v2/" + bucket + "/" + prefix + "/report/v2/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/report/v2/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isNotFound());
    }

    /**
     * Immutability, which is the deliberate departure from S3: there a {@code PUT} to an existing
     * key replaces it, and here a stored version is a thing other documents cite.
     */
    @Test
    @DisplayName("a stored version cannot be overwritten by naming it")
    void aStoredVersionCannotBeReplaced() throws Exception {
        String credential = keyWith(tagFolderId + ":WRITE");

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("first".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/v1/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("second".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("cannot be replaced")));
    }

    /**
     * File names are unique per folder, and since {@code V2.9} the bytes are too (a directory per
     * folder id), so the same name under a sibling folder is another file - not a conflict and
     * not a version of the first. Each write lands where its key says, and the bucket folder
     * itself takes a file as well.
     */
    @Test
    @DisplayName("the same name under a sibling folder, or in the bucket itself, is another file")
    void theSameNameElsewhereIsAnotherFile() throws Exception {
        String credential = keyWith(tagFolderId + ":WRITE", otherTagFolderId + ":WRITE", bucketFolderId + ":WRITE");
        String otherPrefix = prefix.substring(0, prefix.indexOf('/')) + "/" + otherTagName;

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("a".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(put("/api/v2/" + bucket + "/" + otherPrefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("b".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.key").value(otherPrefix + "/report/v1/report.txt"));

        mockMvc.perform(put("/api/v2/" + bucket + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("c".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("report/v1/report.txt"));

        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/report/v2/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                // nothing was appended to the first file
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v2/" + bucket + "/report/v1/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(content().string("c"));
    }

    /**
     * The key is taken from the URL <em>decoded</em>. The first version sliced it out of the raw
     * request line, so a Persian file name - which this installation is full of - was stored as
     * {@code %DA%AF%D8%B2…}, on disk and in {@code file_info}, and could then only be read back
     * by sending the percent-encoded form.
     */
    @Test
    @DisplayName("a key with non-ASCII characters is stored and read back as written")
    void aNonAsciiKeyIsStoredAsWritten() throws Exception {
        String credential = keyWith(tagFolderId + ":WRITE");
        String name = "گزارش"; // Persian for "report"

        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/" + name + "/" + name + ".txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("x".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(prefix + "/" + name + "/v1/" + name + ".txt"));

        // And it downloads under that name: percent-encoded in filename*, since a raw Persian
        // header is one Tomcat drops before it is sent (issue 85).
        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/" + name + "/v1/" + name + ".txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("filename*=UTF-8''%DA%AF%D8%B2%D8%A7%D8%B1%D8%B4.txt")))
                .andExpect(content().string("x"));

        org.assertj.core.api.Assertions.assertThat(fileInfoRepository.findAll())
                .extracting(com.hnp.filemanagement.entity.FileInfo::getFileName)
                .contains(name)
                .noneMatch(stored -> stored.contains("%"));
    }

    /** What the roadmap promises of a download: {@code Range}, {@code Last-Modified}, {@code ETag}. */
    @Test
    @DisplayName("a download honours Range and carries the caching headers")
    void aDownloadHonoursRange() throws Exception {
        String credential = keyWith(tagFolderId + ":WRITE");
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("hello".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/report/v1/report.txt")
                        .header(HttpHeaders.RANGE, "bytes=1-2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 1-2/5"))
                .andExpect(header().exists(HttpHeaders.LAST_MODIFIED))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(content().string("el"));
    }

    // ---------------------------------------------------------------- listing

    @Test
    @DisplayName("an empty bucket lists nothing rather than failing")
    void anEmptyBucketListsNothing() throws Exception {
        mockMvc.perform(get("/api/v2/{bucket}", bucket)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyWith(tagFolderId + ":READ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucket").value(bucket))
                .andExpect(jsonPath("$.contents").isArray())
                .andExpect(jsonPath("$.truncated").value(false));
    }

    /**
     * The delimiter is what makes a flat key space browsable: with it the listing stops at each
     * boundary and reports the boundaries instead of everything beneath them.
     */
    @Test
    @DisplayName("a delimiter rolls keys up into common prefixes")
    void aDelimiterGroupsKeys() throws Exception {
        String credential = keyWith(tagFolderId + ":WRITE");
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content("x".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v2/{bucket}", bucket).param("delimiter", "/")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commonPrefixes[0]").value(prefix.split("/")[0] + "/"))
                .andExpect(jsonPath("$.contents").isEmpty());
    }

    @Test
    @DisplayName("the bucket name is matched the way it is written, not the way it is stored")
    void bucketNamesAreNormalised() throws Exception {
        mockMvc.perform(get("/api/v2/{bucket}", bucket.toLowerCase().replace('_', '-'))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyWith(tagFolderId + ":READ")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a bucket that does not exist is 404, not 403")
    void anUnknownBucketIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v2/{bucket}", "no-such-bucket")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyWith(tagFolderId + ":READ")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- what a key may not do

    @Test
    @DisplayName("a key with no scopes reaches no bucket at all")
    void aKeyWithoutScopesReachesNothing() throws Exception {
        mockMvc.perform(get("/api/v2/{bucket}", bucket)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyWith()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("without a credential the v2 API is 401, like the rest of /api")
    void withoutACredentialItIs401() throws Exception {
        mockMvc.perform(get("/api/v2/{bucket}", bucket))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    /**
     * The same distinction issue 76 was about, arriving at a different door: reading a folder is not
     * permission to file documents into it.
     */
    @Test
    @DisplayName("a read-only key cannot write")
    void aReadOnlyKeyCannotWrite() throws Exception {
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyWith(tagFolderId + ":READ"))
                        .contentType(MediaType.TEXT_PLAIN).content("x".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a key scoped to one folder cannot reach another in the same bucket")
    void scopesAreEnforcedWithinABucket() throws Exception {
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/report/report.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + keyWith(otherTagFolderId + ":WRITE"))
                        .contentType(MediaType.TEXT_PLAIN).content("x".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- helpers

    private String keyWith(String... grants) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("v2 " + TestData.nextSequence());
        request.setFolderGrants(List.of(grants));
        return apiKeyService.create(request, creatorId).credential();
    }

}
