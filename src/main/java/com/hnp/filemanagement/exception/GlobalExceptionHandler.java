package com.hnp.filemanagement.exception;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.util.GlobalGeneralLogging;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

/**
 * The single exception handler for the whole application, HTML and REST alike.
 *
 * <p>There used to be a second advice scoped to {@code FileApi}. It duplicated this one and
 * disagreed with it - mapping {@code AccessDeniedException} to 400, for instance - so the same
 * failure answered differently depending on which endpoint you hit. One advice, one contract.
 *
 * <p>Two shapes come out of here, chosen by what the caller asked for:
 *
 * <ul>
 *   <li>a request that accepts {@code text/html} gets {@code error.html} with the right status;</li>
 *   <li>anything else gets an RFC 9457 {@link ProblemDetail} as {@code application/problem+json}.</li>
 * </ul>
 *
 * <p>The status comes from the {@link ResponseStatus} annotation on the exception itself, so the
 * exception stays the single source of truth: {@code ResourceNotFoundException} is 404,
 * {@code DuplicateResourceException} and {@code DependencyResourceException} are 409,
 * {@code InvalidDataException} is 400, {@code BusinessException} is 417. Endpoints used to catch
 * these and flatten them all to 400 with a hand-written sentence; they no longer catch them at all.
 *
 * <p>Messages from domain exceptions carry ids and occasionally paths, so they are logged but never
 * returned for an unexpected failure - a 500 gets a generic, translated message.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** RFC 9457 asks for a stable URI per problem kind; these are documentation anchors. */
    private static final String PROBLEM_BASE = "https://github.com/hnpanther/file-management/blob/main/docs/issues.md#";

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    // ------------------------------------------------------------------ domain failures

    @ExceptionHandler({ResourceNotFoundException.class, DuplicateResourceException.class,
            DependencyResourceException.class, BusinessException.class, InvalidDataException.class})
    public Object domainException(RuntimeException e, @AuthenticationPrincipal UserDetailsImpl principal,
                                  HttpServletRequest request) {

        HttpStatus status = statusOf(e);
        log(principal, request, e.getClass().getSimpleName() + ": " + e.getMessage(), status);

        // A domain message names what the caller got wrong, so it is safe and useful to return.
        return respond(request, status, e.getMessage(), e.getClass().getSimpleName());
    }

    /**
     * A {@code @PreAuthorize} denial is raised inside the controller invocation, so it never reaches
     * Spring Security's {@code ExceptionTranslationFilter} and its accessDeniedPage. It is handled
     * here, and answers 403 - the retired API advice answered 400, which told a client nothing.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public Object accessDenied(AccessDeniedException e, @AuthenticationPrincipal UserDetailsImpl principal,
                               HttpServletRequest request) {

        log(principal, request, "AccessDeniedException: " + e.getMessage(), HttpStatus.FORBIDDEN);
        return respond(request, HttpStatus.FORBIDDEN,
                detail(request, "error.accessDenied", "not authorised for this resource"), "AccessDenied");
    }

    /**
     * A body that Jackson cannot read is the caller's mistake, not a server fault. Without this it
     * fell through to {@link #uncaughtException} and answered 500 - which mattered once the
     * endpoints started binding request records instead of parsing the raw body themselves.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object unreadableBody(HttpMessageNotReadableException e,
                                 @AuthenticationPrincipal UserDetailsImpl principal,
                                 HttpServletRequest request) {

        log(principal, request, "HttpMessageNotReadableException: " + e.getMessage(), HttpStatus.BAD_REQUEST);

        // The exception message quotes the offending payload, so it is not echoed back.
        return respond(request, HttpStatus.BAD_REQUEST,
                detail(request, "error.invalidRequestBody", "request body is not readable"), "InvalidRequestBody");
    }

    /**
     * A path variable or query parameter that will not convert - {@code /file-info/abc} where an
     * {@code int} is declared. That is the caller's mistake, so it is 400.
     *
     * <p>Without this it fell through to {@link #uncaughtException} and answered <b>500</b>, telling
     * an integration that the server had broken when in fact it had been sent nonsense. Oracle APEX
     * hit exactly this by declaring its ids as {@code varchar2}.
     *
     * <p>Only the parameter <em>name</em> is echoed. The offending value came from the caller and
     * may be anything at all, so it is logged and not reflected.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Object typeMismatch(MethodArgumentTypeMismatchException e,
                               @AuthenticationPrincipal UserDetailsImpl principal,
                               HttpServletRequest request) {

        log(principal, request, "MethodArgumentTypeMismatchException: " + e.getMessage(),
                HttpStatus.BAD_REQUEST);

        return respond(request, HttpStatus.BAD_REQUEST,
                detail(request, "error.invalidParameter", "invalid value for parameter '" + e.getName() + "'"),
                "InvalidParameter");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object methodNotSupported(HttpRequestMethodNotSupportedException e,
                                     @AuthenticationPrincipal UserDetailsImpl principal,
                                     HttpServletRequest request) {

        log(principal, request, "HttpRequestMethodNotSupportedException: " + e.getMessage(),
                HttpStatus.METHOD_NOT_ALLOWED);
        return respond(request, HttpStatus.METHOD_NOT_ALLOWED, e.getMessage(), "MethodNotSupported");
    }

    /**
     * A missing resource must answer 404. Returning the HTML error page with a 200, as this once
     * did, makes a failed script tag arrive as a valid HTML document: the browser then reports
     * "Uncaught SyntaxError: Unexpected token '&lt;'" and the real cause is invisible.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object noResourceFound(NoResourceFoundException e, @AuthenticationPrincipal UserDetailsImpl principal,
                                  HttpServletRequest request) {

        // Expected often enough (a probe for /robots.txt) that ERROR would be noise.
        log(principal, request, "NoResourceFoundException: " + e.getMessage(), HttpStatus.NOT_FOUND);

        if (!wantsHtml(request)) {
            return ResponseEntity.notFound().build();
        }
        return htmlError(HttpStatus.NOT_FOUND, message("error.notFound"));
    }

    /**
     * Anything unforeseen is a 500 with a generic message. This used to return 200 with the HTML
     * error page, so a failing JSON endpoint looked successful to its caller.
     */
    @ExceptionHandler(Exception.class)
    public Object uncaughtException(Exception e, @AuthenticationPrincipal UserDetailsImpl principal,
                                    HttpServletRequest request) {

        logger.error("unhandled exception on {} {}", request.getMethod(),
                GlobalGeneralLogging.fullPath(request), e);

        // Never echo an unexpected exception message: it can carry SQL, paths or ids.
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR,
                detail(request, "error.unexpected", "unexpected server error"), "Unexpected");
    }

    // ------------------------------------------------------------------ shaping

    /** HTML for a browser navigation, RFC 9457 for everything else. */
    private Object respond(HttpServletRequest request, HttpStatus status, String detail, String kind) {
        if (wantsHtml(request)) {
            return htmlError(status, detail);
        }
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(PROBLEM_BASE + kind.toLowerCase()));
        problem.setTitle(kind);
        problem.setProperty("path", GlobalGeneralLogging.fullPath(request));
        return ResponseEntity.status(status).body(problem);
    }

    private ModelAndView htmlError(HttpStatus status, String message) {
        ModelAndView modelAndView = new ModelAndView();
        modelAndView.addObject("message", message);
        modelAndView.setViewName("error.html");
        modelAndView.setStatus(status);
        return modelAndView;
    }

    /**
     * The status declared on the exception type. Reading the annotation keeps the mapping in one
     * place - on the exception - instead of repeating it here and drifting.
     */
    private static HttpStatus statusOf(RuntimeException e) {
        ResponseStatus annotation = e.getClass().getAnnotation(ResponseStatus.class);
        return annotation == null ? HttpStatus.BAD_REQUEST : HttpStatus.valueOf(annotation.code().value());
    }

    private static boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("text/html");
    }

    private void log(UserDetailsImpl principal, HttpServletRequest request, String what, HttpStatus status) {
        String who = principal == null
                ? "anonymous"
                : principal.getId() + "/" + principal.getUsername();
        logger.warn("user=[{}] {} {} -> {} {}", who, request.getMethod(),
                GlobalGeneralLogging.fullPath(request), status.value(), what);
    }

    /**
     * The wording for a generic failure, chosen by who is asking.
     *
     * <p>{@code /api/**} is the machine-facing surface and its callers are programs, so it gets the
     * English text; everything else is the Persian UI and gets the translated one. Both halves used
     * to receive the Persian string, so an integration reading a 500 got a sentence in a script it
     * could not act on - and {@code FileApi}'s own documentation claimed otherwise.
     *
     * <p>This only applies to messages this class composes. A domain exception's own message is
     * already English and is passed through untouched.
     */
    private String detail(HttpServletRequest request, String messageCode, String machineText) {
        return isMachineApi(request) ? machineText : message(messageCode);
    }

    /** True for the versioned REST surface. The context path is stripped so a deployment under a
     * prefix still answers the same way. */
    private static boolean isMachineApi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/");
    }

    private String message(String code) {
        return messageSource.getMessage(code, null, LocaleContextHolder.getLocale());
    }
}
