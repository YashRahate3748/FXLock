package com.ofss.fxlock.rate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(
            PasswordEncoder encoder,

            @Value(
                "${security.service.username}"
            )
            String serviceUsername,

            @Value(
                "${security.service.password}"
            )
            String servicePassword,

            @Value(
                "${security.risk.username}"
            )
            String riskUsername,

            @Value(
                "${security.risk.password}"
            )
            String riskPassword
    ) {

        UserDetails serviceUser =
                User.withUsername(
                        serviceUsername
                )
                .password(
                        encoder.encode(
                                servicePassword
                        )
                )
                .roles("SERVICE")
                .build();

        UserDetails riskUser =
                User.withUsername(
                        riskUsername
                )
                .password(
                        encoder.encode(
                                riskPassword
                        )
                )
                .roles(
                        "RISK_ANALYST",
                        "SERVICE"
                )
                .build();

        return new InMemoryUserDetailsManager(
                serviceUser,
                riskUser
        );
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http
    ) throws Exception {

        http

            .csrf(csrf -> csrf.disable())

            .authorizeHttpRequests(auth -> auth

                .requestMatchers(
                    "/actuator/health"
                )
                .permitAll()

                .requestMatchers(
                    "/api/v1/risk/**"
                )
                .hasRole("RISK_ANALYST")

                .requestMatchers(
                    "/api/v1/rates/**"
                )
                .hasAnyRole(
                    "SERVICE",
                    "RISK_ANALYST"
                )

                .anyRequest()
                .authenticated()
            )

            .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}