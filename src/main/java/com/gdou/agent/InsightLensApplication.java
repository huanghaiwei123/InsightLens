package com.gdou.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@MapperScan("com.gdou.agent.api.mapper")
@EnableTransactionManagement
public class InsightLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightLensApplication.class, args);
    }

}
