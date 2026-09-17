package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import org.springframework.stereotype.Component;

/**
 * Authenticates against Active Directory, then loads the matching local account for its roles.
 *
 * <p>Built for the network it actually runs on rather than for a lab (see {@code deployment.md},
 * "Active Directory behind a load balancer"):
 *
 * <ul>
 *   <li><b>Several domain controllers.</b> {@code url} may list more than one, separated by spaces
 *       or commas. They are handed to JNDI as one space-separated provider URL, which JNDI treats
 *       as a fail-over list: each is tried in order until a connection succeeds. A controller whose
 *       {@code 636} is closed is skipped in the time a refused connection takes.</li>
 *   <li><b>Timeouts.</b> Without them a controller that is down but not refusing - a black hole -
 *       holds every login for the operating system's TCP timeout before the next one is tried.
 *       {@code connect-timeout-ms} and {@code read-timeout-ms} bound that.</li>
 *   <li><b>Self-signed certificates.</b> A truststore holding the controllers' certificates is
 *       given to JNDI through {@link LdapTrustStoreSocketFactory}; the JVM's own {@code cacerts}
 *       and command line are left alone.</li>
 *   <li><b>A load-balancer name.</b> When the URL names something the certificates do not
 *       (the domain, a virtual name), {@code verify-hostname=false} switches off the name check -
 *       and only that check. With a truststore that holds nothing but the controllers' own
 *       certificates, identity is still proven by the handshake: no other server has those keys.
 *       Without a truststore the switch is refused, because encryption to an unverified,
 *       unpinned server is not worth the false comfort.</li>
 *   <li><b>No verification at all.</b> {@code verify-certificate=false} is the explicit,
 *       loudly-logged equivalent of {@code ssl.CERT_NONE}: it works against anything on 636 and
 *       proves nothing about what answered. An operator's decision, not a default.</li>
 * </ul>
 */
@Component
public class ActiveDirectoryCustomAuthenticationProvider implements AuthenticationProvider {

    Logger logger = LoggerFactory.getLogger(ActiveDirectoryCustomAuthenticationProvider.class);

    @Value("${filemanagement.auth.ldap.activedirectory.domain:}")
    private String domain;

    @Value("${filemanagement.auth.ldap.activedirectory.url:}")
    private String url;

    @Value("${filemanagement.auth.ldap.activedirectory.enabled:false}")
    private boolean enabled;

