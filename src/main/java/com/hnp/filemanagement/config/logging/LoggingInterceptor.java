package com.hnp.filemanagement.config.logging;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The one writer of the "who did what, where" line (roadmap 2.1, {@code docs/issues.md} issue 25).
 *
 * <p>Every request gets exactly two lines: what arrived - method, path, caller, the signed-in user,
 * and <b>which handler</b> Spring chose - and what was sent back. The handler used to write the
 * first of those itself, from a six-line preamble copied into a hundred and twenty methods; it now
 * adds only detail the interceptor cannot know ({@link GlobalGeneralLogging#detail(String)}).
 *
 * <p>Two things this fixes while moving: the "with user" line was written and then written again
 * without the user, so every authenticated request logged twice; and the principal was read
 * without a null check, which is a {@code NullPointerException} on any request that reaches a
 * handler with no {@code Authentication} at all.
 *
 * <p>The path is masked ({@link GlobalGeneralLogging#maskSecrets}): a share link's token is the
 * access to a file, and it travels in the path.
 */
public class LoggingInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!logger.isDebugEnabled()) {
            return true;
        }
        UserDetailsImpl principal = GlobalGeneralLogging.currentPrincipal();
        logger.debug("--> {} {} from {} by {} to {}",
                request.getMethod(),
                GlobalGeneralLogging.fullPath(request),
                request.getRemoteAddr(),
                principal == null ? "anonymous" : principal.getId() + "/" + principal.getUsername(),
                describe(handler));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!logger.isDebugEnabled()) {
            return;
        }
        logger.debug("<-- {} {} {}{}",
                request.getMethod(),
                GlobalGeneralLogging.fullPath(request),
                response.getStatus(),
                ex == null ? "" : " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
    }

    /** {@code FolderResource#createFolder} for a handler method; the type for anything else. */
    private static String describe(Object handler) {
        if (handler instanceof HandlerMethod method) {
            return method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
        }
        return handler == null ? "-" : handler.getClass().getSimpleName();
    }
}
