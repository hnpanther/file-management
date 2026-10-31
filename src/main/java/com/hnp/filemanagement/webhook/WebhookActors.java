package com.hnp.filemanagement.webhook;

/** Whose events a subscription is sent (roadmap 13.18). Stored by name. */
public enum WebhookActors {

    /** Everybody's. */
    ALL,

    /** A person's, by the page or the v1 password - no API key acted. */
    PEOPLE,

    /** An API key's (v1 Bearer, v2, S3). */
    KEYS
}
