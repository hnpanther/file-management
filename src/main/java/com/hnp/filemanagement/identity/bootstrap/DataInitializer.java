package com.hnp.filemanagement.identity.bootstrap;

import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.identity.domain.Permission;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.folder.domain.RoleFolderGrant;
import com.hnp.filemanagement.file.domain.UploadPolicy;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.PermissionRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.file.persistence.UploadPolicyRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Brings a fresh database up to a state the application can actually be used in: every permission
 * the code references, the two fixed roles, and one administrator to sign in as.
 *
 * <p>This lived in {@code FileManagementApplication} as a {@code @Transactional} method that the
 * {@code CommandLineRunner} lambda called on {@code this}. Spring's transaction support is a proxy
 * around the bean, and a call from inside the bean does not go through the proxy — so the
 * annotation did nothing and the bootstrap ran as a dozen independent transactions. Failing
 * halfway left the database half-seeded: some permissions inserted, no roles, no administrator, and
 * no error the next start could detect. As its own component the annotation applies, and the whole
 * bootstrap is one commit.
 *
 * <p>It is idempotent — everything is created only when missing — so it is safe on every start,
 * which is what makes adding a {@link PermissionEnum} constant a one-line change.
 *
 * <h2>The administrator's password</h2>
 *
 * <p>It used to be the literal {@code "admin"}, compiled in. Every deployment of this application
 * therefore shipped with the same known credentials for an account holding every permission. Now:
 *
 * <ul>
 *   <li>{@code filemanagement.bootstrap.admin-password} sets it, and that is the supported way;</li>
 *   <li>with nothing configured, a random password is generated and written to the log <b>once</b>,
 *       at WARN, on the run that creates the account. It is never regenerated and never logged
 *       again, so it has to be collected from that first start.</li>
 * </ul>
 *
 * <p>Either way the account exists only when it is missing, so setting the property later does not
 * reset a password that has already been changed.
 *
 * <h2>The fixed roles</h2>
 *
 * <p>ADMIN and USER are defined in {@link FixedRole}, and every start brings them to that
 * definition: ADMIN holds every assignable permission row - so <b>a permission added to
 * {@link PermissionEnum} is seeded above and given to ADMIN in the same start</b>, with nothing to
 * migrate - USER holds exactly {@link FixedRole#USER_PERMISSIONS}, and neither has folder grants
 * or an upload policy row of its own (ADMIN may upload every catalogued kind anyway,
 * {@code UploadPolicyService.administratorLimits}). This is done here, on every start, and not in
 * a Flyway migration, because a migration runs once and the permissions and the content kinds
 * keep growing after it.
 *
 * <p>Before 1.9.0 both roles could be edited on the role page, so an installation may find them
 * holding more. <b>Nothing is taken from anyone</b>: whatever USER holds beyond its definition - a
 * permission it is not given, a folder grant, an own upload policy - is first copied into a new
 * role, {@code USER_PREVIOUS}, which every holder of USER is given as well; only then is USER
 * reset. What each person can do is therefore the same after the start as before it, and the
 * administrator can see in one place what was set by hand and decide what to keep. A permission
 * either role lacks is simply added. Nothing of ADMIN's needs keeping - it already reaches every
 * endpoint, every folder and every kind of file - so its folder grants, its own upload policy and
 * the wildcard and key rows the page never offers are dropped without a copy.
 */
@Component
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ADMIN_USERNAME = "Admin";

    /** What USER's extras are copied into: {@code USER_PREVIOUS}. */
    static final String PREVIOUS_SUFFIX = "_PREVIOUS";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UploadPolicyRepository uploadPolicyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    private final String configuredAdminPassword;

    public DataInitializer(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PermissionRepository permissionRepository,
                           UploadPolicyRepository uploadPolicyRepository,
                           BCryptPasswordEncoder passwordEncoder,
                           FileManagementProperties properties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.uploadPolicyRepository = uploadPolicyRepository;
        this.passwordEncoder = passwordEncoder;
        this.configuredAdminPassword = properties.bootstrap().adminPassword();
    }

    @Transactional
    public void initialize() {
        seedPermissions();
        Role adminRole = seedRole(FixedRole.ADMIN.roleName());
        seedRole(FixedRole.USER.roleName());
        reconcile(FixedRole.ADMIN);
        reconcile(FixedRole.USER);
        seedAdministrator(adminRole);
    }

    /**
     * Brings a fixed role to its definition, after copying whatever it holds beyond it into a
     * {@code *_PREVIOUS} role its holders are given too (the class comment). Does nothing - and
     * writes nothing - when the role is already as defined, which is every start after the first.
     */
    void reconcile(FixedRole fixed) {
        Role role = roleRepository.findByRoleNameIgnoreCase(fixed.roleName()).orElseThrow();
        role = roleRepository.findByIdWithPermissions(role.getId()).orElseThrow();
        Role withGrants = roleRepository.findByIdWithFolders(role.getId()).orElseThrow();
        Optional<UploadPolicy> ownPolicy = uploadPolicyRepository.findByRoleId(role.getId());

        Set<PermissionEnum> held = role.getPermissions().stream()
                .map(Permission::getPermissionName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PermissionEnum.class)));
        Set<PermissionEnum> extra = EnumSet.noneOf(PermissionEnum.class);
        extra.addAll(held);
        extra.removeAll(fixed.permissions());
        boolean hasGrants = !withGrants.getFolderGrants().isEmpty();

        // Worth keeping for somebody: for USER anything beyond the definition; for ADMIN nothing,
        // since its holders already reach every endpoint, folder and kind of file.
        boolean preserve = fixed == FixedRole.USER && (!extra.isEmpty() || hasGrants || ownPolicy.isPresent());
        boolean differs = !held.equals(fixed.permissions()) || hasGrants || ownPolicy.isPresent();
        if (!differs) {
            return;
        }

        if (preserve) {
            preserveInCopy(fixed, role, withGrants, ownPolicy);
        }

        role.setPermissions(new LinkedHashSet<>(permissionRepository.findByPermissionNameIn(List.copyOf(fixed.permissions()))));
        withGrants.replaceFolderGrants(List.of());
        ownPolicy.ifPresent(uploadPolicyRepository::delete);
        logger.warn("fixed role {} brought to its definition: {} permission(s){}{}{}", fixed.roleName(),
                fixed.permissions().size(),
                extra.isEmpty() ? "" : ", removed " + extra,
                hasGrants ? ", folder grants removed" : "",
                ownPolicy.isPresent() ? ", own upload policy removed (the system-wide one applies)" : "");
    }

    /**
     * A new role holding exactly what the fixed role held - permissions, folder grants, own upload
     * policy - given to everybody who holds the fixed role.
     */
    private void preserveInCopy(FixedRole fixed, Role role, Role withGrants, Optional<UploadPolicy> ownPolicy) {
        String name = unusedName(fixed.roleName() + PREVIOUS_SUFFIX);
        Role copy = new Role();
        copy.setRoleName(name);
        copy.setPermissions(new LinkedHashSet<>(role.getPermissions()));
        Role saved = roleRepository.save(copy);
        saved.replaceFolderGrants(withGrants.getFolderGrants().stream()
                .map(grant -> new RoleFolderGrant(saved, grant.getFolder(), grant.getPermission()))
                .toList());
        ownPolicy.ifPresent(policy -> {
            UploadPolicy copied = new UploadPolicy();
            copied.setRole(saved);
            copied.replaceRules(policy.limits());
            uploadPolicyRepository.save(copied);
        });

        List<User> holders = userRepository.findHoldersOfRole(role.getId());
        holders.forEach(holder -> holder.getRoles().add(saved));
        logger.warn("fixed role {} held more than its definition; kept in new role {} ({} permission(s), {} folder "
                        + "grant(s), {}) and given to its {} holder(s) - review it on the role page",
                fixed.roleName(), name, saved.getPermissions().size(), saved.getFolderGrants().size(),
                ownPolicy.isPresent() ? "its own upload policy" : "no upload policy of its own", holders.size());
    }

    /** {@code base}, or {@code base_2}, {@code base_3} ... - the first no role has. */
    private String unusedName(String base) {
        String name = base;
        for (int n = 2; roleRepository.existsByRoleNameIgnoreCase(name); n++) {
            name = base + "_" + n;
        }
        return name;
    }

    /**
     * Inserts every {@link PermissionEnum} constant that has no row yet.
     *
     * <p>The missing set is computed with an {@link EnumSet} difference rather than by scanning the
     * loaded list once per constant, which the previous version did — seventy constants against a
     * growing list, for a job a set difference does in one pass. The seeding matters more than the
     * cost: a constant used in a {@code @PreAuthorize} expression but absent from this table denies
     * its endpoint to everyone except ADMIN, and nothing reports that.
     */
    private void seedPermissions() {
        Set<PermissionEnum> existing = permissionRepository.findAll().stream()
                .map(Permission::getPermissionName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PermissionEnum.class)));

        Set<PermissionEnum> missing = EnumSet.allOf(PermissionEnum.class);
        missing.removeAll(existing);

        if (missing.isEmpty()) {
            return;
        }

        List<Permission> permissions = missing.stream().map(name -> {
            Permission permission = new Permission();
            permission.setPermissionName(name);
            return permission;
        }).toList();

        permissionRepository.saveAll(permissions);
        logger.info("seeded {} new permission(s): {}", permissions.size(), missing);
    }

    private Role seedRole(String roleName) {
        return roleRepository.findByRoleNameIgnoreCase(roleName).orElseGet(() -> {
            Role role = new Role();
            role.setRoleName(roleName);
            logger.info("seeded role {}", roleName);
            return roleRepository.save(role);
        });
    }

    private void seedAdministrator(Role adminRole) {

        if (userRepository.existsByUsernameIgnoreCase(ADMIN_USERNAME)) {
            return;
        }

        String password = configuredAdminPassword;
        if (password == null || password.isBlank()) {
            password = generatePassword();
            logger.warn("""
                    No filemanagement.bootstrap.admin-password configured. \
                    A random password was generated for the "{}" account: {} \
                    This is the only time it is shown - sign in and change it.""", ADMIN_USERNAME, password);
        }

        User admin = new User();
        admin.setUsername(ADMIN_USERNAME);
        admin.setFirstName(ADMIN_USERNAME);
        admin.setLastName(ADMIN_USERNAME);
        admin.setNationalCode("9999999999");
        admin.setPhoneNumber("99999999997");
        admin.setPersonelCode(9999);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setEnabled(1);
        admin.setState(0);
        admin.setLoginType(0);
        admin.getRoles().add(adminRole);

        userRepository.save(admin);
        logger.info("seeded administrator account \"{}\"", ADMIN_USERNAME);
    }

    /** 24 bytes from {@link SecureRandom}, URL-safe encoded — not a memorable password on purpose. */
    private static String generatePassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
