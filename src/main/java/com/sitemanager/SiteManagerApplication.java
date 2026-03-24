package com.sitemanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SiteManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiteManagerApplication.class, args);
    }
}
