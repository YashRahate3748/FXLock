package com.fxlock.quote;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class SecurityConfiguration {
    @Bean BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(QuotePolicy policy, BCryptPasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
            User.withUsername("customer-demo").password(encoder.encode(policy.demoPassword())).roles("CUSTOMER").build(),
            User.withUsername("customer-other").password(encoder.encode(policy.demoPassword())).roles("CUSTOMER").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/css/**","/js/**","/actuator/health","/error").permitAll().anyRequest().authenticated())
            .formLogin(f -> f.defaultSuccessUrl("/",true)).logout(l -> l.logoutSuccessUrl("/login?logout"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),request -> request.getServletPath().startsWith("/quotes"))
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),request -> request.getServletPath().startsWith("/wallets/"))
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),request -> request.getServletPath().startsWith("/api/transaction-history"))
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),request -> request.getServletPath().startsWith("/api/customer/")))
            .build();
    }
}
