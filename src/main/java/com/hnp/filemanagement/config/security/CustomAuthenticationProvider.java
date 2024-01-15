package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.controller.FileCategoryController;
import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.CommunicationException;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.filter.Filter;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class CustomAuthenticationProvider implements AuthenticationProvider {

    Logger logger = LoggerFactory.getLogger(CustomAuthenticationProvider.class);

    @Autowired
    private UserDetailsService userDetailsService;



    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {


        logger.debug("enter into custom auth");
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();

        String domain = "hnp.local";
        String url =  "ldap://172.29.76.9";

        ActiveDirectoryLdapAuthenticationProvider activeDirectoryLdapAuthenticationProvider =
                new ActiveDirectoryLdapAuthenticationProvider( "hnp.local", "ldap://172.29.76.9");

        // to parse AD failed credentails error message due to account - expiry,lock, credentialis - expiry,lock
        activeDirectoryLdapAuthenticationProvider.setConvertSubErrorCodesToExceptions(true);


        Authentication authenticate = activeDirectoryLdapAuthenticationProvider.authenticate(new UsernamePasswordAuthenticationToken(username, password));

        if(authenticate.isAuthenticated()) {
            LdapUserDetails ldapUserDetails = (LdapUserDetails) authenticate.getPrincipal();
            logger.debug("extracted username=" + ldapUserDetails.getUsername());
            UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByUsername(ldapUserDetails.getUsername());
            return new UsernamePasswordAuthenticationToken
                    (userDetails, password);
        }


        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }

    private String rootDnFromDomain(String domain) {
        String[] tokens = StringUtils.tokenizeToStringArray(domain, ".");
        StringBuilder root = new StringBuilder();
        String[] var4 = tokens;
        int var5 = tokens.length;

        for(int var6 = 0; var6 < var5; ++var6) {
            String token = var4[var6];
            if (root.length() > 0) {
                root.append(',');
            }

            root.append("dc=").append(token);
        }

        return root.toString();
    }
}
