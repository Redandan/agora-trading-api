package com.agora.service.ai.skill;

import com.agora.dto.ExchangeRateInfo;
import com.agora.service.ExchangeRateService;
import com.agora.service.ai.GroqApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeRateSkill implements AiSkill {

    private final ExchangeRateService exchangeRateService;
    private final GroqApiClient groqApiClient;

    @Override
    public String getIntentCode() {
        return "EXCHANGE_RATE";
    }

    @Override
    public String getIntentDescription() {
        return "詢問匯率、法幣兌換（例：USDT換台幣、匯率多少）";
    }

    @Override
    public String execute(String text) {
        List<ExchangeRateInfo> rates = exchangeRateService.getAllUsdtRates();
        if (rates.isEmpty()) {
            return "目前無法取得匯率資料 💱";
        }

        StringBuilder rateContext = new StringBuilder();
        for (ExchangeRateInfo info : rates) {
            rateContext.append(String.format("USDT → %s（%s）：%s %s\n",
                    info.getToCurrency(),
                    info.getCurrencyName() != null ? info.getCurrencyName() : info.getToCurrency(),
                    info.getRate().toPlainString(),
                    info.getSymbol() != null ? info.getSymbol() : ""));
        }

        if (!groqApiClient.isEnabled()) {
            return "💱 USDT 即時匯率\n\n" + rateContext.toString().trim();
        }

        try {
            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> system = new HashMap<>();
            system.put("role", "system");
            system.put("content",
                "你是一個友善的加密貨幣助理。根據以下即時匯率資料，用繁體中文、口語化、簡短地回答用戶的匯率問題。" +
                "不要逐條列出數據格式，用自然語言說明就好。\n\n即時匯率資料：\n" + rateContext);
            messages.add(system);
            Map<String, String> user = new HashMap<>();
            user.put("role", "user");
            user.put("content", text);
            messages.add(user);
            String reply = groqApiClient.chat(messages);
            return reply != null ? reply.trim() : "💱 USDT 即時匯率\n\n" + rateContext.toString().trim();
        } catch (Exception e) {
            log.debug("Groq 匯率回覆失敗，降級為純文字: {}", e.getMessage());
            return "💱 USDT 即時匯率\n\n" + rateContext.toString().trim();
        }
    }

    @Override
    public SkillResponse executeRich(String text) {
        String result = execute(text);
        return result != null ? SkillResponse.text(result) : null;
    }
}
