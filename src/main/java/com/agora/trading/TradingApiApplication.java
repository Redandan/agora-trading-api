package com.agora.trading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agora")
@EnableJpaRepositories(basePackages = {
        "com.agora.repository.trading",
        "com.agora.repository.system"
})
@EntityScan("com.agora.model")
@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan("com.agora")
public class TradingApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingApiApplication.class, args);
    }
}
