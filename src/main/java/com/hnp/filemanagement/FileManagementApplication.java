package com.hnp.filemanagement;

import com.hnp.filemanagement.entity.Permission;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.PermissionRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@SpringBootApplication
public class FileManagementApplication extends SpringBootServletInitializer {

	public static void main(String[] args) {
		SpringApplication.run(FileManagementApplication.class, args);
	}


	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		return builder.sources(FileManagementApplication.class);
	}

	@Bean
	public CommandLineRunner runner(
			UserRepository userRepository,
			RoleRepository roleRepository,
			PermissionRepository permissionRepository) {

		return  args -> {

//            initialize(userRepository, roleRepository, permissionRepository);

		};
	}

	@Transactional
	public void initialize(
			UserRepository userRepository,
			RoleRepository roleRepository,
			PermissionRepository permissionRepository) {

		// create permissions if not exists
		List<Permission> allPermission = permissionRepository.findAll();
		for(PermissionEnum pe: PermissionEnum.values()) {
			if(allPermission.stream().filter(permission -> permission.getPermissionName().equals(pe)).findFirst().isEmpty()) {
				Permission permission = new Permission();
				permission.setPermissionName(pe);
				permissionRepository.save(permission);
			}
		}


		// create admin role if not exists
        Optional<Role> optionalAdminRole = roleRepository.findByRoleName("ADMIN");
        if(optionalAdminRole.isEmpty()) {
            Role role = new Role();
            role.setRoleName("ADMIN");
            roleRepository.save(role);
            roleRepository.save(role);
        }

		// create user role if not exists
		Optional<Role> optionalUserRole = roleRepository.findByRoleName("USER");
		if(optionalUserRole.isEmpty()) {
			Role role = new Role();
			role.setRoleName("USER");
			roleRepository.save(role);
			roleRepository.save(role);
		}

        // create admin user if not exists
        Optional<User> optionalUser = userRepository.findByIdOrUsername(0, "Admin");
        Role role = roleRepository.findByRoleName("ADMIN").get();
        if(optionalUser.isEmpty()) {
            User user = new User();
            user.setUsername("Admin");
            user.setFirstName("Admin");
            user.setLastName("Admin");
            user.setNationalCode("9999999999");
            user.setPhoneNumber("99999999997");
            user.setPersonelCode(9999);
            user.setPassword("password");
			user.setEnabled(1);
			user.setState(0);
            user.setCreatedAt(LocalDateTime.now());
            user.getRoles().add(role);
            userRepository.save(user);
        }




	}

}
