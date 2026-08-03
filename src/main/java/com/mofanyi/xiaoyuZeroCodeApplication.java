package com.mofanyi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@MapperScan("com.mofanyi.mapper")
public class xiaoyuZeroCodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(xiaoyuZeroCodeApplication.class, args);
    }
}
