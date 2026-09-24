package com.hnp.filemanagement.util;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * What a handler adds to the line every request already writes.
 *
 * <p><b>The "who did what, where" line is not written here any more</b> (roadmap 2.1,
 * {@code docs/issues.md} issue 25). It was: every handler rebuilt the principal id, the username
 * and the request path and passed them in - six lines repeated in a hundred and twenty methods,
 * every one of them able to drift, and all of them saying what
 * {@code LoggingInterceptor} already says for every request, handler and all. The interceptor is
 * now the one writer of that line, so a handler writes only the part the interceptor cannot know:
 * <em>which</em> folder, <em>which</em> user, <em>which</em> id.
 *
 * <pre>
 *     globalGeneralLogging.detail("create folder " + name + " under folderId=" + parentId);
 * </pre>
 *
 * <p>The principal and the path come from the request in progress, so there is nothing to pass and
 * nothing to get wrong. Outside a request (a scheduled task, a test) the line is still written,
 * with what is known.
 *
 * <p>Never pass a JPA entity as {@code message}. Its {@code toString()} no longer recurses - it is
 * {@code AbstractEntity}'s {@code Type#id} since {@code @Data} left the entities - but an id says
 * as much and cannot start loading anything. Log an id.
 */
@Component
public class GlobalGeneralLogging {

    private static final Logger logger = LoggerFactory.getLogger(GlobalGeneralLogging.class);

    /**
     * Writes what this handler is about to do, against the request in progress.
     *
     * @param message an id, a name, a decision - never an entity
     */
    public void detail(String message) {
        HttpServletRequest request = currentRequest();
        UserDetailsImpl principal = currentPrincipal();
        logger.debug("[detail][username={}][userId={}][path={}]: {}",
                principal == null ? "anonymous" : principal.getUsername(),
                principal == null ? 0 : principal.getId(),
                request == null ? "-" : request.getMethod() + " " + fullPath(request),
                message);
    }

    /** What a service says about itself; the request line is the interceptor's. */
    public void serviceLogging(String methodName, String className, String message) {
        logger.debug("[GlobalGeneralLogging-Service][class={}][method={}]: {}", className, methodName, message);
    }

    /** Request URI with its query string, which is what makes a logged path reproducible. */
    public static String fullPath(HttpServletRequest request) {
        String query = request.getQueryString();
        return maskSecrets(query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query);
    }

    /**
     * A path as it may be written down. A share link's token is the whole access to a file
     * (roadmap 10.5) and travels in the path, so it is never logged or echoed: {@code /share/…}
     * loses its last segment here, and nothing that logs a request bypasses this.
     */
    public static String maskSecrets(String path) {
        if (path == null) {
            return null;
        }
        int at = path.indexOf("/share/");
        if (at < 0) {
            return path;
        }
        int start = at + "/share/".length();
        int end = start;
        while (end < path.length() && path.charAt(end) != '/' && path.charAt(end) != '?') {
            end++;
        }
        return end == start ? path : path.substring(0, start) + "***" + path.substring(end);
    }

    /** The signed-in user of the request in progress, or null - anonymous, or no request at all. */
    public static UserDetailsImpl currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof UserDetailsImpl principal
                ? principal
                : null;
    }

    /** The request in progress, or null outside one. */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }
}
