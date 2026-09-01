package com.netcracker.cloud.storagetestservice.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ServiceConfiguration {

    /**
     * The suite drives this contract over a port-forward and carries no token or CSRF token. The
     * API is stateless, so there is no session for a forged request to ride on.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}
