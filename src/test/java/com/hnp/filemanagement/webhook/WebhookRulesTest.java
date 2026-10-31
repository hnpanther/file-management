package com.hnp.filemanagement.webhook;

import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.webhook.WebhookRules.Occurrence;
import com.hnp.filemanagement.webhook.WebhookRules.Place;
import com.hnp.filemanagement.webhook.WebhookRules.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The matching of webhooks (roadmap 13.18), in memory: the three scopes at any depth, the recycle bin never, a move
 * matched from either side, the filters - events, extensions, the name's glob, who did it, the keys left out - and
 * the extensions asked only of a rule that filters by them. With the allowlist and the signature, the pieces with no
 * database.
 */
class WebhookRulesTest {

    // root 1 > bucket 10 > a 20 > b 30 > c 40 ; another bucket 11 > d 21 ; the bin 0 > holder 900
    private static final Place C = new Place(40, "/1/10/20/30/40/", "/7/9/");
    private static final Place A = new Place(20, "/1/10/20/", "/7/");
    private static final Place D = new Place(21, "/1/11/21/", "/");
    private static final Place IN_BIN = new Place(900, "/0/900/", "/7/9/");

    @Test
    @DisplayName("SUBTREE matches its folder and every folder below it, at any depth; FOLDER only its own")
    void subtreeAndFolder() {
        Rule subtree = rule(WebhookScope.SUBTREE, 20, null);
        assertThat(subtree.matches(upload(C))).isTrue();
        assertThat(subtree.matches(upload(A))).isTrue();
        assertThat(subtree.matches(upload(D))).isFalse();

        Rule folder = rule(WebhookScope.FOLDER, 20, null);
        assertThat(folder.matches(upload(A))).isTrue();
        assertThat(folder.matches(upload(C))).isFalse();

        assertThat(rule(WebhookScope.SUBTREE, 1, null).matches(upload(D))).as("the root's subtree is every folder").isTrue();
    }

    @Test
    @DisplayName("CLASS matches a folder of the class or below one, anywhere - or only below its folder when it names one")
    void byClass() {
        assertThat(rule(WebhookScope.CLASS, null, 9).matches(upload(C))).isTrue();
        assertThat(rule(WebhookScope.CLASS, null, 9).matches(upload(A))).as("A is class 7 only").isFalse();
        assertThat(rule(WebhookScope.CLASS, null, 7).matches(upload(A))).isTrue();
        assertThat(rule(WebhookScope.CLASS, 30, 9).matches(upload(C))).isTrue();
        assertThat(rule(WebhookScope.CLASS, 21, 9).matches(upload(C))).as("not below its folder").isFalse();
        assertThat(rule(WebhookScope.CLASS, null, 79).matches(upload(new Place(5, "/1/5/", "/7/"))))
                .as("class 7 is not class 79").isFalse();
    }

    @Test
    @DisplayName("nothing in the recycle bin matches - whatever the scope, the class path it kept")
    void neverTheBin() {
        assertThat(rule(WebhookScope.CLASS, null, 9).matches(upload(IN_BIN))).isFalse();
        assertThat(rule(WebhookScope.SUBTREE, 0, null).matches(upload(IN_BIN))).isFalse();
        assertThat(rule(WebhookScope.FOLDER, 900, null).matches(upload(IN_BIN))).isFalse();
    }

    @Test
    @DisplayName("a move matches a subscription on the folder it left as well as on the one it reached")
    void aMoveFromEitherSide() {
        Rule onD = rule(WebhookScope.SUBTREE, 21, null, EnumSet.of(WebhookEvent.MOVED));
        Occurrence outOfD = new Occurrence(FileEvent.FILE_MOVED, C, D, null, "f", () -> Set.of("pdf"));
        Occurrence intoD = new Occurrence(FileEvent.FILE_MOVED, D, C, null, "f", () -> Set.of("pdf"));
        Occurrence elsewhere = new Occurrence(FileEvent.FILE_MOVED, C, A, null, "f", () -> Set.of("pdf"));
        assertThat(onD.matches(outOfD)).isTrue();
        assertThat(onD.matches(intoD)).isTrue();
        assertThat(onD.matches(elsewhere)).isFalse();
    }

    @Test
    @DisplayName("only the events chosen; each history event is one webhook event, and the history's own are none")
    void events() {
        Rule created = rule(WebhookScope.SUBTREE, 10, null, EnumSet.of(WebhookEvent.FILE_CREATED));
        assertThat(created.matches(upload(C))).isTrue();
        assertThat(created.matches(occurrence(FileEvent.VERSION_ADDED, C, null))).isFalse();
        assertThat(WebhookEvent.of(FileEvent.FORMAT_ADDED)).contains(WebhookEvent.REVISION_CREATED);
        assertThat(WebhookEvent.of(FileEvent.REVISION_PURGED)).contains(WebhookEvent.PURGED);
        assertThat(WebhookEvent.of(FileEvent.SHARE_LINK_CREATED)).isEmpty();
        assertThat(WebhookEvent.of(FileEvent.DESCRIPTION_CHANGED)).isEmpty();
        Rule all = rule(WebhookScope.SUBTREE, 10, null, EnumSet.allOf(WebhookEvent.class));
        assertThat(all.matches(occurrence(FileEvent.SHARE_LINK_CREATED, C, null))).isFalse();
    }

