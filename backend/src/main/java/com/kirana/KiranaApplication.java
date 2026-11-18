package com.kirana;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// Auth is JWT-only, so Spring's in-memory default user is not wanted.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class KiranaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KiranaApplication.class, args);
    }
}
