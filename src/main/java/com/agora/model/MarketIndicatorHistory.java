package com.agora.model;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 每小時市場指標歷史快照(V040)。
 *
 * <p>由 {@code MarketIndicatorHistoryCollector} 每小時 :01 寫入一筆(per symbol × indicator)。
 * 累積 3-6 個月後可用於:
 * <ul>
 *   <li>MarketFlip event 的歷史脈絡對比(「這個 whale_buy_ratio=76% 在近 7 天歷史裡是否極端?」)</li>
 *   <li>ML/AI 判斷時的 feature 注入</li>
 *   <li>回測 MarketFlip trigger 在歷史上是否有 predictive power</li>
 * </ul>
 *
 * <p>外部 API(CoinGlass / Coinalyze)需 API key 與付費考量;此表走自建路線,
 * 每次 tick 失敗不阻塞其他 indicator(失敗即 log warn 跳過)。
 */
@Data
@Entity
@Table(name = "market_indicator_history",
        indexes = {
                @Index(name = "idx_mih_sym_ind_captured",
                       columnList = "symbol, indicator, captured_at DESC"),
                @Index(name = "idx_mih_sym_ind_err_captured_value",
                       columnList = "symbol, indicator, error_flag, captured_at DESC, value"),
                @Index(name = "idx_mih_captured", columnList = "captured_at DESC")
        })
public class MarketIndicatorHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(nullable = false, length = 20)
    private String symbol;

    /** fear_greed / whale_buy_ratio / funding_rate / long_short_ratio / orderbook_imbalance */
    @Column(nullable = false, length = 32)
    private String indicator;

    @Column(nullable = false, precision = 12, scale = 6)
    private BigDecimal value;

    /** 額外資訊(聚合 periods、API latency、原始片段等),可 null */
    @Column(name = "metadata_json", columnDefinition = "JSON")
    private String metadataJson;

    /** #234: 1 = API-returned garbage, exclude from ML training. Default 0. */
    @Column(name = "error_flag", nullable = false)
    private boolean errorFlag = false;

    @Column(name = "error_reason", length = 255)
    private String errorReason;
}
