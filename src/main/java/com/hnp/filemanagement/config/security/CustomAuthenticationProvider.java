package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.controller.FileCategoryController;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class CustomAuthenticationProvider implements AuthenticationProvider {

    Logger logger = LoggerFactory.getLogger(CustomAuthenticationProvider.class);
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {

        logger.debug("enter into custom auth");
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();

        if(username.equals("active") && password.equals("active")) {

            UserDetailsImpl userDetails = new UserDetailsImpl();
            userDetails.setId(10);
            userDetails.setUsername(username);
            userDetails.setPassword(password);
            userDetails.setEnabled(1);
            userDetails.setState(0);
            List<PermissionEnum> list = new ArrayList<>();
            list.add(PermissionEnum.ADMIN);
            userDetails.setPermissions(list);


            return new UsernamePasswordAuthenticationToken
                    (userDetails, password, List.of(new SimpleGrantedAuthority("ADMIN")));
        } else {
            throw new BadCredentialsException("not active user");
        }

//        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }
}
