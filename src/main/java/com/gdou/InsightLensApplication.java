package com.gdou;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.gdou.agent")
public class InsightLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightLensApplication.class, args);
    }

}
