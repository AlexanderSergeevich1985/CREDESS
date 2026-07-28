package org.credess;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CredessApplication {
    public static void main(String[] args) {
        SpringApplication.run(CredessApplication.class, args);
    }
}
