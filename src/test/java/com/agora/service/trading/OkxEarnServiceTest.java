package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OkxEarnServiceTest {

    @Test
    void topUpTradingBufferReturnsFalseBeforeAnyOkxCallWhenDisabled() {
        OkxEarnService service = new OkxEarnService(new OkxTradingProperties(), new ObjectMapper());
        ReflectionTestUtils.setField(service, "topupEnabled", false);

        assertThat(service.topUpTradingBuffer(BigDecimal.ZERO)).isFalse();
    }
}
