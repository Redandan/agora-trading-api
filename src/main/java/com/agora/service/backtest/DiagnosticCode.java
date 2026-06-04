package com.agora.service.backtest;

public enum DiagnosticCode {

    // MTF 資料準備
    MTF_INDEX_NOT_READY(
            "MTF_INDEX_NOT_READY",
            "MTF",
            "MTF timeframe index 尚未就緒（K 線資料不足）",
            true),
    MTF_CANDLE_MISSING(
            "MTF_CANDLE_MISSING",
            "MTF",
            "MTF 當根或前一根 K 線為 null",
            true),

    // 指標準備
    INDICATOR_SERIES_MISSING(
            "INDICATOR_SERIES_MISSING",
            "INDICATOR",
            "指標陣列缺失（未正確建立）",
            true),
    INDICATOR_VALUE_NOT_READY(
            "INDICATOR_VALUE_NOT_READY",
            "INDICATOR",
            "指標值為 NaN（warmup 期間未就緒）",
            true),

    // 進場過濾
    ADX_BELOW_THRESHOLD(
            "ADX_BELOW_THRESHOLD",
            "ENTRY_FILTER",
            "ADX 強度未達門檻，行情趨勢不足",
            true),
    LOOKBACK_NOT_ENOUGH(
            "LOOKBACK_NOT_ENOUGH",
            "ENTRY_FILTER",
            "Lookback K 線數量不足，無法計算關鍵位",
            true),
    REBOUND_NOT_READY(
            "REBOUND_NOT_READY",
            "ENTRY_FILTER",
            "Pullback→Rebound 序列尚未成立，等待 RSI 由回調區回升確認",
            true),
    CANDLE_BREAK_NOT_CONFIRMED(
            "CANDLE_BREAK_NOT_CONFIRMED",
            "ENTRY_FILTER",
            "requireCandleBreak=true 但收盤價未突破前根 K 線高點",
            true),
    LONG_SIGNALS_NOT_ENOUGH(
            "LONG_SIGNALS_NOT_ENOUGH",
            "ENTRY_FILTER",
            "多頭打分訊號數量未達 minSignals",
            true),
    SHORT_SIGNALS_NOT_ENOUGH(
            "SHORT_SIGNALS_NOT_ENOUGH",
            "ENTRY_FILTER",
            "空頭打分訊號數量未達 minSignals",
            true),
    RISK_REWARD_NOT_ENOUGH(
            "RISK_REWARD_NOT_ENOUGH",
            "ENTRY_FILTER",
            "風險報酬比未達 minRR，不符合進場條件",
            true),

    // 趨勢過濾
    SHORT_TREND_NOT_SUPPORTED(
            "SHORT_TREND_NOT_SUPPORTED",
            "TREND",
            "日線空頭趨勢成立，但策略尚未支援空頭進場",
            true),
    TREND_FILTER_BLOCKED(
            "TREND_FILTER_BLOCKED",
            "TREND",
            "日線趨勢未通過多頭條件（close < MA50 或 MACD <= 0）",
            true),

    // 進場訊號（1H 五項打分）
    EMA20_ABOVE(
            "EMA20_ABOVE",
            "SIGNAL",
            "1H close 在 EMA20 上方",
            true),
    EMA20_BELOW(
            "EMA20_BELOW",
            "SIGNAL",
            "1H close 在 EMA20 下方（空頭訊號）",
            true),
    RSI_PULLBACK(
            "RSI_PULLBACK",
            "SIGNAL",
            "1H RSI 低於回調門檻，短線拉回未過冷",
            true),
    RSI_OVERBOUGHT(
            "RSI_OVERBOUGHT",
            "SIGNAL",
            "1H RSI 高於做空門檻，短線超漲（空頭訊號）",
            true),
    MACD_GOLDEN_CROSS(
            "MACD_GOLDEN_CROSS",
            "SIGNAL",
            "1H MACD 金叉（MACD 線由下穿越 Signal 線）",
            true),
    MACD_DEATH_CROSS(
            "MACD_DEATH_CROSS",
            "SIGNAL",
            "1H MACD 死叉（MACD 線由上穿越 Signal 線）（空頭訊號）",
            true),
    BOLL_MID_CROSS_UP(
            "BOLL_MID_CROSS_UP",
            "SIGNAL",
            "1H 收盤價由下穿越布林中軌",
            true),
    BOLL_MID_CROSS_DOWN(
            "BOLL_MID_CROSS_DOWN",
            "SIGNAL",
            "1H 收盤價由上穿越布林中軌（空頭訊號）",
            true),
    VOLUME_ABOVE_MA(
            "VOLUME_ABOVE_MA",
            "SIGNAL",
            "1H 成交量大於量能移動平均",
            true),

    // 通用
    NO_TRADE_TRIGGERED(
            "NO_TRADE_TRIGGERED",
            "GENERAL",
            "本次回測未觸發任何交易",
            true),
    DIAGNOSTIC_PARSE_FALLBACK(
            "DIAGNOSTIC_PARSE_FALLBACK",
            "GENERAL",
            "診斷日誌反序列化失敗，以原始內容回傳",
            true);

    private final String code;
    private final String type;
    private final String desc;
    private final boolean enable;

    DiagnosticCode(String code, String type, String desc, boolean enable) {
        this.code = code;
        this.type = type;
        this.desc = desc;
        this.enable = enable;
    }

    public String getCode() {
        return code;
    }

    public String getType() {
        return type;
    }

    public String getDesc() {
        return desc;
    }

    public boolean isEnable() {
        return enable;
    }
}
