package com.hnp.filemanagement.config.security;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * The TLS socket factory JNDI uses for {@code ldaps://} when a truststore is configured for
 * Active Directory, so that self-signed domain-controller certificates can be trusted without
 * touching the JVM's own {@code cacerts} or its command line.
 *
 * <p>JNDI has exactly one way to be given a socket factory: the class name in
 * {@code java.naming.ldap.factory.socket}, which it instantiates through a static
 * {@code getDefault()} with no arguments. That forces the configuration to be static - there is no
 * object to hand it to - which is why {@link #configure} exists and is called once, at start-up,
 * by {@link ActiveDirectoryCustomAuthenticationProvider}. Nothing else should call it.
 *
 * <p>With a truststore the trust decision is "is this certificate, or its issuer, in it" and
 * nothing looser. A truststore holding the domain controllers' own self-signed certificates is
 * therefore a <em>pin</em>: only those servers can complete the handshake, whatever name was used
 * to reach them. That is what makes switching hostname verification off tolerable behind a
 * load-balancer name - see the provider.
 *
 * <p>{@link #configureTrustAll()} is the other mode: accept whatever certificate is presented,
 * which is what {@code ldap3} does with {@code ssl.CERT_NONE} and what many intranet integrations
 * quietly run on. The connection is still encrypted, but to whoever answers - a machine on the
 * path that answers first reads every password. It exists because an operator may decide that
 * is acceptable on their network; the provider makes that decision loud in the log, and the
 * truststore is one export command away.
 */
public final class LdapTrustStoreSocketFactory extends SSLSocketFactory {

    private static volatile SSLSocketFactory configured;

    private final SSLSocketFactory delegate;

    private LdapTrustStoreSocketFactory(SSLSocketFactory delegate) {
        this.delegate = delegate;
    }

    /** What JNDI calls. Fails loudly if nobody configured a truststore, rather than trusting nothing silently. */
    public static SocketFactory getDefault() {
        SSLSocketFactory factory = configured;
        if (factory == null) {
            throw new IllegalStateException("LdapTrustStoreSocketFactory used before a truststore was configured");
        }
        return new LdapTrustStoreSocketFactory(factory);
    }

    /**
     * Builds the trust from a keystore file. Called once at start-up; a second call replaces the
     * first, which is only useful to tests.
     */
    public static void configure(Path trustStore, String password, String type) {
        try (InputStream in = Files.newInputStream(trustStore)) {
            KeyStore keyStore = KeyStore.getInstance(type == null || type.isBlank() ? "PKCS12" : type);
            keyStore.load(in, password == null ? new char[0] : password.toCharArray());
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), null);
            configured = context.getSocketFactory();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("cannot load the Active Directory truststore " + trustStore + ": " + e.getMessage(), e);
        }
    }

    /** Accept any certificate. See the class comment for what that costs. */
    public static void configureTrustAll() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new javax.net.ssl.TrustManager[]{new TrustEverything()}, null);
            configured = context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot build a TLS context: " + e.getMessage(), e);
        }
    }

    static boolean isConfigured() {
        return configured != null;
    }

    private static final class TrustEverything implements javax.net.ssl.X509TrustManager {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[0];
        }
    }

    // ---------------------------------------------------------------- delegation

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(socket, host, port, autoClose);
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate.createSocket();
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }
}
