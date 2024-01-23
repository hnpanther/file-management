package com.hnp.filemanagement.config.elasticsearch;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchConfiguration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.*;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

@Configuration
public class ElasticSearchConfig extends ElasticsearchConfiguration {


    @Value("${filemanagement.elasticsearch.username}")
    private String username;
    @Value("${filemanagement.elasticsearch.password}")
    private String password;
    @Value("${filemanagement.elasticsearch.url}")
    private String url;
    @Value("${filemanagement.elasticsearch.certificate-path}")
    private String certificatePath;
    @Value("${filemanagement.elasticsearch.ssl.enabled:false}")
    private boolean sslEnabled;


    @Override
    public ClientConfiguration clientConfiguration() {

        if(sslEnabled) {
            final ClientConfiguration clientConfiguration;
            try {
                clientConfiguration = ClientConfiguration.builder()
                        .connectedTo(url)
                        .usingSsl(getSSLContext())
                        .withBasicAuth(username, password)
                        .build();
            } catch (IOException | KeyManagementException | NoSuchAlgorithmException | KeyStoreException |
                     CertificateException e) {
                throw new RuntimeException(e);
            }
            return clientConfiguration;
        }

        final ClientConfiguration clientConfiguration;
        clientConfiguration = ClientConfiguration.builder()
                .connectedTo(url)
//                .usingSsl(getUnsafeSSLContext())
                .withBasicAuth(username, password)
                .build();
        return clientConfiguration;

    }

    private SSLContext getSSLContext() throws IOException, CertificateException, KeyStoreException, NoSuchAlgorithmException, KeyManagementException {

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (InputStream certificateInputStream = new FileInputStream(certificatePath)) {
            ca = cf.generateCertificate(certificateInputStream);
        }
        String keyStoreType = KeyStore.getDefaultType();
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", ca);

        String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory tmf =
                TrustManagerFactory.getInstance(tmfAlgorithm);
        tmf.init(keyStore);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private SSLContext getUnsafeSSLContext() {
        SSLContext sslContext = null;
        try {
            sslContext = SSLContext.getInstance("TLS");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        try {
            sslContext.init(null, new TrustManager[]{ UnsafeX509ExtendedTrustManager.INSTANCE }, null);
        } catch (KeyManagementException e) {
            throw new RuntimeException(e);
        }

        return sslContext;
    }
}
