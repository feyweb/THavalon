package com.thavalon;

import com.thavalon.domain.Dealer;
import com.thavalon.game.ThavalonProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ThavalonProperties.class)
public class ThavalonApplication {

    public static void main(String[] args) {
        SpringApplication.run(ThavalonApplication.class, args);
    }

    /** Backed by SecureRandom, so no two games share a seed. */
    @Bean
    public Dealer dealer() {
        return new Dealer();
    }
}
