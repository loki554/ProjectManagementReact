package com.pmtracker.project_management_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling — нужен NotificationScheduler (task_due_soon/task_overdue, см. notification/).
@SpringBootApplication
@EnableScheduling
public class ProjectManagementBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProjectManagementBackendApplication.class, args);
	}

}
