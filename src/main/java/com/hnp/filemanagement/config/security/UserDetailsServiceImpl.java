package com.hnp.filemanagement.config.security;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final Logger logger = LoggerFactory.getLogger(UserDetailsServiceImpl.class);


    private final UserService userService;


    public UserDetailsServiceImpl(UserService userService) {
        this.userService = userService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {


        try {
            User user = userService.getUserByUsername(username);




            UserDetailsImpl userDetails = new UserDetailsImpl();
            userDetails.setId(user.getId());
            userDetails.setUsername(user.getUsername());
            userDetails.setPassword(user.getPassword());
            userDetails.setEnabled(user.getEnabled());
            userDetails.setState(user.getState());

            List<Permission> allPermissionsOfUser = userService.getAllPermissionsOfUser(user.getId());

            List<PermissionEnum> list = new java.util.ArrayList<>(allPermissionsOfUser.stream().map(Permission::getPermissionName).toList());


            Optional<Role> adminRole = user.getRoles().stream().filter(role -> role.getRoleName().equalsIgnoreCase("ADMIN")).findFirst();
            if(adminRole.isPresent()) {
                list.add(PermissionEnum.ADMIN);
            }


            userDetails.setPermissions(list);


            return userDetails;
        } catch (ResourceNotFoundException e) {
//            logger.debug("load by username exception", e);
            throw new UsernameNotFoundException("username not found, username=" + username);
        }

    }
}
