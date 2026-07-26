package com.agora.service.strategy;

import com.agora.model.MdKline;

/**
 * Minimal Production strategy boundary for one closed-bar runtime lane.
 *
 * <p>Implementations retain ownership of their strategy state, evidence, and
 * optional execution adapter. This interface only standardizes registration
 * and dispatch; it does not add a platform risk gate or exchange behavior.</p>
 */
public interface RuntimeStrategy {

    String key();

    int evaluationOrder();

    void onClosedBar(MdKline kline);
}
