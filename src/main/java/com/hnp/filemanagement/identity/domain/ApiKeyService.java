package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.folder.domain.ApiKeyFolderGrant;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.identity.persistence.ApiKeyRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Creating, listing and revoking API keys, and turning a presented credential back into one
 * (roadmap 9.2).
 *
 * <p>Everything about the secret lives here and nowhere else: it is generated here, hashed here, and
 * returned exactly once from {@link #create}. No other method can produce it, because after
 * {@link #create} returns nothing in the system holds it any more.
 */
@Service
@Transactional(readOnly = true)
public class ApiKeyService {

    /** Prefix on every credential, so one found in a log or a repository is recognisable at a glance. */
    static final String PREFIX = "fmk_";

    /**
     * 96 bits of public id: not a secret, but it must not collide.
     *
     * <p><b>Encoded as hex, not base64.</b> The credential is {@code fmk_{keyId}_{secret}} and is
     * taken apart on the first underscore after the prefix — and base64url's alphabet <em>contains</em>
     * an underscore. A base64 key id therefore breaks the parser whenever the random bytes happen to
     * encode one, which is most of the time and never all of the time: the kind of failure that
     * reaches production and looks intermittent. Hex has no underscore, so the split is unambiguous
     * however long the secret is or what is in it.
     */
    private static final int KEY_ID_BYTES = 12;

    /** 256 bits of secret. Enough that guessing is not a threat model, which is why SHA-256 suffices. */
    private static final int SECRET_BYTES = 32;

    /**
     * How stale {@code last_used_at} is allowed to be.
     *
     * <p>Stamping it on every request would put a write on the read path of a read-only API. Five
     * minutes is enough to answer the question it exists for — "is this key still in use?" — and is
     * the difference between one write per call and one per key per five minutes.
     */
    private static final long LAST_USED_STAMP_MINUTES = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final ApiKeyRepository apiKeyRepository;
    private final FolderRepository folderRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;
    private final S3SecretCipher s3SecretCipher;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         FolderRepository folderRepository,
                         UserRepository userRepository,
                         ActionHistoryService actionHistoryService,
                         S3SecretCipher s3SecretCipher,
                         Clock clock) {
        this.apiKeyRepository = apiKeyRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
        this.s3SecretCipher = s3SecretCipher;
        this.clock = clock;
    }

    /**
     * An S3 key ready to verify a signature with: the key and its secret, decrypted. Empty for an
     * unknown access key id, a V1 key, or one not usable now - the S3 filter answers each alike.
     */
    public record S3Credential(ApiKey apiKey, String secret) {
        @Override
        public String toString() {
            return "S3Credential[keyId=" + apiKey.getKeyId() + ", secret=***]";
        }
    }

    // ------------------------------------------------------------------ creating

    /**
     * Creates a key and returns the credential, which is the only time it exists.
     *
     * @return the full {@code fmk_…} string; the caller has one chance to show it
     */
    @Transactional
    public ApiKeyCreatedDTO create(ApiKeyDTO request, int principalId) {
        User creator = userRepository.findById(principalId).orElseThrow(
                () -> new ResourceNotFoundException("user with id=" + principalId + " doesn't exists"));

        LocalDate expiresOn = request.getExpiresAt();
        requireFuture(expiresOn);

        boolean s3 = request.getKind() == ApiKeyKind.S3;
        if (s3 && !s3SecretCipher.configured()) {
            throw new InvalidDataException("an S3 key needs FILEMANAGEMENT_S3_SECRET_ENCRYPTION_KEY", "apiKey.s3.notConfigured");
        }
        String keyId = s3 ? randomS3AccessKeyId() : randomKeyId();
        String secret = s3 ? randomS3Secret() : randomSecret();

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyId(keyId);
        apiKey.setSecretHash(sha256(secret));
        apiKey.setKind(s3 ? ApiKeyKind.S3 : ApiKeyKind.V1);
        if (s3) {
            apiKey.setSecretEncrypted(s3SecretCipher.encrypt(secret, keyId));
            apiKey.setMayCreateFolders(request.isMayCreateFolders());
            apiKey.setMayDeleteFiles(request.isMayDeleteFiles());
            apiKey.setMayDeleteFolders(request.isMayDeleteFolders());
        }
        apiKey.setTitle(request.getTitle());
        apiKey.setDescription(request.getDescription());
        apiKey.setEnabled(1);
        apiKey.setExpiresAt(endOf(expiresOn));
        apiKey.setCreatedAt(Instant.now(clock));
        apiKey.setCreatedBy(creator);
        apiKey.replaceFolderGrants(grantsFor(apiKey, request.getFolderGrants()));

        ApiKey saved = apiKeyRepository.save(apiKey);

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, saved.getId(), ActionEnum.CREATE,
                principalId, "CREATE API_KEY", "CREATE API_KEY, keyId=" + keyId + ", kind=" + apiKey.getKind() + capabilities(apiKey));

        // An S3 key is two values a client is given separately: the access key id and the secret.
        return new ApiKeyCreatedDTO(saved.getId(), keyId, s3 ? secret : PREFIX + keyId + "_" + secret);
    }

    // ------------------------------------------------------------------ renewing and replacing (roadmap 9.11)

    /**
     * Gives a key a new date - or none - so that one past its date is accepted again, with the same
     * secret: its client works again without a change. Expiry means time ran out, not that the key
     * leaked, so nothing about the credential needs to change. A revoked key is refused: it may have
     * leaked, and what it gets is a replacement ({@link #reissue}).
     *
     * @param expiresOn the new last day, in the future; null for no expiry
     */
    @Transactional
    public void renew(int id, LocalDate expiresOn, int principalId) {
        ApiKey apiKey = requireKey(id);
        if (apiKey.getRevokedAt() != null) {
            throw new InvalidDataException("a revoked key is not renewed but replaced, id=" + id, "apiKey.renew.revoked");
        }
        requireFuture(expiresOn);
        Instant before = apiKey.getExpiresAt();
        apiKey.setExpiresAt(endOf(expiresOn));
        apiKey.setUpdatedAt(Instant.now(clock));
        apiKey.setUpdatedBy(userRepository.findById(principalId).orElse(null));

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, id, ActionEnum.UPDATE_VALUES, principalId,
                "RENEW API_KEY", "RENEW API_KEY, keyId=" + apiKey.getKeyId() + ", expiresAt " + before
                        + " -> " + apiKey.getExpiresAt());
    }

    /**
     * Issues a new key in place of a revoked one: a new key id and a new secret - shown once, as at
     * creation - carrying the revoked key's title, description, folder grants, kind and capabilities,
     * and its date if that is still ahead. The revoked key stays revoked, and names its replacement.
     * A revoked key is never brought back, because whoever may hold its secret would come back with
     * it; a key not revoked has no replacement - revoke it first, so the two are never both valid;
     * and a key is replaced once.
     */
    @Transactional
    public ApiKeyCreatedDTO reissue(int id, int principalId) {
        ApiKey revoked = apiKeyRepository.findByIdWithFolders(id).orElseThrow(
                () -> new ResourceNotFoundException("api key with id=" + id + " doesn't exists"));
        if (revoked.getRevokedAt() == null) {
            throw new InvalidDataException("only a revoked key is replaced, id=" + id, "apiKey.reissue.notRevoked");
        }
        if (revoked.getReplacedById() != null) {
            throw new InvalidDataException("key id=" + id + " was already replaced by id=" + revoked.getReplacedById(),
                    "apiKey.reissue.already");
        }

        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle(revoked.getTitle());
        request.setDescription(revoked.getDescription());
        request.setKind(revoked.getKind());
        request.setMayCreateFolders(revoked.isMayCreateFolders());
        request.setMayDeleteFiles(revoked.isMayDeleteFiles());
        request.setMayDeleteFolders(revoked.isMayDeleteFolders());
        LocalDate lastDay = lastDayOf(revoked.getExpiresAt());
        request.setExpiresAt(lastDay != null && lastDay.isAfter(LocalDate.now(clock)) ? lastDay : null);
        request.setFolderGrants(revoked.getFolderGrants().stream()
                .map(grant -> grant.getFolder().getId() + ":" + grant.getPermission().name())
                .toList());

        ApiKeyCreatedDTO created = create(request, principalId);
        revoked.setReplacedById(created.id());
        revoked.setUpdatedAt(Instant.now(clock));

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, id, ActionEnum.UPDATE_VALUES, principalId,
                "REISSUE API_KEY", "REISSUE API_KEY, keyId=" + revoked.getKeyId() + " replaced by id=" + created.id()
                        + ", keyId=" + created.keyId());
        return created;
    }

    /**
     * Refused rather than accepted and immediately useless: a key that expires today is almost
     * certainly a mistyped year, and it would fail with "expired" on first use.
     */
    private void requireFuture(LocalDate expiresOn) {
        if (expiresOn != null && !expiresOn.isAfter(LocalDate.now(clock))) {
            throw new InvalidDataException("expiry must be in the future, was " + expiresOn, "apiKey.expiry.past");
        }
    }

    /** The last day a stored expiry allows - the column holds the exclusive end of it ({@link #endOf}). */
    private LocalDate lastDayOf(Instant expiresAt) {
        return expiresAt == null ? null : LocalDate.ofInstant(expiresAt, clock.getZone()).minusDays(1);
    }

    // ------------------------------------------------------------------ managing

    /** Every key, newest first. Never carries a secret, because none is stored. */
    public List<ApiKeyDTO> getAll() {
        List<ApiKeyDTO> keys = apiKeyRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
        // Which key each one replaced: the other side of replaced_by_id, read off the same list.
        Map<Integer, Integer> replaces = new HashMap<>();
        keys.stream().filter(k -> k.getReplacedById() != null).forEach(k -> replaces.put(k.getReplacedById(), k.getId()));
        keys.forEach(k -> k.setReplacesId(replaces.get(k.getId())));
        return keys;
    }

    public ApiKeyDTO getById(int id) {
        return toDto(requireKey(id));
    }

    /** The key with its folder grants, for the edit screen. */
    public ApiKeyDTO getByIdWithGrants(int id) {
        ApiKey apiKey = apiKeyRepository.findByIdWithFolders(id).orElseThrow(
                () -> new ResourceNotFoundException("api key with id=" + id + " doesn't exists"));
        ApiKeyDTO dto = toDto(apiKey);
        dto.setFolderGrants(apiKey.getFolderGrants().stream()
                .map(grant -> grant.getFolder().getId() + ":" + grant.getPermission().name())
                .toList());
        return dto;
    }

    /**
     * Replaces the title, description, expiry and folder scopes.
     *
     * <p>Not the secret. There is no "regenerate": a key whose secret changed is a different
     * credential wearing the same name, and every caller of the old one would fail without anything
     * in the interface saying why. Create a new key and revoke the old one.
     */
    @Transactional
    public void update(int id, ApiKeyDTO request, int principalId) {
        ApiKey apiKey = apiKeyRepository.findByIdWithFolders(id).orElseThrow(
                () -> new ResourceNotFoundException("api key with id=" + id + " doesn't exists"));

        apiKey.setTitle(request.getTitle());
        apiKey.setDescription(request.getDescription());
        apiKey.setExpiresAt(endOf(request.getExpiresAt()));
        apiKey.setUpdatedAt(Instant.now(clock));
        apiKey.setUpdatedBy(userRepository.findById(principalId).orElse(null));
        apiKey.replaceFolderGrants(grantsFor(apiKey, request.getFolderGrants()));
        // What an S3 key may do beyond its grants is changed like its grants; its kind never is.
        if (apiKey.getKind() == ApiKeyKind.S3) {
            apiKey.setMayCreateFolders(request.isMayCreateFolders());
            apiKey.setMayDeleteFiles(request.isMayDeleteFiles());
            apiKey.setMayDeleteFolders(request.isMayDeleteFolders());
        }

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, id, ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE API_KEY", "UPDATE API_KEY, keyId=" + apiKey.getKeyId() + capabilities(apiKey));
    }

    /**
     * Burns a key. There is no way back, which is the point — a credential that might have leaked
     * has to stop being valid, not be parked somewhere it can be switched on again by accident.
     */
    @Transactional
    public void revoke(int id, int principalId) {
        ApiKey apiKey = requireKey(id);
        if (apiKey.getRevokedAt() != null) {
            return;
        }
        Instant now = Instant.now(clock);
        apiKey.setRevokedAt(now);
        apiKey.setEnabled(0);
        apiKey.setUpdatedAt(now);

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, id, ActionEnum.UPDATE_VALUES,
                principalId, "REVOKE API_KEY", "REVOKE API_KEY, keyId=" + apiKey.getKeyId());
    }

    /** Switches a key off, or back on. Unlike revoking, this is reversible. */
    @Transactional
    public void changeEnabled(int id, boolean enabled, int principalId) {
        ApiKey apiKey = requireKey(id);
        if (apiKey.getRevokedAt() != null && enabled) {
            throw new InvalidDataException("a revoked key cannot be enabled again, id=" + id);
        }
        apiKey.setEnabled(enabled ? 1 : 0);
        apiKey.setUpdatedAt(Instant.now(clock));

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, id, ActionEnum.UPDATE_VALUES,
                principalId, "CHANGE API_KEY ENABLED",
                "CHANGE API_KEY ENABLED, keyId=" + apiKey.getKeyId() + ", enabled=" + enabled);
    }

    // ------------------------------------------------------------------ authenticating

    /**
     * Resolves a presented credential, or empty if it is not one this application would accept.
     *
     * <p><b>Every failure returns the same empty answer</b> — malformed, unknown, wrong secret,
     * disabled, revoked, expired. The caller answers 401 without saying which, because the
     * difference between "no such key" and "wrong secret" is exactly what tells someone holding half
     * a credential that the other half is worth attacking.
     *
     * <p>The hash comparison is constant-time. It is comparing a 256-bit random value, so a timing
     * attack is not the realistic threat here, but the constant-time method costs nothing and
     * removes the question.
     */
    @Transactional
    public Optional<ApiKey> authenticate(String presented) {
        if (presented == null || !presented.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = presented.substring(PREFIX.length()).split("_", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return Optional.empty();
        }

        Optional<ApiKey> found = apiKeyRepository.findByKeyId(parts[0]);
        // An S3 key is never a bearer: it is accepted on the S3 surface only (roadmap 9.11).
        if (found.isEmpty() || found.get().getKind() != ApiKeyKind.V1) {
            return Optional.empty();
        }
        ApiKey apiKey = found.get();

        if (!MessageDigest.isEqual(apiKey.getSecretHash().getBytes(StandardCharsets.UTF_8),
                sha256(parts[1]).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
        if (!apiKey.isUsableAt(now)) {
            return Optional.empty();
        }

        stampLastUsed(apiKey, now);
        return Optional.of(apiKey);
    }

    /**
     * The S3 key with this access key id, and its secret, if it may be used now. Every failure - no
     * such id, a V1 key, disabled, revoked, expired, a secret this installation cannot decrypt - is
     * the same empty answer, as for {@link #authenticate}.
     */
    @Transactional
    public Optional<S3Credential> s3Credential(String accessKeyId) {
        if (accessKeyId == null || accessKeyId.isBlank() || !s3SecretCipher.configured()) {
            return Optional.empty();
        }
        Optional<ApiKey> found = apiKeyRepository.findByKeyId(accessKeyId);
        if (found.isEmpty() || found.get().getKind() != ApiKeyKind.S3) {
            return Optional.empty();
        }
        ApiKey apiKey = found.get();
        Instant now = Instant.now(clock);
        if (!apiKey.isUsableAt(now)) {
            return Optional.empty();
        }
        String secret;
        try {
            secret = s3SecretCipher.decrypt(apiKey.getSecretEncrypted(), apiKey.getKeyId());
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
        stampLastUsed(apiKey, now);
        return Optional.of(new S3Credential(apiKey, secret));
    }

    /**
     * Set on the entity and left to the flush, rather than issued as a bulk {@code UPDATE}.
     *
     * <p>Both are one statement. The difference is that a bulk update goes round the persistence
     * context, so the object this method just returned would still say the key had never been used —
     * the row and the object would disagree for the rest of the transaction, which is a trap for
     * whatever reads it next rather than a saving.
     */
    private void stampLastUsed(ApiKey apiKey, Instant now) {
        Instant last = apiKey.getLastUsedAt();
        if (last == null || last.isBefore(now.minus(Duration.ofMinutes(LAST_USED_STAMP_MINUTES)))) {
            apiKey.setLastUsedAt(now);
        }
    }

    // ------------------------------------------------------------------ shared pieces

    /**
     * The instant a key that "expires on" this date stops working: the start of the next day, in
     * the installation's time zone ({@code filemanagement.time-zone}), so the date itself is still
     * usable to its last minute there - whatever zone the server runs in.
     */
    private Instant endOf(LocalDate expiresOn) {
        return expiresOn == null ? null : expiresOn.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    }

    private ApiKey requireKey(int id) {
        return apiKeyRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("api key with id=" + id + " doesn't exists"));
    }

    /**
     * Reads the posted {@code "{folderId}:{permission}"} entries — the same encoding the role page
     * uses, deliberately, so the two screens post the same thing and one parser is right for both.
     */
    private List<ApiKeyFolderGrant> grantsFor(ApiKey apiKey, List<String> folderGrants) {
        if (folderGrants == null) {
            return List.of();
        }
        Map<Integer, FolderPermission> requested = new LinkedHashMap<>();
        for (String entry : folderGrants) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(":", 2);
            if (parts.length != 2) {
                throw new InvalidDataException("malformed folder grant: " + entry);
            }
            try {
                requested.put(Integer.valueOf(parts[0].trim()),
                        FolderPermission.valueOf(parts[1].trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new InvalidDataException("malformed folder grant: " + entry);
            }
        }

        Map<Integer, Folder> folders = folderRepository.findAllById(requested.keySet()).stream()
                .collect(Collectors.toMap(Folder::getId, folder -> folder, (a, b) -> a, LinkedHashMap::new));
        if (folders.size() != requested.size()) {
            throw new InvalidDataException("folder list for an api key holds an id that does not exist");
        }

        return requested.entrySet().stream()
                .map(entry -> new ApiKeyFolderGrant(apiKey, folders.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    private ApiKeyDTO toDto(ApiKey apiKey) {
        ApiKeyDTO dto = new ApiKeyDTO();
        dto.setId(apiKey.getId());
        dto.setKeyId(apiKey.getKeyId());
        dto.setTitle(apiKey.getTitle());
        dto.setDescription(apiKey.getDescription());
        // Back to a date for the form; the column holds the exclusive end of that day (endOf).
        dto.setExpiresAt(lastDayOf(apiKey.getExpiresAt()));
        dto.setEnabled(apiKey.getEnabled());
        dto.setRevokedAt(apiKey.getRevokedAt());
        dto.setLastUsedAt(apiKey.getLastUsedAt());
        dto.setCreatedAt(apiKey.getCreatedAt());
        dto.setCreatedBy(apiKey.getCreatedBy() == null ? null : apiKey.getCreatedBy().getUsername());
        Instant now = Instant.now(clock);
        dto.setUsable(apiKey.isUsableAt(now));
        dto.setExpired(apiKey.getRevokedAt() == null && apiKey.getExpiresAt() != null && !apiKey.getExpiresAt().isAfter(now));
        dto.setReplacedById(apiKey.getReplacedById());
        dto.setKind(apiKey.getKind());
        dto.setMayCreateFolders(apiKey.isMayCreateFolders());
        dto.setMayDeleteFiles(apiKey.isMayDeleteFiles());
        dto.setMayDeleteFolders(apiKey.isMayDeleteFolders());
        return dto;
    }

    /** What an S3 key may do beyond its grants, for the action history; nothing for a V1 key. */
    private static String capabilities(ApiKey apiKey) {
        return apiKey.getKind() != ApiKeyKind.S3 ? "" : ", mayCreateFolders=" + apiKey.isMayCreateFolders()
                + ", mayDeleteFiles=" + apiKey.isMayDeleteFiles() + ", mayDeleteFolders=" + apiKey.isMayDeleteFolders();
    }

    /** Twenty upper-case letters and digits, as an AWS access key id looks: {@code FM} and 18 random. */
    private static String randomS3AccessKeyId() {
        return "FM" + randomAlphanumeric(18, "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567");
    }

    /** Forty characters, as an AWS secret looks, from a 62-letter alphabet: about 238 bits. */
    private static String randomS3Secret() {
        return randomAlphanumeric(40, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789");
    }

    private static String randomAlphanumeric(int length, String alphabet) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /** Hex, so that it can never contain the underscore the credential is split on. */
    private static String randomKeyId() {
        return HexFormat.of().formatHex(randomBytes(KEY_ID_BYTES));
    }

    /** base64url, which is compact and may contain anything — it is the last field, so it may. */
    private static String randomSecret() {
        return ENCODER.encodeToString(randomBytes(SECRET_BYTES));
    }

    private static byte[] randomBytes(int count) {
        byte[] buffer = new byte[count];
        RANDOM.nextBytes(buffer);
        return buffer;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256; this cannot happen and must not be swallowed if it does.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
