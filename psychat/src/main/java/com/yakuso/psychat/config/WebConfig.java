package com.yakuso.psychat.config;

import com.yakuso.psychat.common.TraceFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration() {
        FilterRegistrationBean<TraceFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new TraceFilter());
        bean.addUrlPatterns("/api/*");
        bean.setOrder(1);
        return bean;
    }
}
