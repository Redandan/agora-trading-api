package com.agora.service.ai.skill;

import com.agora.infra.skill.TradingAnalysisFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI Skill：交易機會分析。
 *
 * <p>當用戶詢問市場行情、是否值得買入、交易機會等問題時，
 * IntentClassifier 會分類為 TRADING_ANALYSIS，觸發此 Skill 執行
 * Fear&amp;Greed + 鯨魚流向 + 技術指標的 AI 綜合分析。</p>
 *
 * <p>由於 {@link com.agora.service.ai.GroupAIChatService} 以 {@code @Async} 執行，
 * 此 Skill 的 Groq 呼叫不會阻塞主執行緒。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingAnalysisSkill implements AiSkill {

    private final TradingAnalysisFacade tradingAnalysisFacade;

    @Override
    public String getIntentCode() {
        return "TRADING_ANALYSIS";
    }

    @Override
    public String getIntentDescription() {
        return "詢問加密貨幣交易機會、是否值得買入、市場分析、BTC/ETH 趨勢看法（例：有沒有交易機會、現在可以買BTC嗎、行情怎麼樣）";
    }

    @Override
    public String execute(String text) {
        log.info("[TradingAnalysisSkill] Triggered by: {}", text);
        try {
            return tradingAnalysisFacade.analyze();
        } catch (Exception e) {
            log.error("[TradingAnalysisSkill] Analysis failed: {}", e.getMessage(), e);
            return null;
        }
    }

    @Override
    public SkillResponse executeRich(String text) {
        String result = execute(text);
        return result != null ? SkillResponse.html(result) : null;
    }

    @Override
    public boolean needsFallback() {
        // 分析失敗時，讓 AI 用一般聊天方式回應
        return true;
    }
}
