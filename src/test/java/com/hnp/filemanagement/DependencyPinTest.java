package com.hnp.filemanagement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dependency overrides that exist for a security reason.
 *
 * <p>Each pin here was added because the version Spring Boot manages, or the one a transitive
 * dependency drags in, carries a published advisory. A pin is easy to delete by accident during a
 * later upgrade - especially a downgrade back to the managed version - so the minimum is asserted
 * rather than trusted. Raising a pin is fine; lowering it fails this test.
 *
 * <p>This is a floor, not a scan: it cannot know about advisories published after it was written.
 * Re-run a real scan of the resolved tree when upgrading, and record the result in
 * {@code docs/issues.md}.
 */
class DependencyPinTest {

    private static String pom;

    private static String pom() throws IOException {
        if (pom == null) {
            pom = Files.readString(Path.of("pom.xml"));
        }
        return pom;
    }

    private static int[] version(String text) {
        String[] parts = text.trim().split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Matcher digits = Pattern.compile("^(\\d+)").matcher(parts[i]);
            numbers[i] = digits.find() ? Integer.parseInt(digits.group(1)) : 0;
        }
        return numbers;
    }

    private static boolean atLeast(String actual, String minimum) {
        int[] a = version(actual);
        int[] m = version(minimum);
        for (int i = 0; i < Math.max(a.length, m.length); i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < m.length ? m[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return true;
    }

    private static String property(String name) throws IOException {
        Matcher matcher = Pattern.compile("<" + name + ">([^<]+)</" + name + ">").matcher(pom());
        assertTrue(matcher.find(), () -> "pom.xml no longer defines <" + name + ">");
        return matcher.group(1);
    }

    /**
     * Boot 4.1.1 manages Tomcat 11.0.24, which carries three CRITICAL advisories. One of them,
     * CVE-2026-68525, is an authorization flaw in FORM authentication - which is exactly how this
     * application signs users in.
     */
    @Test
    void tomcatIsAtOrAboveTheVersionThatFixesTheFormAuthAdvisory() throws IOException {
        String pinned = property("tomcat.version");

        assertThat(atLeast(pinned, "11.0.25"))
                .as("tomcat.version is %s; 11.0.25 fixes CVE-2026-65905, CVE-2026-65182 and "
                        + "CVE-2026-68525 (FORM authentication authorization)", pinned)
                .isTrue();
    }

    /**
     * Testcontainers pulls commons-compress transitively. Neither the Boot BOM nor the
     * Testcontainers BOM manages it, so it needs an explicit dependencyManagement entry - a version
     * property alone silently does nothing, which is how the first attempt at this pin failed.
     */
    @Test
    void commonsCompressIsPinnedThroughDependencyManagement() throws IOException {
        Matcher matcher = Pattern.compile(
                "<artifactId>commons-compress</artifactId>\\s*<version>([^<]+)</version>").matcher(pom());

        assertTrue(matcher.find(), "commons-compress must be pinned in <dependencyManagement>");
        String pinned = matcher.group(1);

        assertThat(atLeast(pinned, "1.26.0"))
                .as("commons-compress is %s; 1.26.0 fixes CVE-2024-25710 and CVE-2024-26308", pinned)
                .isTrue();
        assertTrue(pom().contains("<dependencyManagement>"),
                "the pin only takes effect inside <dependencyManagement>");
    }

    /**
     * Lombok's processor cannot run on a JDK 23+ javac before 1.18.40, and JDK 23 also stopped
     * discovering processors on the classpath - hence the explicit processor path.
     */
    @Test
    void lombokIsUsableOnACurrentJdkAndDeclaredAsAProcessorPath() throws IOException {
        assertThat(atLeast(property("lombok.version"), "1.18.40")).isTrue();
        assertTrue(pom().contains("<annotationProcessorPaths>"),
                "JDK 23+ does not discover annotation processors on the classpath");
    }

    /** The version Boot manages predates Docker context support and cannot find a daemon. */
    @Test
    void testcontainersIsAtOrAboveTheVersionThatFindsDockerDesktop() throws IOException {
        assertThat(atLeast(property("testcontainers.version"), "1.21.0")).isTrue();
    }

    @Test
    void theBuildStillTargetsJava21() throws IOException {
        assertThat(property("java.version")).isEqualTo("21");
    }
}
