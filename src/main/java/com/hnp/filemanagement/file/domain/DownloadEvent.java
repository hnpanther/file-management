package com.hnp.filemanagement.file.domain;

import java.time.Instant;

/**
 * One download as it is recorded (2.7.0): the revision, and who took it - a person, an API key, a
 * share link, or nobody at all - from which address, through which channel. What a reader needs to
 * make sense of it later is copied in: the file's name, the version, the username.
 *
 * @param userId      the signed-in person, or the account an API key acts for; null for nobody
 * @param apiKeyId    the API key the request carried, or null
 * @param shareLinkId the share link it came through, or null
 * @param clientIp    the address the request came from, as the server saw it - behind a reverse
 *                    proxy that forwards it, the client's
 */
public record DownloadEvent(Instant occurredAt, DownloadChannel channel,
                            int fileInfoId, int fileDetailsId, String fileName, Integer version, Integer folderId,
                            Integer userId, String username, Integer apiKeyId, Integer shareLinkId, String clientIp) {

    /** Who, for telling a repeat from a new download: the key, the person, the link, or the address. */
    String actor() {
        if (apiKeyId != null) {
            return "k" + apiKeyId;
        }
        if (userId != null) {
            return "u" + userId;
        }
        if (shareLinkId != null) {
            return "s" + shareLinkId + "@" + clientIp;
        }
        return "ip" + clientIp;
    }
}
