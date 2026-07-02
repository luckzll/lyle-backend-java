package com.example.aiapp;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan("com.example.aiapp.mapper")
@SpringBootApplication
public class AiAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiAppApplication.class, args);
    }
}
