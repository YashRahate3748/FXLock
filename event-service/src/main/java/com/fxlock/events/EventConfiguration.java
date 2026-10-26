package com.fxlock.events;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import static org.springframework.security.config.Customizer.withDefaults;
@Configuration
public class EventConfiguration {
 @Bean DefaultErrorHandler kafkaErrorHandler() {
  // Never silently discard poison messages or database failures; keep retrying this partition.
  var handler=new DefaultErrorHandler(new FixedBackOff(5000L,FixedBackOff.UNLIMITED_ATTEMPTS));
  handler.setClassifications(java.util.Map.of(Exception.class,true),true);
  return handler;
 }
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
  return http.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
   .anyRequest().hasRole("OPS")).httpBasic(withDefaults()).build();
 }
}
