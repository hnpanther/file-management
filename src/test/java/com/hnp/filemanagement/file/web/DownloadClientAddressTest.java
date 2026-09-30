package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadRecorder;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address a download is recorded with is the client's, through the real server (2.7.0): behind
 * the reverse proxy the deployment puts in front, the proxy's {@code X-Forwarded-For}, which Tomcat
 * honours from a loopback or private address ({@code server.forward-headers-strategy=native}); with
 * no proxy, the connection's own. MockMvc has no Tomcat, so this runs on a port.
 *
 * <p>Not {@code @Transactional} - the server answers on threads of its own - so what it writes
 * stays; a file and a user of its own, and folder access off rather than a role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=false",
        // The main application.properties sets it; the tests' own file, which replaces it, does not.
        "server.forward-headers-strategy=native"})
class DownloadClientAddressTest extends DatabaseSupport {

    @LocalServerPort
    private int port;
    @Autowired
    private DownloadRecorder downloadRecorder;
    @Autowired
    private FileService fileService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Test
    @DisplayName("through a proxy on loopback the forwarded client is recorded; with no proxy, the connection's address")
    void theClientsAddress() throws Exception {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        String name = "address" + TestData.nextSequence() + ".txt";
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("address");
        request.setFileNameDescription(name);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", TestData.bytesFor(name)));
        FileDetailsDTO revision = fileService.createNewFile(request, owner.getId(), FileService.PUBLIC);

        URI download = URI.create("http://localhost:" + port + "/files/public-download/" + revision.getId());
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<byte[]> proxied = http.send(HttpRequest.newBuilder(download)
                    .header("X-Forwarded-For", "203.0.113.9").build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> direct = http.send(HttpRequest.newBuilder(download).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(proxied.statusCode()).isEqualTo(200);
            assertThat(direct.statusCode()).isEqualTo(200);
            assertThat(direct.body()).isEqualTo(TestData.bytesFor(name));
        }
        downloadRecorder.flush();

        assertThat(jdbcTemplate.queryForList(
                "SELECT client_ip FROM file_download WHERE file_info_id = ? ORDER BY id", String.class, revision.getFileInfoId()))
                .hasSize(2)
                .first().isEqualTo("203.0.113.9");
        assertThat(jdbcTemplate.queryForList(
                "SELECT client_ip FROM file_download WHERE file_info_id = ? ORDER BY id", String.class, revision.getFileInfoId())
                .get(1)).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }
}
