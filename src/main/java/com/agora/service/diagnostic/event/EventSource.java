package com.agora.service.diagnostic.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #337 EventSource — 抽取「**指標 / 訊號 fire 過的時點 + 預期方向**」的統一介面。
 *
 * <p>每個 source 自己定義 \\{@code filter\\} 字串語意（見 issue #337 表格），
 * 例如：
 * <ul>
 *   <li>{@code tg_indicator}：filter = TG \\{@code source\\} 欄位 LIKE，如 {@code ShortBuildIndicator}</li>
 *   <li>{@code mih_threshold}：filter = "indicator:operator:value"，如 {@code funding_rate:lte:-0.0003}</li>
 *   <li>{@code decision_audit}：filter = event_type，如 {@code FILTER_BLOCK} / {@code ATTENTION_HIT}</li>
 *   <li>{@code ml_inference}：filter = "versionId:decision"，如 {@code 19:BLOCK}</li>
 *   <li>{@code live_signal}：filter = strategyId 數字</li>
 * </ul>
 */
public interface EventSource {

    /** Source 名稱，必須與 issue #337 表格一致（tg_indicator / mih_threshold / ...） */
    String name();

    /**
     * 抽取時間範圍內所有 event。
     *
     * @param filter source-specific 過濾字串（見 class javadoc）；可為 null 代表不過濾
     * @param from   起點（含）
     * @param to     終點（含）
     * @return 依時間升冪排序的 events
     * @throws IllegalArgumentException filter 字串格式不合法（例如 mih_threshold 無 ':'）
     */
    List<Event> fetch(String filter, LocalDateTime from, LocalDateTime to);
}
