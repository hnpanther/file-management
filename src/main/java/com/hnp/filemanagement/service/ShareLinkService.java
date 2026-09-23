package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDownloadDTO;
import com.hnp.filemanagement.dto.ShareLinkDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileShareLink;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileShareLinkRepository;
import com.hnp.filemanagement.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Temporary share links ({@code V2.12}, roadmap 10.5): a link to one stored revision, valid for
 * a number of minutes, optionally behind a password, optionally for a number of downloads, that
 * anyone holding it may download <em>without signing in</em> and outside folder access.
 *
 * <p><b>The link is the access.</b> Making one needs {@code CREATE_SHARE_LINK} and {@code READ}
 * on the file's folder - the same test as downloading it oneself - and from then on the token
 * stands in for that person's right to read the file, for as long as the link lives. Which is
 * why it lives briefly: the validity is clamped to {@code filemanagement.share-links.max-minutes}
 * without complaint (the response says the real expiry), defaults to {@code default-minutes},
 * and the installation may make a password mandatory ({@code password=REQUIRED}).
 *
 * <p><b>Nothing about a bad token is distinguishable.</b> Unknown, expired, revoked and
 * exhausted all answer the same 404, so the space of tokens cannot be probed for live ones; 256
 * random bits make guessing pointless anyway. A password is checked in constant time by BCrypt;
 * {@code max-failed-attempts} wrong guesses lock the link for {@code lock-minutes}.
 *
 * <p><b>Audit.</b> Creation and revocation are the maker's; a download has no principal, so it
 * is recorded against the maker with the link's id - what they handed out was used.
 *
 * <p><b>A download locks the row</b> ({@code findByTokenHashForUpdate}), because it writes the
 * count and the lock counter: without it two downloads arriving together both read the count
 * before either writes, and a link good for one download serves two.
 *
 * <p>The clock is injected so that expiry and locks are testable without waiting.
 */
@Service
public class ShareLinkService {

