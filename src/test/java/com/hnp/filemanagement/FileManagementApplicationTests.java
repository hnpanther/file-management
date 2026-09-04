package com.hnp.filemanagement;

import com.hnp.filemanagement.support.MySqlSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the whole application context — web layer, security, Thymeleaf, JPA and Flyway —
 * has to start against a real database. Nothing else in the suite proves that, because every other
 * test is a {@code @DataJpaTest} slice that never sees a controller or a filter chain.
 */
@SpringBootTest
class FileManagementApplicationTests extends MySqlSupport {

	@Test
	void contextLoads() {
	}

}
