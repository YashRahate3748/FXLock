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
    @Bean SecurityFilterChain security(HttpSecurity http, AccountClient accounts, AccountValidation validation,
            org.springframework.core.env.Environment environment) throws Exception {
        boolean databaseAccounts = environment.acceptsProfiles(org.springframework.core.env.Profiles.of("team", "oracle-local"));
        var csrf = new org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository();
        http.csrf(configuration -> configuration.csrfTokenRepository(csrf).ignoringRequestMatchers("/internal/dashboard/redeem"));
        if (databaseAccounts) {
            http.addFilterBefore(M3BrowserLogin.filter(accounts, validation, csrf),
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        }
        return http.authorizeHttpRequests(a -> a.requestMatchers("/internal/dashboard/redeem", "/dashboard/logout", "/login","/demo-login","/register","/css/**","/js/**","/actuator/health","/error").permitAll().anyRequest().authenticated())
            .formLogin(f -> f.loginPage("/login")
                .loginProcessingUrl(databaseAccounts ? "/demo-login" : "/login")
                .defaultSuccessUrl("/",true)
                .failureUrl(databaseAccounts ? "/login?demo=true&error" : "/login?error"))
            .logout(l -> l.logoutSuccessUrl(environment.getProperty("fxlock.dashboard-url", "http://localhost:8083") + "/dashboard/logout"))
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),request -> request.getServletPath().startsWith("/quotes"))
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),request -> request.getServletPath().startsWith("/wallets/")))
            .build();
    }
}
