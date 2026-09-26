package com.hnp.filemanagement;

import com.hnp.filemanagement.identity.domain.PermissionEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code hasAuthority('X')} names a real permission.
 *
 * <p>The guard on a handler is a string, and the permission table is seeded from
 * {@link PermissionEnum} - so a name that drifts from the enum does not fail to compile, it
 * fails to be grantable: nobody but {@code ADMIN} can ever reach that endpoint, silently.
 * {@code GET /users/create} was in exactly that state ({@code CREATE_NEW_USER} against the
 * enum's {@code CREATE_NEW_USER_PAGE}, {@code docs/issues.md} issue 84). This reads every Java
 * source under {@code src/main/java} and checks each name against the enum.
 *
 * <p>The same check covers the templates' {@code sec:authorize}, where a drifted name hides a
 * control from everyone who is not an administrator.
 */
class PermissionNamesTest {

    private static final Pattern AUTHORITY = Pattern.compile("hasAuthority\\('([^']*)'\\)");

    @Test
    @DisplayName("every hasAuthority in the Java sources names a PermissionEnum constant")
    void javaGuardsNameRealPermissions() throws IOException {
        assertThat(namesUnder(Paths.get("src/main/java"), ".java")).isSubsetOf(permissionNames());
    }

    @Test
    @DisplayName("every hasAuthority in the templates names a PermissionEnum constant")
    void templateGuardsNameRealPermissions() throws IOException {
        assertThat(namesUnder(Paths.get("src/main/resources/templates"), ".html")).isSubsetOf(permissionNames());
    }

    private static Set<String> permissionNames() {
        return EnumSet.allOf(PermissionEnum.class).stream().map(Enum::name).collect(Collectors.toSet());
    }

    private static List<String> namesUnder(Path root, String extension) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(extension)).toList()) {
                Matcher matcher = AUTHORITY.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        assertThat(names).as("the scan found guards at all under %s", root).isNotEmpty();
        return names;
    }
}
