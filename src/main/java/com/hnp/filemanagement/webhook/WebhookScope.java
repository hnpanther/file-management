package com.hnp.filemanagement.webhook;

/** Where a subscription listens (roadmap 13.18). Stored by name. */
public enum WebhookScope {

    /** The folder and everything beneath it, at any depth - as a grant covers. */
    SUBTREE,

    /** The folder alone: what happens directly in it, nothing beneath. */
    FOLDER,

    /** Every folder of a class, and beneath one - within a folder when one is set, as a scoped class grant. */
    CLASS
}
