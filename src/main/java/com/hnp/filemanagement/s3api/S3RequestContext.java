package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.identity.domain.ApiKey;

/**
 * What the signature check learnt, kept on the request for the handler: the key, and what a
 * streamed body's chunks are signed with - the signing key, the date and scope, and the seed (the
 * request's own signature, which the first chunk's chains to) - and how the payload was declared.
 */
public record S3RequestContext(ApiKey apiKey, byte[] signingKey, String amzDate, String scope,
                               String seedSignature, String payloadHash) {

    /** Without the signing key, which a record would otherwise print. */
    @Override
    public String toString() {
        return "S3RequestContext[keyId=" + apiKey.getKeyId() + ", payload=" + payloadHash + "]";
    }
}
