package com.fxlock.quote;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(QuotePolicy.class)
public class QuoteApplication {
    public static void main(String[] args) { SpringApplication.run(QuoteApplication.class, args); }
    @Bean Clock clock() { return Clock.systemUTC(); }
}
