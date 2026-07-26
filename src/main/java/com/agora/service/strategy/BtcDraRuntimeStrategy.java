package com.agora.service.strategy;

import com.agora.model.MdKline;
import com.agora.service.trading.BtcDraLiveExecutionService;
import com.agora.service.trading.BtcDraPolicy;
import com.agora.service.trading.BtcDraRuntimeLaneService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Thin DRA strategy adapter that preserves evidence-before-execution ordering.
 */
@Component
@RequiredArgsConstructor
public class BtcDraRuntimeStrategy implements RuntimeStrategy {

    private static final int EVALUATION_ORDER = 300;

    private final BtcDraRuntimeLaneService runtimeLaneService;
    private final BtcDraLiveExecutionService liveExecutionService;

    @Override
    public String key() {
        return BtcDraPolicy.POLICY_MODE;
    }

    @Override
    public int evaluationOrder() {
        return EVALUATION_ORDER;
    }

    @Override
    public void onClosedBar(MdKline kline) {
        BtcDraRuntimeLaneService.RuntimeObservation observation =
                runtimeLaneService.evaluate(kline);
        liveExecutionService.evaluate(observation);
    }
}
