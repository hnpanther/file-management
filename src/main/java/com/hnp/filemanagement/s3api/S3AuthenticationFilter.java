package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.identity.domain.ApiKey;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates a request to the S3-compatible surface with AWS Signature V4 (roadmap 9.10.2): the
 * {@code Authorization} header, or the same in a pre-signed URL's query. The key must be an S3 key
 * ({@code ApiKeyService.s3Credential}); its secret, decrypted, signs the request again here, and
 * only an exact match - compared in constant time - authenticates it, as the key's creator with the
 * key recorded beside them, like a V1 key (2.3.0).
 *
 * <p>A request that carries a signature and fails is answered here, at once, with S3's error -
 * {@code InvalidAccessKeyId}, {@code SignatureDoesNotMatch}, {@code RequestTimeTooSkewed},
 * {@code AccessDenied} for an expired pre-signed URL - and goes no further. One with no signature at
 * all goes on unauthenticated, and the chain refuses it ({@code AccessDenied}).
 *
 * <p><b>The path signed</b> is the one the client sent. A client given the endpoint
 * {@code http://host/s3} signs {@code /s3/bucket/key}; a reverse proxy that maps a host of its own
 * onto {@code /s3} passes on a path the client signed without it. Both are accepted: the path as
 * received, and the same without its leading {@code /s3}.
 */
public class S3AuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(S3AuthenticationFilter.class);

    /** The request attribute {@link S3RequestContext} is kept under. */
    public static final String CONTEXT = S3AuthenticationFilter.class.getName() + ".context";

    /** How far a request's own time may be from the server's - S3's rule. */
    static final Duration ALLOWED_SKEW = Duration.ofMinutes(15);

    /** The longest a pre-signed URL may be valid - S3's rule. */
    static final long MAX_PRESIGNED_SECONDS = 7 * 24 * 3600;

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final ApiKeyService apiKeyService;
    private final Clock clock;
    private final String mountPrefix;

    public S3AuthenticationFilter(ApiKeyService apiKeyService, Clock clock, String mountPrefix) {
        this.apiKeyService = apiKeyService;
        this.clock = clock;
        this.mountPrefix = mountPrefix;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean presigned = request.getParameter("X-Amz-Signature") != null;
        if (header == null && !presigned) {
            chain.doFilter(request, response);
            return;
        }
        Outcome outcome = presigned ? presigned(request) : signed(request, header);
        if (outcome.error() != null) {
            S3Errors.write(response, outcome.error(), request.getRequestURI());
            return;
        }
        request.setAttribute(CONTEXT, outcome.context());
        SecurityContextHolder.getContext().setAuthentication(authenticationFor(outcome.context().apiKey()));
        chain.doFilter(request, response);
    }

    private record Outcome(S3RequestContext context, S3Errors.Error error) {
        static Outcome refused(S3Errors.Error error) {
            return new Outcome(null, error);
        }
    }

    private Outcome signed(HttpServletRequest request, String header) {
        Optional<SigV4.Authorization> parsed = SigV4.parse(header);
        if (parsed.isEmpty()) {
            return Outcome.refused(S3Errors.Error.AUTHORIZATION_HEADER_MALFORMED);
        }
        SigV4.Authorization authorization = parsed.get();
        String amzDate = request.getHeader("x-amz-date");
        Instant signedAt = parseAmzDate(amzDate);
        if (signedAt == null || !amzDate.startsWith(authorization.date())) {
            return Outcome.refused(S3Errors.Error.AUTHORIZATION_HEADER_MALFORMED);
        }
        if (Duration.between(signedAt, Instant.now(clock)).abs().compareTo(ALLOWED_SKEW) > 0) {
            return Outcome.refused(S3Errors.Error.REQUEST_TIME_TOO_SKEWED);
        }
        String payloadHash = request.getHeader("x-amz-content-sha256");
        if (payloadHash == null || !authorization.signedHeaders().contains("host")
                || !authorization.signedHeaders().contains("x-amz-content-sha256")) {
            return Outcome.refused(S3Errors.Error.AUTHORIZATION_HEADER_MALFORMED);
        }
        return verify(request, authorization, amzDate, payloadHash, null);
    }

    private Outcome presigned(HttpServletRequest request) {
        if (!SigV4.ALGORITHM.equals(request.getParameter("X-Amz-Algorithm"))) {
            return Outcome.refused(S3Errors.Error.AUTHORIZATION_QUERY_MALFORMED);
        }
        Optional<SigV4.Authorization> parsed = SigV4.credential(request.getParameter("X-Amz-Credential"),
                request.getParameter("X-Amz-SignedHeaders"), request.getParameter("X-Amz-Signature"));
        String amzDate = request.getParameter("X-Amz-Date");
        Instant signedAt = parseAmzDate(amzDate);
        long expires;
        try {
            expires = Long.parseLong(request.getParameter("X-Amz-Expires"));
        } catch (NumberFormatException e) {
            expires = -1;
        }
        if (parsed.isEmpty() || signedAt == null || !amzDate.startsWith(parsed.get().date())
                || expires < 1 || expires > MAX_PRESIGNED_SECONDS) {
            return Outcome.refused(S3Errors.Error.AUTHORIZATION_QUERY_MALFORMED);
        }
        Instant now = Instant.now(clock);
        if (now.isAfter(signedAt.plusSeconds(expires)) || now.isBefore(signedAt.minus(ALLOWED_SKEW))) {
            return Outcome.refused(S3Errors.Error.REQUEST_EXPIRED);
        }
        return verify(request, parsed.get(), amzDate, SigV4.UNSIGNED_PAYLOAD, "X-Amz-Signature");
    }

    private Outcome verify(HttpServletRequest request, SigV4.Authorization authorization, String amzDate,
                           String payloadHash, String excludedQueryParameter) {
        Optional<ApiKeyService.S3Credential> credential = apiKeyService.s3Credential(authorization.accessKeyId());
        if (credential.isEmpty()) {
            logger.info("S3 request refused: unknown or unusable access key id {}", authorization.accessKeyId());
            return Outcome.refused(S3Errors.Error.INVALID_ACCESS_KEY_ID);
        }
        byte[] signingKey = SigV4.signingKey(credential.get().secret(), authorization.date(), authorization.region(),
                authorization.service());
        Map<String, List<String>> headers = headersOf(request);
        for (String path : candidatePaths(request.getRequestURI())) {
            String canonical = SigV4.canonicalRequest(request.getMethod(), path, request.getQueryString(), headers,
                    authorization.signedHeaders(), payloadHash, excludedQueryParameter);
            String expected = SigV4.signature(signingKey, SigV4.stringToSign(amzDate, authorization.scope(), canonical));
            if (SigV4.equal(expected, authorization.signature())) {
                return new Outcome(new S3RequestContext(credential.get().apiKey(), signingKey, amzDate,
                        authorization.scope(), authorization.signature(), payloadHash), null);
            }
        }
        logger.info("S3 request refused: signature does not match for access key id {}", authorization.accessKeyId());
        return Outcome.refused(S3Errors.Error.SIGNATURE_DOES_NOT_MATCH);
    }

    private List<String> candidatePaths(String received) {
        List<String> paths = new ArrayList<>(2);
        paths.add(received);
        if (received.startsWith(mountPrefix + "/")) {
            paths.add(received.substring(mountPrefix.length()));
        }
        return paths;
    }

    private static Map<String, List<String>> headersOf(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .addAll(Collections.list(request.getHeaders(name)));
        }
        return headers;
    }

    private static Instant parseAmzDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.from(AMZ_DATE.parse(value));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * As a V1 key authenticates (2.3.0): the key's creator, the key recorded beside them, and the one
     * authority the S3 surface asks - nothing of the v1 API.
     */
    private static UsernamePasswordAuthenticationToken authenticationFor(ApiKey apiKey) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(apiKey.getCreatedBy().getId());
        principal.setUsername(apiKey.getKeyId());
        principal.setPassword("");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setApiKeyId(apiKey.getId());
        principal.setPermissions(List.of(PermissionEnum.API_KEY));
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
