package com.example.events;
import com.example.contract.EventEnvelope;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
public class Infrastructure {
    @Bean KafkaAdmin.NewTopics topics(@Value("${fx.partitions}") int partitions, @Value("${fx.replicas}") int replicas) {
        return new KafkaAdmin.NewTopics(EventEnvelope.TOPICS.stream().map(t -> TopicBuilder.name(t)
                .partitions(partitions).replicas(replicas).build()).toArray(NewTopic[]::new));
    }
    // Never silently skip a failed audit record. Operators fix/replay malformed records using the runbook.
    @Bean DefaultErrorHandler errorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(5000, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
                .anyRequest().hasRole("OPS")).httpBasic(Customizer.withDefaults()).build();
    }
    @Bean UserDetailsService users(@Value("${fx.ops-user}") String user, @Value("${fx.ops-password}") String password) {
        return new InMemoryUserDetailsManager(User.withUsername(user).password("{noop}" + password).roles("OPS").build());
    }
}
