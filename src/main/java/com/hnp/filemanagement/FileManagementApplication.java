package com.hnp.filemanagement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * The application entry point.
 *
 * <p>The database bootstrap used to live here as a {@code @Transactional} method this class called
 * on itself — which meant the annotation never applied, because Spring's transaction support is a
 * proxy and a self-call does not pass through it. It is now
 * {@link com.hnp.filemanagement.config.bootstrap.DataInitializer}, driven by
 * {@link com.hnp.filemanagement.config.bootstrap.BootstrapConfig}, so the seeding is one
 * transaction and the test slices can exclude it.
 *
 * <p>The password encoder stays here on purpose: it is a plain, dependency-free bean that the
 * service layer needs, and declaring it on the {@code @SpringBootConfiguration} class is what makes
 * it available to the {@code @DataJpaTest} slices, which filter out {@code @Configuration} classes.
 */
@SpringBootApplication
public class FileManagementApplication {

	public static void main(String[] args) {
		SpringApplication.run(FileManagementApplication.class, args);
	}

	@Bean
	public BCryptPasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
