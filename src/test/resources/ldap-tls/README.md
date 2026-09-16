Test fixtures for the Active Directory TLS trust (LdapTrustStoreSocketFactoryTest).

Two self-signed server certificates, `dc1.site.test` and `dc2.site.test`, each in its own
PKCS12 with its private key, and a truststore that holds only dc1's certificate. All passwords
are `changeit`. Generated once with keytool, valid for a hundred years; nothing here is a secret
and nothing here is used outside the test suite.
