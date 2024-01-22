package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ActiveDirectoryCustomAuthenticationProvider implements AuthenticationProvider {

    Logger logger = LoggerFactory.getLogger(ActiveDirectoryCustomAuthenticationProvider.class);

    @Value("${filemanagement.auth.ldap.activedirectory.domain}")
    private String domain;

    @Value("${filemanagement.auth.ldap.activedirectory.url}")
    private String url;

    @Value("${filemanagement.auth.ldap.activedirectory.enabled:false}")
    private boolean enabled;

    private final UserDetailsService userDetailsService;

    private final UserService userService;

    public ActiveDirectoryCustomAuthenticationProvider(UserDetailsService userDetailsService, UserService userService) {
        this.userDetailsService = userDetailsService;
        this.userService = userService;
    }


    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {


        logger.debug("enter into active directory auth provider");
        String username = authentication.getName();
        String password = authentication.getCredentials().toString();
//
//        String domain = "hnp.local";
//        String url =  "ldap://172.29.76.9";

        ActiveDirectoryLdapAuthenticationProvider activeDirectoryLdapAuthenticationProvider =
                new ActiveDirectoryLdapAuthenticationProvider( domain, url);

        // to parse AD failed credentails error message due to account - expiry,lock, credentialis - expiry,lock
        activeDirectoryLdapAuthenticationProvider.setConvertSubErrorCodesToExceptions(true);


        Authentication authenticate = activeDirectoryLdapAuthenticationProvider.authenticate(new UsernamePasswordAuthenticationToken(username, password));

        if(authenticate.isAuthenticated()) {
            LdapUserDetails ldapUserDetails = (LdapUserDetails) authenticate.getPrincipal();
            logger.debug("extracted username from active directory=" + ldapUserDetails.getUsername());
//            UserDetailsImpl userDetails = (UserDetailsImpl) userDetailsService.loadUserByUsername(ldapUserDetails.getUsername());
            UserDetailsImpl userDetails = (UserDetailsImpl) userService.createUserDetailsFromUser(ldapUserDetails.getUsername());

//            logger.debug("login type => " + userDetails.getLoginType());
            if(userDetails.getLoginType() != 0 && userDetails.getLoginType() != 2) {
                return null;
            }


//            userDetails.getPermissions().forEach(System.out::println);
//            logger.debug("enabled ? => " + userDetails.getEnabled());


            if(userDetails.getEnabled() == 1) {
                return new UsernamePasswordAuthenticationToken
                        (userDetails, null, userDetails.getAuthorities());
            }
        }


        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return authentication.equals(UsernamePasswordAuthenticationToken.class);
    }

}
