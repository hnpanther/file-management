package com.hnp.filemanagement.file.domain;


import java.time.LocalDateTime;

/**
 * A share link as the pages and the JSON show it (roadmap 10.5).
 *
 * @param token             the secret, present only in the answer to a creation - the one time
 *                          it is ever shown; null everywhere else
 * @param path              {@code /share/{token}}, with the token; null with it
 * @param fileInfoId        the logical file
 * @param fileName          the revision's stored name, extension included
 * @param version           the revision's version number
 * @param status            {@code ACTIVE}, {@code EXPIRED}, {@code REVOKED} or {@code EXHAUSTED}
 * @param passwordProtected whether a password stands before the download
 * @param maxDownloads      the cap, or null
 * @param downloadCount     downloads so far
 * @param createdBy         the maker's username
 */
public record ShareLinkDTO(int id, String token, String path, int fileDetailsId, int fileInfoId,
                           String fileName, int version, Status status, boolean passwordProtected,
                           Integer maxDownloads, int downloadCount, LocalDateTime createdAt,
                           LocalDateTime expiresAt, LocalDateTime revokedAt, String createdBy) {

    public enum Status { ACTIVE, EXPIRED, REVOKED, EXHAUSTED }

    /** Without the token or the path that carries it: either is the whole access to the file. */
    @Override
    public String toString() {
        return "ShareLinkDTO[id=" + id + ", fileDetailsId=" + fileDetailsId + ", status=" + status + "]";
    }

    public static ShareLinkDTO of(FileShareLink link, String token, LocalDateTime now) {
        Status status = link.isRevoked() ? Status.REVOKED
                : link.isExhausted() ? Status.EXHAUSTED
                : link.isExpiredAt(now) ? Status.EXPIRED
                : Status.ACTIVE;
        return new ShareLinkDTO(
                link.getId(),
                token,
                token == null ? null : "/share/" + token,
                link.getFileDetails().getId(),
                link.getFileDetails().getFileInfo().getId(),
                link.getFileDetails().getFileName(),
                link.getFileDetails().getVersion() == null ? 0 : link.getFileDetails().getVersion(),
                status,
                link.hasPassword(),
                link.getMaxDownloads(),
                link.getDownloadCount(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.getRevokedAt(),
                link.getCreatedBy().getUsername());
    }
}
