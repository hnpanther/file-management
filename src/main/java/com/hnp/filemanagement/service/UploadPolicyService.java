package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.UploadRuleRowDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.UploadPolicy;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.exception.UploadRefusedException;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UploadPolicyRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.validation.ContentTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which kinds of file may be uploaded, and how large each may be - a system-wide policy the
 * administrator edits, and an optional policy per role that replaces it for that role.
 *
 * <p><b>Resolution for a person.</b> Each of their roles contributes a set of limits: the role's
 * own policy if it has one, the system-wide policy otherwise. The person may upload what any of
 * those sets allows, up to the largest limit any of them gives for that kind - the union, which
 * is how permissions combine across roles too. A person with no role at all has the system-wide
 * limits. An API key holds no role and has the system-wide limits. Nobody is exempt, the
 * administrator included: the policy can only narrow the catalogue of kinds the application can
 * recognise ({@link ContentTypes#knownExtensions()}), never widen it, so being governed by it costs
 * an administrator nothing they could otherwise have.
 *
 * <p><b>Where it is enforced.</b> {@link #requireAllowed} is called once, in
 * {@code FileService.newFileDetails}, which every route that stores a file - the form, v1, v2, a
 * new version - passes through. The bean-validation face of the upload ({@code @ValidFile}) still
 * checks the catalogue, because it has no principal to resolve a policy for.
 *
 * <p><b>The server's own cap.</b> {@code spring.servlet.multipart.max-file-size} is enforced by
 * the container before any of this runs, so a limit above it would never be reached; the page
 * shows the cap and a limit above it is refused on save.
 */
@Service
public class UploadPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(UploadPolicyService.class);

    private static final long MEGABYTE = 1024L * 1024L;

    private final UploadPolicyRepository uploadPolicyRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ActionHistoryService actionHistoryService;

    @Value("${spring.servlet.multipart.max-file-size:20MB}")
    private DataSize serverCap;

    public UploadPolicyService(UploadPolicyRepository uploadPolicyRepository, RoleRepository roleRepository,
                               UserRepository userRepository, ActionHistoryService actionHistoryService) {
        this.uploadPolicyRepository = uploadPolicyRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.actionHistoryService = actionHistoryService;
    }

    // ---------------------------------------------------------------- reading

    /** The server's multipart cap in whole megabytes - the ceiling every limit sits under. */
    public long serverCapMb() {
        return serverCap.toBytes() / MEGABYTE;
    }

    /** The system-wide limits, extension → bytes, in catalogue order. */
    @Transactional(readOnly = true)
    public Map<String, Long> globalLimits() {
        return inCatalogueOrder(globalLimitsRaw());
    }

    /** A role's own limits, or empty when the role is governed by the system-wide policy. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Long>> roleLimits(int roleId) {
        return uploadPolicyRepository.findByRoleId(roleId).map(policy -> inCatalogueOrder(policy.limits()));
    }

    /**
     * What this principal may upload: extension → bytes, in catalogue order. Resolved from the
     * roles of the person, or the system-wide policy for an API key.
     */
    @Transactional(readOnly = true)
    public Map<String, Long> effectiveLimitsFor(int principalId) {
        if (currentRequestIsAnApiKey()) {
            return globalLimits();
        }
        List<Integer> roleIds = uploadPolicyRepository.findRoleIdsOfUser(principalId);
        if (roleIds.isEmpty()) {
            return globalLimits();
        }
        Map<Integer, Map<String, Long>> own = new LinkedHashMap<>();
        for (UploadPolicy policy : uploadPolicyRepository.findByRoleIdIn(roleIds)) {
            own.put(policy.getRole().getId(), policy.limits());
        }
        Map<String, Long> globalLimits = own.size() < roleIds.size() ? globalLimitsRaw() : Map.of();

        Map<String, Long> union = new LinkedHashMap<>();
        for (Integer roleId : roleIds) {
            Map<String, Long> contribution = own.getOrDefault(roleId, globalLimits);
            contribution.forEach((extension, limit) -> union.merge(extension, limit, Math::max));
        }
        return inCatalogueOrder(union);
    }

    /**
     * The catalogue as a page renders it against a set of limits: every kind, whether these
     * limits list it, and its megabytes - the limit's, or the system-wide one's as a starting
     * value for a kind not listed.
     */
    @Transactional(readOnly = true)
    public List<UploadRuleRowDTO> rowsFor(Map<String, Long> limits) {
        Map<String, Long> fallback = globalLimitsRaw();
        List<UploadRuleRowDTO> rows = new ArrayList<>();
        for (String extension : ContentTypes.knownExtensions()) {
            boolean allowed = limits.containsKey(extension);
            long bytes = allowed ? limits.get(extension) : fallback.getOrDefault(extension, serverCap.toBytes());
            rows.add(new UploadRuleRowDTO(extension, ContentTypes.mediaTypeOf(extension).orElse(""), allowed,
                    Math.max(1, bytes / MEGABYTE)));
        }
        return rows;
    }

    // ---------------------------------------------------------------- enforcing

    /**
     * Refuses the upload if the principal's limits do not list its extension, or list it with a
     * smaller size than the file. The extension is read from the file name; whether the bytes are
     * what it says is {@link ContentTypes#detect}'s job, asked right after this.
     */
    public void requireAllowed(int principalId, MultipartFile file) {
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String extension = dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);

        Map<String, Long> limits = effectiveLimitsFor(principalId);
        List<String> allowed = List.copyOf(limits.keySet());
        Long limit = limits.get(extension);
        if (limit == null) {
            logger.info("upload refused: principal={} extension={} allowed={}", principalId, extension, allowed);
            throw UploadRefusedException.typeNotAllowed(extension, allowed);
        }
        if (file.getSize() > limit) {
            logger.info("upload refused: principal={} extension={} size={} limit={}", principalId, extension, file.getSize(), limit);
            throw UploadRefusedException.tooLarge(extension, file.getSize(), limit, allowed);
        }
    }

    // ---------------------------------------------------------------- editing

    /** Replaces the system-wide policy with these limits (extension → megabytes). */
    @Transactional
    public void saveGlobal(Map<String, Long> limitsMb, int principalId) {
        UploadPolicy policy = global();
        policy.replaceRules(validated(limitsMb));
        policy.setUpdatedBy(userRepository.getReferenceById(principalId));
        uploadPolicyRepository.save(policy);
        actionHistoryService.saveActionHistory(EntityEnum.UploadPolicy, policy.getId(), ActionEnum.UPDATE_VALUES,
                principalId, "UPDATE UPLOAD_POLICY", "UPDATE UPLOAD_POLICY, global, kinds=" + limitsMb.keySet());
    }

    /**
     * Gives the role a policy of its own with these limits (extension → megabytes), or - with
     * {@code null} - takes its own policy away so the system-wide one governs it again.
     */
    @Transactional
    public void saveForRole(int roleId, Map<String, Long> limitsMb, int principalId) {
        Role role = roleRepository.findById(roleId).orElseThrow(
                () -> new ResourceNotFoundException("role with id=" + roleId + " doesn't exists"));
        Optional<UploadPolicy> existing = uploadPolicyRepository.findByRoleId(roleId);

        if (limitsMb == null) {
            if (existing.isPresent()) {
                uploadPolicyRepository.delete(existing.get());
                actionHistoryService.saveActionHistory(EntityEnum.UploadPolicy, existing.get().getId(), ActionEnum.DELETE,
                        principalId, "DELETE UPLOAD_POLICY", "DELETE UPLOAD_POLICY, role=" + role.getRoleName());
            }
            return;
        }

        UploadPolicy policy = existing.orElseGet(() -> {
            UploadPolicy created = new UploadPolicy();
            created.setRole(role);
            created.setCreatedBy(userRepository.getReferenceById(principalId));
            return created;
        });
        policy.replaceRules(validated(limitsMb));
        policy.setUpdatedBy(userRepository.getReferenceById(principalId));
        policy = uploadPolicyRepository.save(policy);
        actionHistoryService.saveActionHistory(EntityEnum.UploadPolicy, policy.getId(),
                existing.isPresent() ? ActionEnum.UPDATE_VALUES : ActionEnum.CREATE,
                principalId, "SAVE UPLOAD_POLICY", "SAVE UPLOAD_POLICY, role=" + role.getRoleName() + ", kinds=" + limitsMb.keySet());
    }

    // ---------------------------------------------------------------- pieces

    /** The system-wide row for writing, created if an installation somehow lacks it. */
    private UploadPolicy global() {
        return uploadPolicyRepository.findGlobal().orElseGet(() -> {
            logger.warn("no system-wide upload policy row; creating one with the default kinds at the server cap");
            UploadPolicy created = new UploadPolicy();
            created.replaceRules(defaultLimits());
            return uploadPolicyRepository.save(created);
        });
    }

    /** The system-wide limits for reading; the defaults if the row is missing, without writing anything. */
    private Map<String, Long> globalLimitsRaw() {
        return uploadPolicyRepository.findGlobal().map(UploadPolicy::limits).orElseGet(this::defaultLimits);
    }

    /** The nine default kinds, each at the server cap - what V2.6 seeded. */
    private Map<String, Long> defaultLimits() {
        Map<String, Long> defaults = new LinkedHashMap<>();
        for (String extension : ContentTypes.knownExtensions()) {
            if (ContentTypes.defaultExtensions().contains(extension)) {
                defaults.put(extension, serverCap.toBytes());
            }
        }
        return defaults;
    }

    /** Megabytes in, bytes out; every kind catalogued, every limit within (0, server cap]. */
    private Map<String, Long> validated(Map<String, Long> limitsMb) {
        Map<String, Long> bytes = new LinkedHashMap<>();
        for (String extension : ContentTypes.knownExtensions()) {
            Long mb = limitsMb.get(extension);
            if (mb == null) {
                continue;
            }
            if (mb < 1) {
                throw new InvalidDataException("limit for ." + extension + " must be at least 1 MB");
            }
            if (mb * MEGABYTE > serverCap.toBytes()) {
                throw new InvalidDataException("limit for ." + extension + " is " + mb + " MB, above the server limit of "
                        + serverCapMb() + " MB (spring.servlet.multipart.max-file-size)");
            }
            bytes.put(extension, mb * MEGABYTE);
        }
        for (String extension : limitsMb.keySet()) {
            if (!ContentTypes.knownExtensions().contains(extension)) {
                throw new InvalidDataException("file type ." + extension + " cannot be allowed: the application does not recognise it");
            }
        }
        return bytes;
    }

    private static Map<String, Long> inCatalogueOrder(Map<String, Long> limits) {
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String extension : ContentTypes.knownExtensions()) {
            if (limits.containsKey(extension)) {
                ordered.put(extension, limits.get(extension));
            }
        }
        return ordered;
    }

    private static boolean currentRequestIsAnApiKey() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof UserDetailsImpl principal
                && principal.getApiKeyId() != null;
    }

    /** Whole megabytes for a byte count, for messages; never less than one. */
    public static long megabytesOf(long bytes) {
        return Math.max(1, Math.round(bytes / (double) MEGABYTE));
    }

    /** The posted form as extension → megabytes, keeping only the ticked kinds. */
    public static Map<String, Long> limitsFrom(Collection<String> allowed, Map<String, Long> maxMb) {
        Map<String, Long> limits = new LinkedHashMap<>();
        if (allowed == null) {
            return limits;
        }
        for (String extension : allowed) {
            Long mb = maxMb == null ? null : maxMb.get(extension);
            limits.put(extension, mb == null ? 0L : mb);
        }
        return limits;
    }
}
