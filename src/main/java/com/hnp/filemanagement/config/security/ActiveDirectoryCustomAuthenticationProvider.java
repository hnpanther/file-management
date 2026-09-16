package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
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

    /** The provider URL as JNDI wants it: every configured server, space-separated. */
    private String providerUrl;

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


    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {


        logger.debug("enter into active directory auth provider");
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();
//
//        String domain = "hnp.local";
//        String url =  "ldap://172.29.76.9";

        ActiveDirectoryLdapAuthenticationProvider activeDirectoryLdapAuthenticationProvider =
                new ActiveDirectoryLdapAuthenticationProvider(domain, providerUrl != null ? providerUrl : providerUrlOf(url));

        // to parse AD failed credentails error message due to account - expiry,lock, credentialis - expiry,lock
        activeDirectoryLdapAuthenticationProvider.setConvertSubErrorCodesToExceptions(true);
        activeDirectoryLdapAuthenticationProvider.setContextEnvironmentProperties(contextEnvironment());


        Authentication authenticate = activeDirectoryLdapAuthenticationProvider.authenticate(new UsernamePasswordAuthenticationToken(username, password));

        if(authenticate.isAuthenticated()) {
            LdapUserDetails ldapUserDetails = (LdapUserDetails) authenticate.getPrincipal();
            logger.debug("extracted username from active directory=" + ldapUserDetails.getUsername());
//            UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByUsername(ldapUserDetails.getUsername());
            UserDetailsImpl userDetails = (UserDetailsImpl) userService.createUserDetailsFromUser(ldapUserDetails.getUsername());

//            logger.debug("login type => " + userDetails.getLoginType());
            if(userDetails.getLoginType() != 0 && userDetails.getLoginType() != 2) {
                return null;
            }


//            userDetails.getPermissions().forEach(System.out::println);
//            logger.debug("enabled ? => " + userDetails.getEnabled());


            if(userDetails.getEnabled() == 1) {
                return new UsernamePasswordAuthenticationToken
                        (userDetails, null, userDetails.getAuthorities());
            }
        }


        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }

}
