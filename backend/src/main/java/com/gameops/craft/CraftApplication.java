package com.gameops.craft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CraftApplication {
    public static void main(String[] args) {
        SpringApplication.run(CraftApplication.class, args);
    }
}