    @Test
    @DisplayName("people only, keys only, and the keys left out - the loop guard")
    void actors() {
        Occurrence byPerson = occurrence(FileEvent.FILE_UPLOADED, C, null);
        Occurrence byKey5 = occurrence(FileEvent.FILE_UPLOADED, C, 5);
        Occurrence byKey6 = occurrence(FileEvent.FILE_UPLOADED, C, 6);
        Rule people = new Rule(1, "p", WebhookScope.SUBTREE, 10, null, EnumSet.of(WebhookEvent.FILE_CREATED), Set.of(),
                null, WebhookActors.PEOPLE, Set.of(), true, 60, 2);
        Rule keys = new Rule(1, "k", WebhookScope.SUBTREE, 10, null, EnumSet.of(WebhookEvent.FILE_CREATED), Set.of(),
                null, WebhookActors.KEYS, Set.of(), true, 60, 2);
        Rule notFive = new Rule(1, "n", WebhookScope.SUBTREE, 10, null, EnumSet.of(WebhookEvent.FILE_CREATED), Set.of(),
                null, WebhookActors.ALL, Set.of(5), true, 60, 2);
        assertThat(people.matches(byPerson)).isTrue();
        assertThat(people.matches(byKey5)).isFalse();
        assertThat(keys.matches(byPerson)).isFalse();
        assertThat(keys.matches(byKey5)).isTrue();
        assertThat(notFive.matches(byKey5)).isFalse();
        assertThat(notFive.matches(byKey6)).isTrue();
        assertThat(notFive.matches(byPerson)).isTrue();
    }

    @Test
    @DisplayName("extensions and the name's glob, without case; the extensions asked only when a rule filters by them")
    void filters() {
        AtomicInteger asked = new AtomicInteger();
        Occurrence invoice = new Occurrence(FileEvent.FILE_UPLOADED, C, null, null, "Invoice-2026",
                () -> {
                    asked.incrementAndGet();
                    return Set.of("pdf");
                });
        Rule plain = rule(WebhookScope.SUBTREE, 10, null);
        assertThat(plain.matches(invoice)).isTrue();
        assertThat(asked).as("no filter, no question").hasValue(0);

        Rule pdf = withFilters(Set.of("pdf", "docx"), null);
        Rule docx = withFilters(Set.of("docx"), null);
        Rule glob = withFilters(Set.of(), "invoice-*.PDF");
        Rule globMiss = withFilters(Set.of(), "invoice-?.pdf");
        assertThat(pdf.matches(invoice)).isTrue();
        assertThat(docx.matches(invoice)).isFalse();
        assertThat(glob.matches(invoice)).isTrue();
        assertThat(globMiss.matches(invoice)).as("? is one character").isFalse();
        assertThat(asked.get()).isPositive();

        // A file of several formats matches when one of them does.
        Occurrence both = new Occurrence(FileEvent.FILE_DELETED, C, null, null, "report", () -> Set.of("pdf", "docx"));
        assertThat(docx.matches(both)).isTrue();
        assertThat(withFilters(Set.of(), "*.docx").matches(both)).isTrue();
    }

    @Test
    @DisplayName("a glob's other characters are themselves - a dot, a bracket, a plus are not a pattern")
    void globIsLiteral() {
        assertThat(WebhookRules.glob("a+b[1].pdf").matcher("a+b[1].pdf").matches()).isTrue();
        assertThat(WebhookRules.glob("a+b[1].pdf").matcher("aab1xpdf").matches()).isFalse();
        assertThat(WebhookRules.glob("گزارش*").matcher("گزارش ماهانه.pdf").matches()).isTrue();
        assertThat(WebhookRules.glob("  ")).isNull();
    }

    @Test
    @DisplayName("the stored lists read back: events by name, extensions lower case without a dot, ids - nonsense left out")
    void storedLists() {
        assertThat(WebhookRules.events("FILE_CREATED, MOVED,NO_SUCH")).containsExactlyInAnyOrder(
                WebhookEvent.FILE_CREATED, WebhookEvent.MOVED);
        assertThat(WebhookRules.extensions(".PDF, docx")).containsExactlyInAnyOrder("pdf", "docx");
        assertThat(WebhookRules.ids("3, x, 12")).containsExactlyInAnyOrder(3, 12);
        assertThat(WebhookRules.idsInOrder("/1/10/20/")).containsExactly(1, 10, 20);
    }

    @Test
    @DisplayName("matching: every rule that matches, paused ones too - their deliveries wait")
    void matchingKeepsPaused() {
        Rule paused = new Rule(2, "paused", WebhookScope.SUBTREE, 10, null, EnumSet.of(WebhookEvent.FILE_CREATED),
                Set.of(), null, WebhookActors.ALL, Set.of(), false, 60, 2);
        WebhookRules rules = new WebhookRules(List.of(rule(WebhookScope.SUBTREE, 10, null), paused,
                rule(WebhookScope.FOLDER, 21, null)));
        assertThat(rules.matching(upload(C))).extracting(Rule::name).containsExactly("r", "paused");
    }

