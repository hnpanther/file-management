package com.hnp.filemanagement.config.bootstrap;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
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
 */
@Component
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    private static final String ADMIN_ROLE = "ADMIN";
    private static final String USER_ROLE = "USER";
    private static final String ADMIN_USERNAME = "Admin";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${filemanagement.bootstrap.admin-password:}")
    private String configuredAdminPassword;

    public DataInitializer(UserRepository userRepository,
                           RoleRepository roleRepository,
                           PermissionRepository permissionRepository,
                           BCryptPasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void initialize() {
        seedPermissions();
        Role adminRole = seedRole(ADMIN_ROLE);
        seedRole(USER_ROLE);
        seedAdministrator(adminRole);
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
        return roleRepository.findByRoleName(roleName).orElseGet(() -> {
            Role role = new Role();
            role.setRoleName(roleName);
            logger.info("seeded role {}", roleName);
            return roleRepository.save(role);
        });
    }

    private void seedAdministrator(Role adminRole) {

        if (userRepository.existsByUsername(ADMIN_USERNAME)) {
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
