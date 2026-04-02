package com.sitemanager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                // Static resources
                .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/favicon.ico").permitAll()
                // Auth endpoints
                .requestMatchers("/api/auth/**").permitAll()
                // Settings read is public, write requires auth
                .requestMatchers(HttpMethod.GET, "/api/settings").permitAll()
                // Suggestions - reading is public
                .requestMatchers(HttpMethod.GET, "/api/suggestions/**").permitAll()
                // Creating suggestions may be anonymous (checked in controller)
                .requestMatchers(HttpMethod.POST, "/api/suggestions").permitAll()
                // Replying to suggestion threads - can be anonymous
                .requestMatchers(HttpMethod.POST, "/api/suggestions/*/messages").permitAll()
                // Voting can be anonymous
                .requestMatchers(HttpMethod.POST, "/api/suggestions/*/vote").permitAll()
                // WebSocket
                .requestMatchers("/ws/**").permitAll()
                // All other API endpoints - permit all but check auth in controllers
                .requestMatchers("/api/**").permitAll()
                .anyRequest().permitAll()
            )
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
