package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.UserService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * The local-password half of authentication: {@code DaoAuthenticationProvider} asks this for the
 * principal, then checks the submitted password against the hash on it.
 *
 * <p>This class used to build {@link UserDetailsImpl} itself, duplicating
 * {@code UserService.createUserDetailsFromUser} line for line — and the copies had already drifted.
 * Only one of them granted the synthetic {@code ADMIN} authority, so an administrator who signed in
 * through Active Directory got it and the same administrator signing in with a local password did
 * not. There is now one builder, and this class only decides whether the local path is allowed to
 * use it.
 *
 * <p>The login-type gate is that decision, and it is deliberately not inside the shared builder:
 * each provider has its own rule, and the value means
 *
 * <ul>
 *   <li>{@code 0} — either mechanism may sign this user in;</li>
 *   <li>{@code 1} — local password only, which is this provider;</li>
 *   <li>{@code 2} — Active Directory only, which is not.</li>
 * </ul>
 *
 * <p>A user the gate rejects is reported as {@code UsernameNotFoundException} rather than as a
 * distinct failure, so an attacker cannot use the difference to learn which accounts exist and how
 * they authenticate.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final int LOGIN_TYPE_ANY = 0;
    private static final int LOGIN_TYPE_LOCAL_ONLY = 1;

    private final UserService userService;

    public UserDetailsServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        try {
            UserDetailsImpl userDetails = userService.createUserDetailsFromUser(username);

            if (userDetails.getLoginType() != LOGIN_TYPE_ANY
                    && userDetails.getLoginType() != LOGIN_TYPE_LOCAL_ONLY) {
                throw new UsernameNotFoundException("username not found, username=" + username);
            }

            return userDetails;
        } catch (ResourceNotFoundException e) {
            throw new UsernameNotFoundException("username not found, username=" + username);
        }
    }
}
