package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.config.FileManagementProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the Active Directory connection is shaped for a real network: several controllers, bounded
 * waits, self-signed certificates pinned in a truststore, and a name check that can be switched
 * off only when that pin exists. No directory server is involved; what is tested is what the
 * provider hands JNDI, and what the TLS trust actually accepts and refuses.
 */
class ActiveDirectoryConnectionTest {

    private static final Path FIXTURES = Path.of("src/test/resources/ldap-tls");
    private static final char[] PASSWORD = "changeit".toCharArray();

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty("com.sun.jndi.ldap.object.disableEndpointIdentification");
    }

    // ---------------------------------------------------------------- the URL list

    @Test
    @DisplayName("servers may be separated by spaces or commas; JNDI gets them space-separated, in order")
    void serversAreNormalisedIntoAFailoverList() {
        assertThat(ActiveDirectoryCustomAuthenticationProvider.providerUrlOf(
                "ldaps://dc1.site.test:636, ldaps://dc2.site.test:636"))
                .isEqualTo("ldaps://dc1.site.test:636 ldaps://dc2.site.test:636");
        assertThat(ActiveDirectoryCustomAuthenticationProvider.providerUrlOf(
                "  ldaps://dc1.site.test:636   ldaps://dc2.site.test:636  "))
                .isEqualTo("ldaps://dc1.site.test:636 ldaps://dc2.site.test:636");
        assertThat(ActiveDirectoryCustomAuthenticationProvider.providerUrlOf("ldaps://only.site.test"))
                .isEqualTo("ldaps://only.site.test");
        assertThat(ActiveDirectoryCustomAuthenticationProvider.providerUrlOf(null)).isEmpty();
        assertThat(ActiveDirectoryCustomAuthenticationProvider.providerUrlOf(" , ")).isEmpty();
    }

    // ---------------------------------------------------------------- what JNDI is told

    @Test
    @DisplayName("every bind carries connect and read timeouts, so a dead controller costs seconds, not minutes")
    void timeoutsAreAlwaysSet() {
        ActiveDirectoryCustomAuthenticationProvider provider = provider(
                Map.of("connectTimeoutMs", 3000, "readTimeoutMs", 7000, "trustStore", ""));

        Map<String, Object> env = provider.contextEnvironment();

        assertThat(env).containsEntry("com.sun.jndi.ldap.connect.timeout", "3000");
        assertThat(env).containsEntry("com.sun.jndi.ldap.read.timeout", "7000");
    }

    @Test
    @DisplayName("with a truststore, JNDI is pointed at the socket factory that carries it")
    void aTruststorePutsTheSocketFactoryIntoTheEnvironment() {
        ActiveDirectoryCustomAuthenticationProvider provider = provider(Map.of(
                "trustStore", FIXTURES.resolve("truststore-dc1.p12").toString(),
                "trustStorePassword", "changeit",
                "trustStoreType", "PKCS12"));
        provider.prepare();

        assertThat(provider.contextEnvironment())
                .containsEntry("java.naming.ldap.factory.socket", LdapTrustStoreSocketFactory.class.getName());
    }

    // ---------------------------------------------------------------- the name check

    @Test
    @DisplayName("switching hostname verification off is refused without a truststore to pin the servers")
    void hostnameVerificationCannotBeSwitchedOffWithoutAPin() {
        ActiveDirectoryCustomAuthenticationProvider provider = provider(
                Map.of("verifyHostname", false, "trustStore", ""));
        // the static configuration may be left over from another test: make sure it is not
        ReflectionTestUtils.setField(LdapTrustStoreSocketFactory.class, "configured", null);

        assertThatThrownBy(provider::prepare)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a truststore");
        assertThat(System.getProperty("com.sun.jndi.ldap.object.disableEndpointIdentification")).isNull();
    }

    @Test
    @DisplayName("with a truststore, switching hostname verification off sets the one switch JNDI reads")
    void hostnameVerificationOffWithAPin() {
        ActiveDirectoryCustomAuthenticationProvider provider = provider(Map.of(
                "verifyHostname", false,
                "trustStore", FIXTURES.resolve("truststore-dc1.p12").toString(),
                "trustStorePassword", "changeit"));

        provider.prepare();

        assertThat(System.getProperty("com.sun.jndi.ldap.object.disableEndpointIdentification")).isEqualTo("true");
    }

    // ---------------------------------------------------------------- what the trust accepts

    /**
     * The pin, exercised with a real TLS handshake: a server presenting the certificate in the
     * truststore is accepted, one presenting a different self-signed certificate is not - even
     * though both are "valid" certificates and neither name is checked here.
     */
    @Test
    @DisplayName("the truststore accepts the controller whose certificate it holds and refuses any other")
    void theTrustIsAPin() throws Exception {
        LdapTrustStoreSocketFactory.configure(FIXTURES.resolve("truststore-dc1.p12"), "changeit", "PKCS12");

        assertThat(handshakeAgainst("dc1-key.p12")).as("dc1 is in the truststore").isNull();
        assertThat(handshakeAgainst("dc2-key.p12")).as("dc2 is not").isInstanceOf(SSLHandshakeException.class);
    }

    /**
     * The other mode, named for what it is: any certificate, any name. Both test controllers are
     * accepted, and the hostname switch is set as well, since a name check without a certificate
     * check would only refuse the honest servers.
     */
    @Test
    @DisplayName("verify-certificate=false accepts every controller and switches the name check off with it")
    void noVerificationAcceptsEverything() throws Exception {
        ActiveDirectoryCustomAuthenticationProvider provider = provider(
                Map.of("verifyCertificate", false, "trustStore", ""));

        provider.prepare();

        assertThat(provider.contextEnvironment())
                .containsEntry("java.naming.ldap.factory.socket", LdapTrustStoreSocketFactory.class.getName());
        assertThat(System.getProperty("com.sun.jndi.ldap.object.disableEndpointIdentification")).isEqualTo("true");
        assertThat(handshakeAgainst("dc1-key.p12")).isNull();
        assertThat(handshakeAgainst("dc2-key.p12")).as("nothing is refused").isNull();
    }

    /** Runs a TLS server with the given key, connects through the factory, returns the failure or null. */
    private static Throwable handshakeAgainst(String serverKeyStore) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(FIXTURES.resolve(serverKeyStore))) {
            keys.load(in, PASSWORD);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagers.getKeyManagers(), null, null);

        try (SSLServerSocket server = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<Void> serverSide = CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) server.accept()) {
                    accepted.startHandshake();
                } catch (IOException ignored) {
                    // the client's verdict is what the test reads
                }
            });
            try (SSLSocket client = (SSLSocket) LdapTrustStoreSocketFactory.getDefault()
                    .createSocket(InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                client.startHandshake();
                return null;
            } catch (SSLHandshakeException e) {
                return e;
            } finally {
                serverSide.join();
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A provider configured as an installation would configure it: the settings go in through
     * {@link FileManagementProperties}, the way they do in production, rather than being poked
     * into fields (roadmap 2.1 - the fields are final now).
     *
     * @param overrides what this test varies: the timeouts, {@code trustStore},
     *                  {@code trustStorePassword}, {@code trustStoreType}, {@code verifyHostname}
     *                  and {@code verifyCertificate}
     */
    private static ActiveDirectoryCustomAuthenticationProvider provider(Map<String, Object> overrides) {
        FileManagementProperties.ActiveDirectory settings = new FileManagementProperties.ActiveDirectory(
                true, "site.test", "ldaps://dc1.site.test:636",
                (Integer) overrides.getOrDefault("connectTimeoutMs", 5000),
                (Integer) overrides.getOrDefault("readTimeoutMs", 10000),
                (String) overrides.getOrDefault("trustStore", ""),
                (String) overrides.getOrDefault("trustStorePassword", ""),
                (String) overrides.getOrDefault("trustStoreType", "PKCS12"),
                (Boolean) overrides.getOrDefault("verifyHostname", true),
                (Boolean) overrides.getOrDefault("verifyCertificate", true));
        return new ActiveDirectoryCustomAuthenticationProvider(null, null,
                FileManagementProperties.defaults("D:/files/").withActiveDirectory(settings));
    }
}
