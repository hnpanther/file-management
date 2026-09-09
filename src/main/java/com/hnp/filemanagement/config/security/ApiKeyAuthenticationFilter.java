package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.entity.ApiKey;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns {@code Authorization: Bearer fmk_…} into an authenticated request (roadmap 9.2).
 *
 * <p><b>It never rejects anything.</b> A missing, malformed or refused credential simply leaves the
 * security context empty and the request carries on to the chain's own entry point, which answers
 * 401 with the {@code WWW-Authenticate} header already agreed there. A filter that wrote its own
 * error response would be a second place that decides what an unauthenticated API request looks
 * like, and the two would drift.
 *
 * <p><b>An already-authenticated request is left alone</b>, so a caller cannot present Basic
 * credentials and a key together and have the second quietly replace the first.
 *
 * <p>The principal is a {@link UserDetailsImpl} whose {@code id} is the person who created the key.
 * That is what keeps the audit trail whole — {@code action_history.created_by} is a foreign key to
 * {@code user}, and every service in this application takes a {@code principalId} — while
 * {@code apiKeyId} marks the request as a key's rather than a person's, so folder access resolves
 * from the key's own scopes and not from the creator's.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer ";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            presentedCredential(request)
                    .flatMap(apiKeyService::authenticate)
                    .map(ApiKeyAuthenticationFilter::authenticationFor)
                    .ifPresent(authentication ->
                            SecurityContextHolder.getContext().setAuthentication(authentication));
        }

        filterChain.doFilter(request, response);
    }

    private static Optional<String> presentedCredential(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String value = header.substring(SCHEME.length()).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /**
     * The authorities a key holds, which are its own and not its creator's.
     *
     * <p>{@code API_KEY} is what the v2 endpoints will require. {@code API_HEALTH_TEST} is granted
     * as well so that a newly created key can be proved to work — that endpoint returns a fixed
     * string and its own comment describes it as a probe "that also proves the caller's token and
     * permission still work", which is exactly what somebody setting up an integration needs on day
     * one. Nothing else from the v1 set is granted: those belong to the shared machine account, and
     * a key inheriting them would reach every file in the system.
     */
    private static Authentication authenticationFor(ApiKey apiKey) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(apiKey.getCreatedBy().getId());
        principal.setUsername(apiKey.getKeyId());
        principal.setPassword("");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setApiKeyId(apiKey.getId());
        principal.setPermissions(List.of(PermissionEnum.API_KEY, PermissionEnum.API_HEALTH_TEST));

        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }
}