    @Value("${filemanagement.auth.ldap.activedirectory.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${filemanagement.auth.ldap.activedirectory.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Value("${filemanagement.auth.ldap.activedirectory.truststore:}")
    private String trustStore;

    @Value("${filemanagement.auth.ldap.activedirectory.truststore-password:}")
    private String trustStorePassword;

    @Value("${filemanagement.auth.ldap.activedirectory.truststore-type:PKCS12}")
    private String trustStoreType;

    @Value("${filemanagement.auth.ldap.activedirectory.verify-hostname:true}")
    private boolean verifyHostname;

    /**
     * {@code false} accepts any certificate and any name - the {@code ssl.CERT_NONE} of the Python
     * world. Encrypted, unauthenticated. Loud in the log, because it should be a decision.
     */
    @Value("${filemanagement.auth.ldap.activedirectory.verify-certificate:true}")
    private boolean verifyCertificate;

    /** {@code user.login_type}: either backend, the local password only, or the directory only. */
    private static final int LOGIN_TYPE_ANY = 0;
    private static final int LOGIN_TYPE_LOCAL_ONLY = 1;
    private static final int LOGIN_TYPE_AD_ONLY = 2;

    /** The provider URL as JNDI wants it: every configured server, space-separated. */
    private String providerUrl;

    /**
     * The Spring provider that does the bind, built once in {@link #prepare()} (issue 8: it used to
     * be built on every login attempt). {@code null} means Active Directory is not configured, in
     * which case this provider has no opinion and says so with {@code null}, the one place that
     * answer is still the right one.
     */
    private AuthenticationProvider delegate;

    private final UserDetailsService userDetailsService;

    private final UserService userService;

    public ActiveDirectoryCustomAuthenticationProvider(UserDetailsService userDetailsService, UserService userService) {
        this.userDetailsService = userDetailsService;
        this.userService = userService;
    }

    /**
     * Turns the configuration into what JNDI needs, once, and says what it did at start-up - the
     * one moment somebody is looking at the log - rather than on the first failed login.
     */
    @jakarta.annotation.PostConstruct
    void prepare() {
        if (!enabled) {
            return;
        }
        providerUrl = providerUrlOf(url);
        if (providerUrl.isEmpty()) {
            logger.warn("Active Directory is enabled but no URL is configured; every login will fall through to the local database");
            return;
        }
        for (String server : providerUrl.split(" ")) {
            if (server.startsWith("ldap://")) {
                logger.warn("Active Directory server {} is plain ldap://, so passwords cross the network in the clear; use ldaps://", server);
            }
        }
        if (!verifyCertificate) {
            LdapTrustStoreSocketFactory.configureTrustAll();
            System.setProperty("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");
            logger.warn("Active Directory TLS verifies NOTHING (verify-certificate=false): the connection is encrypted "
                    + "to whichever server answers, and a machine on the path can read every password. "
                    + "Export the controllers' certificates into a truststore to close this.");
        } else if (trustStore != null && !trustStore.isBlank()) {
            LdapTrustStoreSocketFactory.configure(java.nio.file.Path.of(trustStore), trustStorePassword, trustStoreType);
            logger.info("Active Directory TLS trust: {} ({})", trustStore, trustStoreType);
        }
        if (verifyCertificate && !verifyHostname) {
            if (!LdapTrustStoreSocketFactory.isConfigured()) {
                throw new IllegalStateException("filemanagement.auth.ldap.activedirectory.verify-hostname=false requires a truststore: "
                        + "without one the server's identity would be checked by nothing at all");
            }
            // JNDI has no per-context switch for this; the system property is the only one it reads.
            System.setProperty("com.sun.jndi.ldap.object.disableEndpointIdentification", "true");
            logger.warn("Active Directory hostname verification is off; the servers are identified by the truststore alone");
        }
        logger.info("Active Directory authentication: domain={}, servers={}, connect timeout {} ms, read timeout {} ms",
                domain, providerUrl, connectTimeoutMs, readTimeoutMs);
        delegate = newDelegate();
    }

    private ActiveDirectoryLdapAuthenticationProvider newDelegate() {
        ActiveDirectoryLdapAuthenticationProvider provider =
                new ActiveDirectoryLdapAuthenticationProvider(domain, providerUrl != null ? providerUrl : providerUrlOf(url));
        // to parse AD failed credentails error message due to account - expiry,lock, credentialis - expiry,lock
        provider.setConvertSubErrorCodesToExceptions(true);
        provider.setContextEnvironmentProperties(contextEnvironment());
        return provider;
    }

    /** For tests: stand something in for the directory. */
    void useDelegate(AuthenticationProvider delegate) {
        this.delegate = delegate;
    }

    /** Spaces or commas between servers in the configuration; one space between them for JNDI. */
    static String providerUrlOf(String configured) {
        if (configured == null) {
            return "";
        }
        return java.util.Arrays.stream(configured.trim().split("[\\s,]+"))
                .filter(server -> !server.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /** The JNDI environment every bind is made with. */
    java.util.Map<String, Object> contextEnvironment() {
        java.util.Map<String, Object> env = new java.util.HashMap<>();
        env.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(connectTimeoutMs));
        env.put("com.sun.jndi.ldap.read.timeout", String.valueOf(readTimeoutMs));
        if (LdapTrustStoreSocketFactory.isConfigured()) {
            env.put("java.naming.ldap.factory.socket", LdapTrustStoreSocketFactory.class.getName());
        }
        return env;
    }


    /**
     * Binds as the user, then decides from the local row whether that bind may sign them in.
     *
     * <p>Every refusal is an exception with its reason, never {@code null} (issue 8). What each one
     * makes {@code ProviderManager} do next is the point:
     *
     * <ul>
     *   <li>{@link DisabledException} for a disabled account stops the chain at once. Until this fix
     *       the answer was {@code null}, which means "no opinion", and the manager went on to try
     *       the same password against the local hash - so a disabled AD user was not refused, only
     *       re-tested against a backend that was never meant to know them.</li>
     *   <li>{@link BadCredentialsException} for a wrong password, an unknown account, or an account
     *       marked local-only lets the manager try the local provider next. For a local-only user
     *       that is the right backend; for the other two it fails there as well, with the same
     *       message, and the reason is in this log.</li>
     *   <li>{@link AuthenticationServiceException} when the directory cannot be reached. Spring's
     *       provider reports that as an {@code InternalAuthenticationServiceException}, which the
     *       manager rethrows without trying anyone else - so an outage of the directory would have
     *       locked out every local account, the administrator's included. Re-thrown as the plain
     *       service exception, the local provider still gets its turn, and the outage is in the
     *       log at ERROR.</li>
     * </ul>
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        logger.debug("enter into active directory auth provider");
        if (delegate == null) {
            // Not configured: no opinion, and the local provider decides.
            return null;
        }
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();

        Authentication bound;
        try {
            bound = delegate.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (InternalAuthenticationServiceException e) {
            logger.error("Active Directory is unreachable; local accounts can still sign in. username={}", username, e);
            throw new AuthenticationServiceException("Active Directory is unreachable", e);
        }
        if (bound == null || !bound.isAuthenticated()) {
            throw new BadCredentialsException("Active Directory did not authenticate " + username);
        }

        LdapUserDetails ldapUserDetails = (LdapUserDetails) bound.getPrincipal();
        logger.debug("extracted username from active directory=" + ldapUserDetails.getUsername());

        UserDetailsImpl userDetails;
        try {
            userDetails = userService.createUserDetailsFromUser(ldapUserDetails.getUsername());
        } catch (ResourceNotFoundException e) {
            logger.warn("Active Directory authenticated {} but this application has no such account", ldapUserDetails.getUsername());
            throw new BadCredentialsException("no such account: " + ldapUserDetails.getUsername());
        }

        if (userDetails.getLoginType() != LOGIN_TYPE_ANY && userDetails.getLoginType() != LOGIN_TYPE_AD_ONLY) {
            logger.info("Active Directory authenticated {} but the account is local-password only; the local provider decides", username);
            throw new BadCredentialsException("account is restricted to its local password: " + username);
        }
        if (!userDetails.isEnabled()) {
            logger.warn("Active Directory authenticated {} but the account is disabled", username);
            throw new DisabledException("account is disabled: " + username);
        }
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }

}
