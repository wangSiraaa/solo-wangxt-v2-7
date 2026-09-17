package com.gameops.craft.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
    /** Single UTC clock; replaceable in tests to simulate activity end / timeout. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
