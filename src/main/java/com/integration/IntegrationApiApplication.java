package com.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling drives ReminderScheduler, which polls once a minute for todos
// whose reminder is due and pushes them to the user's subscribed browsers.
@EnableScheduling
@SpringBootApplication
public class IntegrationApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationApiApplication.class, args);
    }
}