package com.hnp.filemanagement.service;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.RoleDTO;
import com.hnp.filemanagement.dto.UserDTO;
import com.hnp.filemanagement.entity.ActionEnum;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.util.ModelConverterUtil;
import com.hnp.filemanagement.util.SearchTerms;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Users, their roles, and the authorities the login builds from both.
 *
 * <p>{@code loginType} restricts which mechanism may sign a user in: {@code 0} either, {@code 1}
 * local password only, {@code 2} Active Directory only. Enforcing it is each authentication
 * provider's job, not this class's — see {@code UserDetailsServiceImpl} and
 * {@code ActiveDirectoryCustomAuthenticationProvider} — because the rule differs between them.
 *
 * <p>{@link #createUserDetailsFromUser} is the single place that turns a row into a Spring Security
 * principal. It used to have a twin inside {@code UserDetailsServiceImpl} that built the same
 * object from the same two calls, and the two had already drifted — only one of them granted the
 * synthetic {@code ADMIN} authority, so what a user could do depended on which authentication
 * provider had handled them.
 *
 * <p>The class is read-only transactional by default and each mutating method opts in. Two things
 * follow from that, and both used to be wrong here:
 *
 * <ul>
 *   <li>a state change and the audit row that records it commit together, so a failure can no
 *       longer leave a changed user with no history of the change;</li>
 *   <li>the lazy {@code roles} graph is resolved while its transaction is still open, so no entity
 *       escapes to a controller half-loaded.</li>
 * </ul>
 *
 * <p>Passwords are hashed with BCrypt on the way in and never read back; {@code UserDTO} carries a
 * mask, not the hash.
 */
@Service
@Transactional(readOnly = true)
public class UserService {

    /** Given to every newly created user, so a new account can do something before an admin acts. */
    private static final String DEFAULT_ROLE = "USER";

    private static final String ADMIN_ROLE = "ADMIN";

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final RoleService roleService;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;
    private final ActionHistoryService actionHistoryService;

    public UserService(UserRepository userRepository,
                       PermissionRepository permissionRepository,
                       RoleService roleService,
                       BCryptPasswordEncoder bCryptPasswordEncoder,
                       ActionHistoryService actionHistoryService) {
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.roleService = roleService;
        this.bCryptPasswordEncoder = bCryptPasswordEncoder;
        this.actionHistoryService = actionHistoryService;
    }

    // ------------------------------------------------------------------ commands

    @Transactional
    public void createUser(UserDTO userDTO, int principalId) {

        Role defaultRole = roleService.getRoleEntityByName(DEFAULT_ROLE);

        requireNoDuplicate(userDTO.getUsername(), userDTO.getPersonelCode(),
                userDTO.getNationalCode(), userDTO.getPhoneNumber(), userDTO);

        User user = new User();
        user.setUsername(userDTO.getUsername());
        user.setPersonelCode(userDTO.getPersonelCode());
        user.setNationalCode(userDTO.getNationalCode());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setPassword(bCryptPasswordEncoder.encode(userDTO.getPassword()));
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());
        user.setEnabled(1);
        user.setState(1);
        user.setLoginType(0);
        user.getRoles().add(defaultRole);

        userRepository.save(user);

        actionHistoryService.saveActionHistory(EntityEnum.User, user.getId(), ActionEnum.CREATE, principalId,
                "CREATE NEW USER", "CREATE NEW USER");
    }

    /**
     * Updates the mutable identity fields of a user.
     *
     * <p>Each unique field is checked only when it actually changed, and only against its own
     * column. The previous version compared the four fields, packed the changed ones into
     * {@code ""} / {@code 0} sentinels, and passed all four to one
     * {@code existsByUsernameOrPersonelCodeOrNationalCodeOrPhoneNumber} — which asked the database
     * "is there a user whose username is the empty string, or whose personnel code is zero, …". It
     * happened to work because no such row exists, and it dereferenced {@code getPhoneNumber()}
     * without a null check on a nullable column.
     */
    @Transactional
    public void updateUser(UserDTO userDTO, int principalId) {

        User user = getUser(userDTO.getId());

        String newUsername = changedOrNull(user.getUsername(), userDTO.getUsername());
        String newNationalCode = changedOrNull(user.getNationalCode(), userDTO.getNationalCode());
        String newPhoneNumber = changedOrNull(user.getPhoneNumber(), userDTO.getPhoneNumber());
        Integer newPersonelCode = Objects.equals(user.getPersonelCode(), userDTO.getPersonelCode())
                ? null : userDTO.getPersonelCode();

        requireNoDuplicate(newUsername, newPersonelCode, newNationalCode, newPhoneNumber, userDTO);

        if (newUsername != null) {
            user.setUsername(newUsername);
        }
        if (newNationalCode != null) {
            user.setNationalCode(newNationalCode);
        }
        if (newPersonelCode != null) {
            user.setPersonelCode(newPersonelCode);
        }
        if (newPhoneNumber != null) {
            user.setPhoneNumber(newPhoneNumber);
        }
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());

        actionHistoryService.saveActionHistory(EntityEnum.User, user.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE USER", "UPDATE USER");
    }

    /** Replaces the user's roles with exactly the ones named; an unknown id rejects the whole call. */
    @Transactional
    public void updateUserRoles(int userId, List<Integer> roleIds, int principalId) {

        if (roleIds == null) {
            throw new InvalidDataException("role list can not be null");
        }

        User user = getUserWithRoles(userId);

        List<Role> roles = roleService.getRoleByIds(roleIds);
        if (roles.size() != new LinkedHashSet<>(roleIds).size()) {
            throw new InvalidDataException("invalid role for add to user, roleList=" + roleIds);
        }

        user.setRoles(new LinkedHashSet<>(roles));

        actionHistoryService.saveActionHistory(EntityEnum.UserRole, user.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "UPDATE USER_ROLE", "UPDATE USER_ROLE");
    }

    @Transactional
    public void changePassword(UserDTO userDTO, int principalId) {

        User user = getUser(userDTO.getId());
        user.setPassword(bCryptPasswordEncoder.encode(userDTO.getPassword()));

        // The new password is never logged, here or in the history row.
        actionHistoryService.saveActionHistory(EntityEnum.User, user.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "CHANGE PASSWORD", "CHANGE PASSWORD");
    }

    @Transactional
    public void changeEnabled(int userId, int enabled, int principalId) {

        if (enabled != 0 && enabled != 1) {
            throw new InvalidDataException("enabled is invalid, enabled=" + enabled);
        }

        User user = getUser(userId);
        user.setEnabled(enabled);

        actionHistoryService.saveActionHistory(EntityEnum.User, user.getId(), ActionEnum.UPDATE_VALUES, principalId,
                "CHANGE ENABLED", "Change enabled to " + enabled);
    }

    @Transactional
    public void changeLoginType(int userId, int type, int principalId) {

        if (type != 0 && type != 1 && type != 2) {
            throw new InvalidDataException("login type is invalid, type=" + type);
        }

        User user = getUser(userId);
        int oldLoginType = user.getLoginType();
        user.setLoginType(type);

        actionHistoryService.saveActionHistory(EntityEnum.User, userId, ActionEnum.UPDATE_VALUES, principalId,
                "CHANGE LOGIN TYPE", "Change Login Type from " + oldLoginType + " to " + type);
    }

    // ------------------------------------------------------------------ queries

    /**
     * Builds the Spring Security principal for a username, in one query.
     *
     * <p>A user with no roles gets an empty authority list and can still sign in — they simply
     * reach nothing but the permit-all pages. The previous version raised
     * {@code ResourceNotFoundException("user don't have any role!")} from inside the permission
     * lookup, which this method then translated into {@code UsernameNotFoundException}: a roleless
     * account was refused with "username not found", and no log line said otherwise.
     *
     * @throws ResourceNotFoundException if no user has that username
     */
    public UserDetailsImpl createUserDetailsFromUser(String username) {

        User user = userRepository.findByUsernameWithRolesAndPermissions(username).orElseThrow(
                () -> new ResourceNotFoundException("user not found. username=" + username)
        );

        List<PermissionEnum> authorities = new java.util.ArrayList<>(
                user.getRoles().stream()
                        .flatMap(role -> role.getPermissions().stream())
                        .map(Permission::getPermissionName)
                        .distinct()
                        .toList());

        // ADMIN is not a row in the permission table - it is the wildcard every @PreAuthorize
        // accepts as an alternative, granted by holding the role of that name.
        boolean isAdmin = user.getRoles().stream()
                .anyMatch(role -> role.getRoleName().equalsIgnoreCase(ADMIN_ROLE));
        if (isAdmin) {
            authorities.add(PermissionEnum.ADMIN);
        }

        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(user.getId());
        userDetails.setUsername(user.getUsername());
        userDetails.setPassword(user.getPassword());
        userDetails.setEnabled(user.getEnabled());
        userDetails.setState(user.getState());
        userDetails.setLoginType(user.getLoginType());
        userDetails.setPermissions(authorities);

        return userDetails;
    }

    /**
     * The distinct permissions a user holds through their roles, or an empty list if they hold no
     * roles at all.
     *
     * <p>The JPQL for this used to sit in this class as an {@code EntityManager.createQuery} call.
     * It is now {@code PermissionRepository.findDistinctByRoleIds}, which keeps queries in the
     * repository layer and lets this method skip the round trip entirely when there is nothing to
     * ask about.
     */
    public List<Permission> getAllPermissionsOfUser(int userId) {

        Set<Integer> roleIds = getUserWithRoles(userId).getRoles().stream()
                .map(Role::getId)
                .collect(Collectors.toSet());

        if (roleIds.isEmpty()) {
            return List.of();
        }
        return permissionRepository.findDistinctByRoleIds(roleIds);
    }

    public UserDTO getUserDtoById(int id) {
        return ModelConverterUtil.convertUserToUserDTO(getUserWithRoles(id));
    }

    /** Every role in the system, with {@code selected} set on the ones this user holds. */
    public List<RoleDTO> getAllRoleDtoOfUserWithSelected(int userId) {

        Set<Integer> held = getUserWithRoles(userId).getRoles().stream()
                .map(Role::getId)
                .collect(Collectors.toSet());

        List<RoleDTO> roles = roleService.getAllRoles();
        roles.forEach(role -> role.setSelected(held.contains(role.getId())));

        return roles;
    }

    /**
     * One page of users for the list screen.
     *
     * <p>A single {@link Page} replaces the old pair of methods — one for the rows, one for the
     * count — which parsed the search term with two slightly different rules: the row query blanked
     * an all-whitespace term, the count query did not. Two queries deriving their filter
     * independently is a pager that can disagree with its own list.
     *
     * <p>A term that parses as a number searches the id and the personnel code; anything else
     * searches the username and the full name.
     */
    public Page<UserDTO> getUserPage(String search, int pageSize, int pageNumber) {

        String term = SearchTerms.blankToNull(search);
        Integer searchNumber = parseIntOrNull(term);
        if (searchNumber != null) {
            term = null;
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());
        return userRepository.search(searchNumber, term, pageable)
                .map(ModelConverterUtil::convertUserToUserDTO);
    }

    // ------------------------------------------------------------------ internals

    private User getUser(int id) {
        return userRepository.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("user not found. id=" + id)
        );
    }

    private User getUserWithRoles(int id) {
        return userRepository.findByIdWithRoles(id).orElseThrow(
                () -> new ResourceNotFoundException("user not found. id=" + id)
        );
    }

    /**
     * Rejects the change if any of the supplied values already belongs to another user. A null
     * argument means "this field did not change", and is not checked.
     */
    private void requireNoDuplicate(String username, Integer personelCode, String nationalCode,
                                    String phoneNumber, UserDTO context) {

        boolean duplicate =
                (username != null && userRepository.existsByUsername(username))
                        || (personelCode != null && userRepository.existsByPersonelCode(personelCode))
                        || (nationalCode != null && userRepository.existsByNationalCode(nationalCode))
                        || (phoneNumber != null && userRepository.existsByPhoneNumber(phoneNumber));

        if (duplicate) {
            throw new DuplicateResourceException("duplicate user info=" + context);
        }
    }

    /** The new value when it differs from the current one, else null. Null-safe on both sides. */
    private static String changedOrNull(String current, String candidate) {
        return Objects.equals(current, candidate) ? null : candidate;
    }

    private static Integer parseIntOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
