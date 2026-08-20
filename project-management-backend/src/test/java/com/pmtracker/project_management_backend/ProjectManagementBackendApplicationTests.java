package com.pmtracker.project_management_backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Профиль нужен явно: в application.properties spring.profiles.active больше нет (иначе он
// уехал бы в jar и стал бы профилем прода), а Maven-свойство spring-boot.run.profiles
// действует только на spring-boot:run. Без этой аннотации контекст в тестах поднимался бы
// вообще без датасорса, почты и JWT-секрета и падал бы на старте.
@ActiveProfiles("dev")
@SpringBootTest
class ProjectManagementBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
