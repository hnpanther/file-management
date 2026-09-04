package com.hnp.filemanagement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the interface copy in {@code messages.properties}: every key a template asks for has to
 * exist, and the templates that have been converted must not slip back to inline Persian.
 */
class MessageBundleTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path TEMPLATES = RESOURCES.resolve("templates");
    private static final Path BUNDLE = RESOURCES.resolve("messages.properties");

    /** #{key} and #{key(args)} - the argument list is not part of the key. */
    private static final Pattern MESSAGE_KEY = Pattern.compile("#\\{([A-Za-z0-9_.]+)");
    private static final Pattern PERSIAN = Pattern.compile("[\\u0600-\\u06ff]");

    /** Templates whose copy has been moved into the bundle. Extend as the rest are converted. */
    private static final List<String> EXTERNALISED = List.of(
            "navbar.html", "security/login.html", "error.html",
            "file-management/category/categories.html",
            "file-management/category/save-category.html",
            "file-management/files/file-info-page.html",
            "file-management/files/file-info.html",
            "file-management/files/files-public.html",
            "file-management/files/new-file-details.html",
            "file-management/files/save-file.html",
            "file-management/general-tag/general-tags.html",
            "file-management/general-tag/save-general-tag.html",
            "file-management/main-tag/main-tags.html",
            "file-management/main-tag/save-main-tag.html",
            "file-management/sub-category/save-sub-category.html",
            "file-management/sub-category/sub-categories.html",
            "role/roles.html",
            "role/save-role.html",
            "user/save-user.html",
            "user/user-change-password.html",
            "user/user-profile.html",
            "user/user-role.html",
            "user/users.html");

    private Properties bundle() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(BUNDLE)) {
            // Spring reads the bundle as UTF-8 (spring.messages.encoding), so the test must too.
            properties.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    void bundleIsReadableAsUtf8AndCarriesPersianCopy() throws IOException {
        Properties properties = bundle();

        assertThat(properties).isNotEmpty();
        assertThat(properties.getProperty("app.name"))
                .as("the bundle must decode as UTF-8, not mojibake")
                .isNotNull()
                .matches(value -> PERSIAN.matcher(value).find());
    }

    @Test
    void everyMessageKeyUsedByATemplateExists() throws IOException {
        Properties properties = bundle();
        Set<String> missing = new TreeSet<>();

        for (Path template : htmlFiles()) {
            Matcher matcher = MESSAGE_KEY.matcher(Files.readString(template));
            while (matcher.find()) {
                String key = matcher.group(1);
                if (properties.getProperty(key) == null) {
                    missing.add(key + "  (" + template + ")");
                }
            }
        }

        assertThat(missing)
                .as("templates reference message keys that messages.properties does not define")
                .isEmpty();
    }

    @Test
    void everyMessageKeyUsedByJavaCodeExists() throws IOException {
        Properties properties = bundle();
        Pattern javaKey = Pattern.compile("getMessage\\(\"([A-Za-z0-9_.]+)\"|message\\(\"([A-Za-z0-9_.]+)\"");
        Set<String> missing = new TreeSet<>();

        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path java : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = javaKey.matcher(Files.readString(java));
                while (matcher.find()) {
                    String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (properties.getProperty(key) == null) {
                        missing.add(key + "  (" + java + ")");
                    }
                }
            }
        }

        assertThat(missing).isEmpty();
    }

    /**
     * Persian between tags is Thymeleaf prototype text - it is replaced at render time and is
     * useful when opening a template in a browser. Persian inside an <em>attribute</em> is not:
     * nothing replaces it, so it is a string that escaped the bundle.
     */
    @Test
    void convertedTemplatesHoldNoPersianInsideAttributes() throws IOException {
        Pattern persianAttribute = Pattern.compile(
                "[\\w:.-]+\\s*=\\s*\"[^\"]*[\\u0600-\\u06ff][^\"]*\"");

        for (String name : EXTERNALISED) {
            Path template = TEMPLATES.resolve(name);
            Matcher matcher = persianAttribute.matcher(Files.readString(template));

            Set<String> offenders = new TreeSet<>();
            while (matcher.find()) {
                offenders.add(matcher.group());
            }

            assertThat(offenders)
                    .as("%s holds Persian in an attribute; move it to messages.properties", template)
                    .isEmpty();
        }
    }

    @Test
    void convertedTemplatesActuallyUseTheBundle() throws IOException {
        for (String name : EXTERNALISED) {
            Path template = TEMPLATES.resolve(name);
            Matcher matcher = MESSAGE_KEY.matcher(Files.readString(template));

            Set<String> used = new TreeSet<>();
            while (matcher.find()) {
                used.add(matcher.group(1));
            }

            assertThat(used)
                    .as("%s is listed as externalised but references no message keys", template)
                    .isNotEmpty();
        }
    }

    private List<Path> htmlFiles() throws IOException {
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".html")).toList();
        }
    }
}
