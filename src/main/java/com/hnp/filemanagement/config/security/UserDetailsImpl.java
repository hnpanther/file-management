package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.entity.PermissionEnum;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

@Data
public class UserDetailsImpl implements UserDetails {

    private int id;
    private String username;
    private List<PermissionEnum> permissions;
    private String password;
    private int enabled;
    private int state;
    private int loginType;

    /**
     * Set only when this request authenticated with an API key rather than as a person
     * (roadmap 9.2); null otherwise.
     *
     * <p>{@link #id} still names the user the key was created by, so every service that takes a
     * {@code principalId} and every {@code action_history} row keep working unchanged. This field is
     * what stops a key inheriting that person's folder grants: {@code FolderAccessService} resolves
     * the key's own scopes when it is present.
     */
    private Integer apiKeyId;


    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {

        return permissions.stream().map(p -> new SimpleGrantedAuthority(p.name())).toList();
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled == 1;
    }

}
