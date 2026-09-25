package com.hnp.filemanagement.dto;

/**
 * The one and only time the secret exists outside the caller's hands.
 *
 * <p>A separate type from {@link ApiKeyDTO} on purpose. The secret is returned by exactly one
 * service method, carried to exactly one screen, and never stored — only its SHA-256 hash is. If it
 * lived on the type the list page renders, showing it again would be a field access rather than a
 * decision, and eventually somebody would make it.
 *
 * @param credential the full {@code fmk_{keyId}_{secret}} string to hand to the integration
 */
public record ApiKeyCreatedDTO(int id, String keyId, String credential) {

    /** Without the credential: a record prints every component, and this one is a secret. */
    @Override
    public String toString() {
        return "ApiKeyCreatedDTO[id=" + id + ", keyId=" + keyId + ", credential=***]";
    }
}
