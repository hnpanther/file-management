package com.hnp.filemanagement;

import com.hnp.filemanagement.support.DatabaseSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint of this application states who may call it (2.7.4). A handler without
 * {@code @PreAuthorize} is reachable by anyone the filter chain lets through - every signed-in
 * person, whatever their role - and nothing fails to say so: the page simply works for everybody.
 * The few that are meant to be open are named here, each with the reason; a new one fails this
 * test until it is guarded or added, with its reason, on purpose.
 */
@SpringBootTest
class EndpointGuardTest extends DatabaseSupport {

    /** Open on purpose, as "Class#method", with why. */
    private static final Set<String> OPEN = Set.of(
            // The landing redirect and the page a refusal is shown on: they disclose nothing and
            // decide nothing - every page behind them is guarded on its own.
            "HomeController#home",
            "SecurityController#accessDenied",
            // Signing in: before any principal exists.
            "SecurityController#loginPage",
            // The public files: anonymous by design, closed to visitors by one setting
            // (AppSettingService.isPublicFilesAnonymous, enforced in SecurityConfig); a download
            // there is of a public revision only (FileService.downloadPublicFile).
            "FileController#getAllPublicFile",
            "FileController#downloadPublicFile",
            // A share link: the token is the access - its expiry, password, lock and count are
            // checked in ShareLinkService.
            "ShareLinkController#landing",
            "ShareLinkController#download",
            // Spring Boot's error page, served to whoever reached the application.
            "BasicErrorController#error",
            "BasicErrorController#errorHtml");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("every handler carries @PreAuthorize, but for the few that are open on purpose")
    void everyHandlerIsGuarded() {
        Set<String> unguarded = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.hnp.filemanagement")
                    && !handler.getBeanType().getSimpleName().equals("BasicErrorController")) {
                continue;
            }
            String name = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
            seen.add(name);
            boolean guarded = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
            if (!guarded && !OPEN.contains(name)) {
                unguarded.add(name + " " + entry.getKey());
            }
        }
        assertThat(unguarded).as("handlers without @PreAuthorize").isEmpty();
        assertThat(seen).as("every open handler still exists").containsAll(
                OPEN.stream().filter(name -> !name.startsWith("BasicErrorController")).toList());
    }
}