    private static final Logger logger = LoggerFactory.getLogger(ShareLinkService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_PASSWORD_LENGTH = 72;

    /** Whether a link may, must, or may not carry a password. */
    public enum PasswordPolicy { OPTIONAL, REQUIRED }

    /** What a download attempt with a token comes to. */
    public enum Outcome { DOWNLOAD, PASSWORD_REQUIRED, WRONG_PASSWORD, LOCKED }

    /**
     * @param file        the bytes, for {@link Outcome#DOWNLOAD}; null otherwise
     * @param lockedUntil when a locked link opens again, for {@link Outcome#LOCKED}; null otherwise
     */
    public record Attempt(Outcome outcome, FileDownloadDTO file, LocalDateTime lockedUntil) {
        static Attempt of(Outcome outcome) {
            return new Attempt(outcome, null, null);
        }
    }

    private final FileShareLinkRepository shareLinkRepository;
    private final FileDetailsRepository fileDetailsRepository;
    private final UserRepository userRepository;
    private final FileService fileService;
    private final FolderAccessService folderAccessService;
    private final ActionHistoryService actionHistoryService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Value("${filemanagement.share-links.max-minutes:1440}")
    private long maxMinutes;
    @Value("${filemanagement.share-links.default-minutes:60}")
    private long defaultMinutes;
    @Value("${filemanagement.share-links.password:OPTIONAL}")
    private PasswordPolicy passwordPolicy;
    @Value("${filemanagement.share-links.max-failed-attempts:5}")
    private int maxFailedAttempts;
    @Value("${filemanagement.share-links.lock-minutes:15}")
    private long lockMinutes;

    public ShareLinkService(FileShareLinkRepository shareLinkRepository, FileDetailsRepository fileDetailsRepository,
                            UserRepository userRepository, FileService fileService,
                            FolderAccessService folderAccessService, ActionHistoryService actionHistoryService,
                            PasswordEncoder passwordEncoder, Clock clock) {
        this.shareLinkRepository = shareLinkRepository;
        this.fileDetailsRepository = fileDetailsRepository;
        this.userRepository = userRepository;
        this.fileService = fileService;
        this.folderAccessService = folderAccessService;
        this.actionHistoryService = actionHistoryService;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the rules, for the pages

    public long maxMinutes() {
        return maxMinutes;
    }

    public long defaultMinutes() {
        return defaultMinutes;
    }

    public PasswordPolicy passwordPolicy() {
        return passwordPolicy;
    }

    // ------------------------------------------------------------------ making and revoking

    /**
     * Makes a link to a revision the caller may read.
     *
     * @param minutes      how long the link lives; null for the default, above the cap clamped to it
     * @param password     a password, or null / blank for none - refused blank under {@code REQUIRED}
     * @param maxDownloads how many downloads it is good for; null for no cap
     * @return the link, with the token - the one time it is ever shown
     * @throws ResourceNotFoundException no such revision
     * @throws AccessDeniedException     no read access to the file's folder
     * @throws InvalidDataException      minutes or downloads below one, a password missing or too long
     */
    @Transactional
    public ShareLinkDTO create(int fileDetailsId, Integer minutes, String password, Integer maxDownloads, int principalId) {
        FileDetails revision = fileDetailsRepository.findByIdWithFileInfo(fileDetailsId)
                .orElseThrow(() -> new ResourceNotFoundException("fileDetails with id=" + fileDetailsId + " not exists"));
        folderAccessService.requireReadAccess(folderAccessService.accessFor(principalId), revision.getFileInfo());

        long validMinutes = minutes == null ? defaultMinutes : minutes;
        if (validMinutes < 1) {
            throw new InvalidDataException("a share link lives at least one minute: " + minutes);
        }
        validMinutes = Math.min(validMinutes, maxMinutes);
        if (maxDownloads != null && maxDownloads < 1) {
            throw new InvalidDataException("a share link allows at least one download, or has no cap: " + maxDownloads);
        }
        String secret = password == null || password.isBlank() ? null : password;
        if (secret == null && passwordPolicy == PasswordPolicy.REQUIRED) {
            throw new InvalidDataException("a share link needs a password on this installation");
        }
        if (secret != null && secret.length() > MAX_PASSWORD_LENGTH) {
            throw new InvalidDataException("a share link password is at most " + MAX_PASSWORD_LENGTH + " characters");
        }

        String token = ENCODER.encodeToString(randomBytes(TOKEN_BYTES));
        LocalDateTime now = LocalDateTime.now(clock);

        FileShareLink link = new FileShareLink();
        link.setTokenHash(sha256(token));
        link.setFileDetails(revision);
        link.setExpiresAt(now.plusMinutes(validMinutes));
        link.setPasswordHash(secret == null ? null : passwordEncoder.encode(secret));
        link.setMaxDownloads(maxDownloads);
        link.setCreatedBy(userRepository.getReferenceById(principalId));
        link = shareLinkRepository.save(link);

        actionHistoryService.saveActionHistory(EntityEnum.FileShareLink, link.getId(), ActionEnum.CREATE, principalId,
                "CREATE SHARE LINK", "CREATE share link id=" + link.getId() + " to fileDetails id=" + fileDetailsId
                        + " valid " + validMinutes + " minute(s), password " + (secret == null ? "no" : "yes")
                        + ", max downloads " + (maxDownloads == null ? "none" : maxDownloads));
        logger.info("share link id={} created for fileDetails id={} by user id={}, expires {}",
                link.getId(), fileDetailsId, principalId, link.getExpiresAt());
        return ShareLinkDTO.of(link, token, now);
    }

    /**
     * Revokes a link: the maker's own, or anyone's for a caller who may revoke any. Revoking a
     * link that is already dead is harmless and recorded all the same.
     *
     * @throws AccessDeniedException someone else's link, without the right to revoke any
     */
    @Transactional
    public void revoke(int linkId, int principalId, boolean any) {
        FileShareLink link = shareLinkRepository.findByIdWithDetails(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("share link with id=" + linkId + " not exists"));
        if (!any && link.getCreatedBy().getId() != principalId) {
            throw new AccessDeniedException("share link id=" + linkId + " belongs to someone else");
        }
        if (!link.isRevoked()) {
            link.setRevokedAt(LocalDateTime.now(clock));
            shareLinkRepository.save(link);
        }
        actionHistoryService.saveActionHistory(EntityEnum.FileShareLink, linkId, ActionEnum.UPDATE_CHANGE_STATE, principalId,
                "REVOKE SHARE LINK", "REVOKE share link id=" + linkId);
    }

    // ------------------------------------------------------------------ using one

    /**
     * The link a token names, if it answers right now - or empty for unknown, expired, revoked
     * and exhausted alike, which a caller turns into one and the same 404.
     */
    @Transactional(readOnly = true)
    public Optional<FileShareLink> usable(String token) {
        LocalDateTime now = LocalDateTime.now(clock);
        return hashOf(token)
                .flatMap(shareLinkRepository::findByTokenHash)
                .filter(link -> link.isUsableAt(now));
    }

    /** The hash to look a token up by, or empty for what cannot be a token at all. */
    private static Optional<String> hashOf(String token) {
        if (token == null || token.isBlank() || token.length() > 128) {
            return Optional.empty();
        }
        return Optional.of(sha256(token));
    }

    /**
     * One download attempt. Without a password on the link the bytes come back at once; with one,
     * only for the right password, and wrong ones count towards the lock.
     *
     * @param password what the visitor typed, or null when they have not been asked yet
     * @throws ResourceNotFoundException the token names nothing usable
     */
    @Transactional
    public Attempt download(String token, String password) {
        LocalDateTime now = LocalDateTime.now(clock);
        // Locked, not merely read: the count and the lock counter are written below, and two
        // downloads arriving together must not both pass a cap of one.
        FileShareLink link = hashOf(token)
                .flatMap(shareLinkRepository::findByTokenHashForUpdate)
                .filter(candidate -> candidate.isUsableAt(now))
                .orElseThrow(() -> new ResourceNotFoundException("no such share link"));

        if (link.hasPassword()) {
            if (link.isLockedAt(now)) {
                return new Attempt(Outcome.LOCKED, null, link.getLockedUntil());
            }
            if (password == null) {
                return Attempt.of(Outcome.PASSWORD_REQUIRED);
            }
            if (!passwordEncoder.matches(password, link.getPasswordHash())) {
                link.setFailedAttempts(link.getFailedAttempts() + 1);
                if (link.getFailedAttempts() >= maxFailedAttempts) {
                    link.setLockedUntil(now.plusMinutes(lockMinutes));
                    link.setFailedAttempts(0);
                    shareLinkRepository.save(link);
                    logger.info("share link id={} locked until {} after {} wrong passwords", link.getId(), link.getLockedUntil(), maxFailedAttempts);
                    return new Attempt(Outcome.LOCKED, null, link.getLockedUntil());
                }
                shareLinkRepository.save(link);
                return Attempt.of(Outcome.WRONG_PASSWORD);
            }
            link.setFailedAttempts(0);
        }

        link.setDownloadCount(link.getDownloadCount() + 1);
        shareLinkRepository.save(link);
        actionHistoryService.saveActionHistory(EntityEnum.FileShareLink, link.getId(), ActionEnum.READ,
                link.getCreatedBy().getId(), "DOWNLOAD VIA SHARE LINK",
                "download " + link.getDownloadCount() + (link.getMaxDownloads() == null ? "" : " of " + link.getMaxDownloads())
                        + " of fileDetails id=" + link.getFileDetails().getId() + " via share link id=" + link.getId());
        return new Attempt(Outcome.DOWNLOAD, fileService.downloadViaShareLink(link.getFileDetails().getId()), null);
    }

    // ------------------------------------------------------------------ the page

    /** The caller's own links, newest first. */
    @Transactional(readOnly = true)
    public List<ShareLinkDTO> listMine(int principalId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return shareLinkRepository.findByCreator(principalId).stream().map(link -> ShareLinkDTO.of(link, null, now)).toList();
    }

    /** Every link, newest first - for whoever may revoke any. */
    @Transactional(readOnly = true)
    public List<ShareLinkDTO> listAll() {
        LocalDateTime now = LocalDateTime.now(clock);
        return shareLinkRepository.findAllWithDetails().stream().map(link -> ShareLinkDTO.of(link, null, now)).toList();
    }

    // ------------------------------------------------------------------ pieces

    private static byte[] randomBytes(int count) {
        byte[] buffer = new byte[count];
        RANDOM.nextBytes(buffer);
        return buffer;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)))
                    .toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
