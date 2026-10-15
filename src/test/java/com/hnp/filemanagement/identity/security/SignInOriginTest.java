package com.hnp.filemanagement.identity.security;

import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real sign-in through the form, on a real port (2.16.0): the session it leaves is registered with
 * where it signed in from - which only a running server proves, the request bound by Spring Boot's
 * {@code RequestContextFilter} before the security filters run - and once ended, its next request is
 * sent to the login page saying why.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignInOriginTest extends DatabaseSupport {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/141.0.0.0 Safari/537.36";

    @LocalServerPort
    private int port;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private ActiveUserSessions activeUserSessions;

    @Test
    @DisplayName("a form sign-in is listed with its address and browser; ended, its next page is the login page with a word on why")
    void signInEndAndComeBack() throws Exception {
        User account = TestData.user();
        account.setPassword(passwordEncoder.encode("a password of the test"));
        account = userRepository.save(account);
        int accountId = account.getId();
        HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();

        String loginPage = browser.send(request("/login").GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        Matcher token = CSRF.matcher(loginPage);
        assertThat(token.find()).as("the login form carries its token").isTrue();
        HttpResponse<String> signedIn = browser.send(request("/login")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(account.getUsername())
                        + "&password=" + encode("a password of the test") + "&_csrf=" + encode(token.group(1))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(signedIn.headers().firstValue("Location")).as("signed in, not refused").hasValueSatisfying(location ->
                assertThat(location).doesNotContain("error"));

        try {
            List<ActiveUserSessions.SignedInSession> theirs = activeUserSessions.signedIn().stream()
                    .filter(session -> session.userId() == accountId).toList();
            assertThat(theirs).singleElement().satisfies(session -> {
                assertThat(session.address()).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
                assertThat(session.userAgent()).isEqualTo(BROWSER);
                assertThat(session.signedInAt()).isNotNull();
            });

            assertThat(activeUserSessions.endSession(theirs.getFirst().handle())).isTrue();
            HttpResponse<String> next = browser.send(request("/").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(next.statusCode()).isEqualTo(302);
            assertThat(next.headers().firstValue("Location")).hasValueSatisfying(location ->
                    assertThat(location).endsWith("/login?expired"));
            assertThat(activeUserSessions.signedIn()).as("signed out by then, and forgotten")
                    .noneMatch(session -> session.userId() == accountId);
        } finally {
            activeUserSessions.endSessionsOf(accountId, account.getUsername());
            userRepository.deleteById(accountId);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("User-Agent", BROWSER);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
