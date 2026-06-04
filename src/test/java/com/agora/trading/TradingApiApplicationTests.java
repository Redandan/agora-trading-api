package com.agora.trading;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.chat.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:trading-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class TradingApiApplicationTests {

    @Test
    void contextLoads() {
    }
}
