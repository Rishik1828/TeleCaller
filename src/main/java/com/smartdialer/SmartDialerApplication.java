package com.smartdialer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmartDialerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartDialerApplication.class, args);
    }
}
