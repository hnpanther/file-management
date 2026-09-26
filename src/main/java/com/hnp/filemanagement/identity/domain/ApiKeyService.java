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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
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

    public ApiKeyService(ApiKeyRepository apiKeyRepository,
                         FolderRepository folderRepository,
                         UserRepository userRepository,
                         ActionHistoryService actionHistoryService) {
        this.apiKeyRepository = apiKeyRepository;
        this.folderRepository = folderRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
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
        if (expiresOn != null && !expiresOn.isAfter(LocalDate.now())) {
            // Refused rather than accepted and immediately useless: a key that expires today is
            // almost certainly a mistyped year, and it would fail with "expired" on first use.
            throw new InvalidDataException("expiry must be in the future, was " + expiresOn);
        }

        String keyId = randomKeyId();
        String secret = randomSecret();

        ApiKey apiKey = new ApiKey();
        apiKey.setKeyId(keyId);
        apiKey.setSecretHash(sha256(secret));
        apiKey.setTitle(request.getTitle());
        apiKey.setDescription(request.getDescription());
        apiKey.setEnabled(1);
        // End of day, so "expires on the 5th" means the 5th is still usable.
        apiKey.setExpiresAt(expiresOn == null ? null : expiresOn.plusDays(1).atStartOfDay());
        apiKey.setCreatedAt(LocalDateTime.now());
        apiKey.setCreatedBy(creator);
        apiKey.replaceFolderGrants(grantsFor(apiKey, request.getFolderGrants()));

        ApiKey saved = apiKeyRepository.save(apiKey);

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, saved.getId(), ActionEnum.CREATE,
                principalId, "CREATE API_KEY", "CREATE API_KEY, keyId=" + keyId);

        return new ApiKeyCreatedDTO(saved.getId(), keyId, PREFIX + keyId + "_" + secret);
    }

    // ------------------------------------------------------------------ managing

    /** Every key, newest first. Never carries a secret, because none is stored. */
    public List<ApiKeyDTO> getAll() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
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
        apiKey.setExpiresAt(request.getExpiresAt() == null
                ? null : request.getExpiresAt().plusDays(1).atStartOfDay());
        apiKey.setUpdatedAt(LocalDateTime.now());
        apiKey.setUpdatedBy(userRepository.findById(principalId).orElse(null));
        apiKey.replaceFolderGrants(grantsFor(apiKey, request.getFolderGrants()));

        actionHistoryService.saveActionHistory(EntityEnum.ApiKey, id, ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE API_KEY", "UPDATE API_KEY, keyId=" + apiKey.getKeyId());
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
        apiKey.setRevokedAt(LocalDateTime.now());
        apiKey.setEnabled(0);
        apiKey.setUpdatedAt(LocalDateTime.now());

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
        apiKey.setUpdatedAt(LocalDateTime.now());

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
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKey apiKey = found.get();

        if (!MessageDigest.isEqual(apiKey.getSecretHash().getBytes(StandardCharsets.UTF_8),
                sha256(parts[1]).getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        if (!apiKey.isUsableAt(now)) {
            return Optional.empty();
        }

        stampLastUsed(apiKey, now);
        return Optional.of(apiKey);
    }

    /**
     * Set on the entity and left to the flush, rather than issued as a bulk {@code UPDATE}.
     *
     * <p>Both are one statement. The difference is that a bulk update goes round the persistence
     * context, so the object this method just returned would still say the key had never been used —
     * the row and the object would disagree for the rest of the transaction, which is a trap for
     * whatever reads it next rather than a saving.
     */
    private void stampLastUsed(ApiKey apiKey, LocalDateTime now) {
        LocalDateTime last = apiKey.getLastUsedAt();
        if (last == null || last.isBefore(now.minusMinutes(LAST_USED_STAMP_MINUTES))) {
            apiKey.setLastUsedAt(now);
        }
    }

    // ------------------------------------------------------------------ shared pieces

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
        // Back to a date for the form; the column holds the exclusive end of that day.
        dto.setExpiresAt(apiKey.getExpiresAt() == null
                ? null : apiKey.getExpiresAt().toLocalDate().minusDays(1));
        dto.setEnabled(apiKey.getEnabled());
        dto.setRevokedAt(apiKey.getRevokedAt());
        dto.setLastUsedAt(apiKey.getLastUsedAt());
        dto.setCreatedAt(apiKey.getCreatedAt());
        dto.setCreatedBy(apiKey.getCreatedBy() == null ? null : apiKey.getCreatedBy().getUsername());
        dto.setUsable(apiKey.isUsableAt(LocalDateTime.now()));
        return dto;
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
