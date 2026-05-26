package com.yakuso.psychat;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.yakuso.psychat.mapper")
public class PsychatApplication {

    public static void main(String[] args) {
        SpringApplication.run(PsychatApplication.class, args);
    }
}