    // ------------------------------------------------------------------ the allowlist

    @Test
    @DisplayName("an address is allowed by its host, by its host and port, or below a *.domain - http as well as https")
    void allowlist() {
        List<String> allowed = List.of("n8n.example.com", "hooks.local:8080", "*.corp.ir", "127.0.0.1", "[::1]:9000");
        assertThat(WebhookHosts.allowed(URI.create("https://n8n.example.com/webhook/x"), allowed)).isTrue();
        assertThat(WebhookHosts.allowed(URI.create("http://n8n.example.com:5678/webhook/x"), allowed)).as("http, any port").isTrue();
        assertThat(WebhookHosts.allowed(URI.create("http://N8N.Example.COM/x"), allowed)).as("without case").isTrue();
        assertThat(WebhookHosts.allowed(URI.create("http://hooks.local:8080/x"), allowed)).isTrue();
        assertThat(WebhookHosts.allowed(URI.create("http://hooks.local/x"), allowed)).as("port 80 is not 8080").isFalse();
        assertThat(WebhookHosts.allowed(URI.create("https://erp.corp.ir/x"), allowed)).isTrue();
        assertThat(WebhookHosts.allowed(URI.create("https://corp.ir/x"), allowed)).as("not the domain itself").isFalse();
        assertThat(WebhookHosts.allowed(URI.create("https://evilcorp.ir/x"), allowed)).isFalse();
        assertThat(WebhookHosts.allowed(URI.create("http://127.0.0.1:1234/x"), allowed)).isTrue();
        assertThat(WebhookHosts.allowed(URI.create("http://[::1]:9000/x"), allowed)).isTrue();
        assertThat(WebhookHosts.allowed(URI.create("http://[::1]:9001/x"), allowed)).isFalse();
        assertThat(WebhookHosts.allowed(URI.create("https://n8n.example.com.attacker.io/x"), allowed)).isFalse();
        assertThat(WebhookHosts.allowed(URI.create("https://user:pw@n8n.example.com/x"), allowed)).as("no user in it").isFalse();
        assertThat(WebhookHosts.allowed(URI.create("ftp://n8n.example.com/x"), allowed)).isFalse();
        assertThat(WebhookHosts.allowed(URI.create("https://n8n.example.com/x"), List.of())).as("an empty list allows none").isFalse();
    }

    // ------------------------------------------------------------------ the signature

    @Test
    @DisplayName("the signature: t and an HMAC-SHA256 of t.body - and a second v1 under the previous secret")
    void signature() {
        String header = WebhookSender.signature(1_700_000_000L, "{\"a\":1}", "whsec_new", null);
        assertThat(header).isEqualTo("t=1700000000,v1=" + WebhookSender.hmac("whsec_new", "1700000000.{\"a\":1}"));
        // A known vector: HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog").
        assertThat(WebhookSender.hmac("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        String both = WebhookSender.signature(1L, "x", "new", "old");
        assertThat(both).isEqualTo("t=1,v1=" + WebhookSender.hmac("new", "1.x") + ",v1=" + WebhookSender.hmac("old", "1.x"));
    }

    @Test
    @DisplayName("a key in a body is URL-encoded per segment, as S3 writes it; a new secret is whsec_ and 43 characters")
    void keysAndSecrets() {
        assertThat(WebhookPayloads.encodeKey("Contracts/A 1/گزارش.pdf"))
                .isEqualTo("Contracts/A+1/%DA%AF%D8%B2%D8%A7%D8%B1%D8%B4.pdf");
        String secret = WebhookSecrets.newSigningSecret();
        assertThat(secret).startsWith("whsec_").hasSize(6 + 43).doesNotContain("=", "+", "/");
        assertThat(WebhookSecrets.newSigningSecret()).isNotEqualTo(secret);
    }

    // ------------------------------------------------------------------ pieces

    private static Rule rule(WebhookScope scope, Integer folderId, Integer classId) {
        return rule(scope, folderId, classId, EnumSet.of(WebhookEvent.FILE_CREATED));
    }

    private static Rule rule(WebhookScope scope, Integer folderId, Integer classId, Set<WebhookEvent> events) {
        return new Rule(1, "r", scope, folderId, classId, events, Set.of(), null, WebhookActors.ALL, Set.of(), true, 60, 2);
    }

    private static Rule withFilters(Set<String> extensions, String glob) {
        return new Rule(1, "f", WebhookScope.SUBTREE, 10, null, EnumSet.of(WebhookEvent.FILE_CREATED, WebhookEvent.FILE_DELETED),
                extensions, WebhookRules.glob(glob), WebhookActors.ALL, Set.of(), true, 60, 2);
    }

    private static Occurrence upload(Place place) {
        return occurrence(FileEvent.FILE_UPLOADED, place, null);
    }

    private static Occurrence occurrence(FileEvent event, Place place, Integer key) {
        return new Occurrence(event, place, null, key, "file", () -> Set.of("pdf"));
    }
}
