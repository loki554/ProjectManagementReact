package com.pmtracker.project_management_backend;

import com.pmtracker.project_management_backend.support.IntegrationTest;
import org.junit.jupiter.api.Test;

// Профиль, датасорс и SMTP приходят из IntegrationTest: контекст поднимается на профиле test
// поверх Postgres в Testcontainers. Раньше здесь стоял @ActiveProfiles("dev"), и тест требовал
// поднятого docker-compose и .env с DB_PASSWORD — то есть ./mvnw test на чистой машине падал
// с "password authentication failed" и выглядел как сломанное приложение.
class ProjectManagementBackendApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

}
