package com.agora.service.advice;

import com.agora.config.BtcOrderAdviceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceConfigurationTest {
    @Test void documentedEnvironmentNamesBindWithoutEnablingTrading() throws Exception {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("advice-test", Map.of(
                "TRADING_BTC_ORDER_ADVICE_MONITOR_ENABLED", "true", "TRADING_BTC_ORDER_ADVICE_NOTIFICATIONS_ENABLED", "false",
                "TRADING_BTC_ORDER_ADVICE_TRADE_FRACTION", "0.2", "TRADING_BTC_ORDER_ADVICE_STATE_FILE", "test-state.json")));
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")))
            env.getPropertySources().addLast(source);
        var props = Binder.get(env).bind("trading.btc-order-advice", BtcOrderAdviceProperties.class).orElseThrow(AssertionError::new);
        assertTrue(props.isMonitorEnabled()); assertFalse(props.isNotificationsEnabled());
        assertEquals("0.2", props.policy().tradeFraction().toPlainString());
        assertEquals("test-state.json", props.getStateFile());
        assertFalse(new BtcOrderAdviceProperties().isMonitorEnabled());
        assertFalse(new BtcOrderAdviceProperties().isNotificationsEnabled());
    }
}
