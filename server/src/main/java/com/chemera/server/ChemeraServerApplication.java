package com.chemera.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.chemera.server.mapper")
@EnableScheduling
public class ChemeraServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChemeraServerApplication.class, args);
    }
}
