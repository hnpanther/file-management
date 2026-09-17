package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.ldap.userdetails.LdapUserDetailsImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the Active Directory provider answers once the directory has spoken (issue 8). The
 * directory itself is stood in for; what is under test is the decision made from the local row,
 * and that every refusal is an exception whose class tells {@code ProviderManager} whether to
 * stop or to try the local provider next.
 */
class ActiveDirectoryProviderOutcomeTest {

    private static final int ANY = 0;
    private static final int LOCAL_ONLY = 1;
    private static final int AD_ONLY = 2;

    UserService userService;
    ActiveDirectoryCustomAuthenticationProvider provider;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        provider = new ActiveDirectoryCustomAuthenticationProvider(null, userService);
        ReflectionTestUtils.setField(provider, "enabled", true);
        provider.useDelegate(directoryThatAccepts());
    }

    // ---------------------------------------------------------------- accepted

    @Test
    @DisplayName("an enabled account that may use the directory is signed in with its local authorities")
    void enabledAccountIsSignedIn() {
        localRow("h.x", ANY, 1);

        Authentication result = provider.authenticate(login("h.x"));

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isInstanceOf(UserDetailsImpl.class);
        assertThat(result.getAuthorities()).extracting(Object::toString).contains("FILE_INFO_PAGE");
    }

    @Test
    @DisplayName("so is a directory-only account")
    void directoryOnlyAccountIsSignedIn() {
        localRow("h.x", AD_ONLY, 1);

        assertThat(provider.authenticate(login("h.x")).isAuthenticated()).isTrue();
    }

    // ---------------------------------------------------------------- refused, and how

    @Test
    @DisplayName("a disabled account is refused with DisabledException, which stops the chain - it is not re-tested locally")
    void disabledAccountStopsTheChain() {
        localRow("h.x", ANY, 0);

        assertThatThrownBy(() -> provider.authenticate(login("h.x")))
                .isInstanceOf(DisabledException.class)
                .isInstanceOf(AccountStatusException.class);
    }

    @Test
    @DisplayName("a local-password-only account is refused with BadCredentials, so the local provider gets its turn")
    void localOnlyAccountIsLeftToTheLocalProvider() {
        localRow("h.x", LOCAL_ONLY, 1);

        assertThatThrownBy(() -> provider.authenticate(login("h.x")))
                .isInstanceOf(BadCredentialsException.class)
                .isNotInstanceOf(AccountStatusException.class);
    }

    @Test
    @DisplayName("an account the directory knows but this application does not is refused, not a 500")
    void unknownAccountIsRefused() {
        when(userService.createUserDetailsFromUser(anyString())).thenThrow(new ResourceNotFoundException("user not found"));

        assertThatThrownBy(() -> provider.authenticate(login("stranger")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("a wrong password is the directory's BadCredentials, passed through")
    void wrongPasswordPassesThrough() {
        provider.useDelegate(directoryThatThrows(new BadCredentialsException("bad")));

        assertThatThrownBy(() -> provider.authenticate(login("h.x")))
                .isInstanceOf(BadCredentialsException.class);
    }

    /**
     * Spring's provider reports an unreachable directory as {@code InternalAuthenticationServiceException},
     * which {@code ProviderManager} rethrows without asking anyone else - every local account, the
     * administrator's included, would be locked out for as long as the directory is down.
     */
    @Test
    @DisplayName("an unreachable directory becomes a plain service exception, so local accounts still sign in")
    void outageDoesNotLockOutLocalAccounts() {
        provider.useDelegate(directoryThatThrows(new InternalAuthenticationServiceException("connect timed out")));

        assertThatThrownBy(() -> provider.authenticate(login("admin")))
                .isInstanceOf(AuthenticationServiceException.class)
                .isNotInstanceOf(InternalAuthenticationServiceException.class)
                .isNotInstanceOf(AccountStatusException.class);
    }

    @Test
    @DisplayName("with no directory configured the provider has no opinion, which is the one place null is the answer")
    void notConfiguredMeansNoOpinion() {
        provider.useDelegate(null);

        assertThat(provider.authenticate(login("h.x"))).isNull();
    }

    // ---------------------------------------------------------------- helpers

    private static Authentication login(String username) {
        return new UsernamePasswordAuthenticationToken(username, "secret");
    }

    /** A directory that accepts whoever asks, answering with an LDAP principal of that name. */
    private static AuthenticationProvider directoryThatAccepts() {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication auth) {
                LdapUserDetailsImpl.Essence essence = new LdapUserDetailsImpl.Essence();
                essence.setUsername(auth.getName());
                essence.setDn("cn=" + auth.getName() + ",dc=site,dc=test");
                return new UsernamePasswordAuthenticationToken(essence.createUserDetails(), null, List.of());
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return true;
            }
        };
    }

    private static AuthenticationProvider directoryThatThrows(RuntimeException failure) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication auth) {
                throw failure;
            }

            @Override
            public boolean supports(Class<?> authentication) {
                return true;
            }
        };
    }

    private void localRow(String username, int loginType, int enabled) {
        UserDetailsImpl details = new UserDetailsImpl();
        details.setUsername(username);
        details.setLoginType(loginType);
        details.setEnabled(enabled);
        details.setPermissions(List.of(PermissionEnum.FILE_INFO_PAGE));
        when(userService.createUserDetailsFromUser(username)).thenReturn(details);
    }
}
