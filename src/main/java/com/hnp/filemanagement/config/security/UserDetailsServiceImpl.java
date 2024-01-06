package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final Logger logger = LoggerFactory.getLogger(UserDetailsServiceImpl.class);


    private final UserService userService;


    public UserDetailsServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        logger.debug("security: login by username=" + username);
        try {
            User user = userService.getUserByUsername(username);

            logger.debug("user find, userId=" + user.getId());


            UserDetailsImpl userDetails = new UserDetailsImpl();
            userDetails.setId(user.getId());
            userDetails.setUsername(user.getUsername());
            userDetails.setPassword(user.getPassword());
            userDetails.setEnabled(user.getEnabled());
            userDetails.setState(user.getState());

            List<Permission> allPermissionsOfUser = userService.getAllPermissionsOfUser(user.getId());

            List<PermissionEnum> list = allPermissionsOfUser.stream().map(Permission::getPermissionName).toList();
            userDetails.setPermissions(list);

            logger.debug("load permissions is ok");
            return userDetails;
        } catch (Exception e) {
            logger.debug("load by username exception", e);
            throw new UsernameNotFoundException("username not found");
        }

    }
}
