package com.gdou.agent.config;

import com.gdou.agent.interceptor.ChatInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.rmi.registry.Registry;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
//        registry.addInterceptor(new ChatInterceptor()).addPathPatterns("/**");
    }
}
