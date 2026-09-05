package com.hnp.filemanagement.util;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place that formats the "who did what, where" line every handler writes.
 *
 * <p>Handlers used to rebuild the principal id, the username and the request path inline - six
 * lines repeated in roughly sixty methods. {@link #controllerLogging(UserDetailsImpl,
 * HttpServletRequest, Class, String)} does that once. The older signature is kept for the
 * Thymeleaf controllers that have not been converted yet; prefer the request-aware one in anything
 * new.
 *
 * <p>Never pass a JPA entity as {@code message}: {@code FileInfo} and {@code FileDetails} are
 * bidirectional and both are {@code @Data}, so {@code toString()} recurses until the stack
 * overflows. Log an id.
 */
@Component
public class GlobalGeneralLogging {

    private static final Logger logger = LoggerFactory.getLogger(GlobalGeneralLogging.class);

    /**
     * Writes the audit-style debug line for a request.
     *
     * @param principal the signed-in user, or {@code null} for an anonymous request
     * @param request   the current request; supplies method, URI and query string
     * @param source    the handler class, used only to label the line
     * @param message   what the handler is about to do - an id, never an entity
     */
    public void controllerLogging(UserDetailsImpl principal, HttpServletRequest request,
                                  Class<?> source, String message) {
        controllerLogging(
                principal == null ? 0 : principal.getId(),
                principal == null ? "anonymous" : principal.getUsername(),
                request.getMethod() + " " + fullPath(request),
                source.getSimpleName(),
                message);
    }

    public void controllerLogging(int principalId, String principalUsername, String path, String className, String message) {
        logger.debug("[GlobalGeneralLogging-Controller][class={}][username={}][userId={}][path={}]: {}",
                className, principalUsername, principalId, path, message);
    }

    public void serviceLogging(String methodName, String className, String message) {
        logger.debug("[GlobalGeneralLogging-Service][class={}][method={}]: {}", className, methodName, message);
    }

    /** Request URI with its query string, which is what makes a logged path reproducible. */
    public static String fullPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}
