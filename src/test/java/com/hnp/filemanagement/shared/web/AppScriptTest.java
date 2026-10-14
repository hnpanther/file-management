package com.hnp.filemanagement.shared.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code static/js/app.js} is one file of components the templates name in {@code x-data}; one of a
 * name defined twice silently replaces the first, and every page that used it breaks with no error
 * (12.8's search folder chooser once took the name of the upload's and the move's).
 */
class AppScriptTest {

    private static final Path SCRIPT = Path.of("src/main/resources/static/js/app.js");
    private static final Pattern DEFINED = Pattern.compile("(?m)^\\s*window\\.([A-Za-z_$][\\w$]*)\\s*=");

    @Test
    @DisplayName("every name app.js puts on window is defined once")
    void eachNameOnce() throws IOException {
        Matcher matcher = DEFINED.matcher(Files.readString(SCRIPT, StandardCharsets.UTF_8));
        List<String> names = new ArrayList<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        assertThat(names).isNotEmpty();
        Map<String, Long> counted = names.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(counted).allSatisfy((name, times) -> assertThat(times).as("window." + name).isEqualTo(1L));
    }
}
