package com.agora.service.ai.skill;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.ai.GroqApiClient;
import com.agora.infra.skill.PriceFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceQuerySkill implements AiSkill {

    private final MdKlineRepository klineRepository;
    private final GroqApiClient groqApiClient;
    private final PriceFacade priceFacade;

    @Override
    public String getIntentCode() {
        return "PRICE";
    }

    @Override
    public String getIntentDescription() {
        return "詢問加密貨幣價格、行情、漲跌（例：BTC多少、ETH現在幾錢）";
    }

    @Override
    public String execute(String text) {
        SkillResponse r = executeRich(text);
        return r != null ? r.getText() : null;
    }

    @Override
    public SkillResponse executeRich(String text) {
        String symbol = extractSymbol(text);
        if (symbol == null) {
            return SkillResponse.text(queryAllAvailablePrices());
        }

        // 優先從 Binance REST API 拿最新資料，失敗才 fallback 到 DB
        MdKline k = priceFacade.fetchLatestKline(symbol + "USDT", "1m");
        if (k == null) {
            log.warn("[PriceQuerySkill] Binance API unavailable for {}, falling back to DB", symbol);
            List<MdKline> latest = klineRepository
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(symbol + "USDT", "1h", PageRequest.of(0, 1));
            if (latest.isEmpty()) {
                latest = klineRepository
                        .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(symbol + "USDT", "15m", PageRequest.of(0, 1));
            }
            if (latest.isEmpty()) {
                return SkillResponse.text("目前沒有 " + symbol + " 的行情資料 📊");
            }
            k = latest.get(0);
        }
        String priceText = String.format("📊 %sUSDT 最新行情\n\n" +
                        "💰 現價：%s USDT\n" +
                        "📈 最高：%s\n" +
                        "📉 最低：%s\n" +
                        "🕐 時間：%s (UTC)\n" +
                        "%s",
                symbol,
                k.getClosePrice().toPlainString(),
                k.getHighPrice().toPlainString(),
                k.getLowPrice().toPlainString(),
                k.getOpenTime().toString().replace("T", " ").substring(0, 16),
                calculateChange(k));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(java.util.List.of(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        InlineKeyboardButton.builder().text("🔄 重新整理").callbackData("price_refresh:" + symbol).build())))
                .build();

        return SkillResponse.withKeyboard(priceText, keyboard);
    }

    private String extractSymbol(String text) {
        if (text == null) return null;
        if (groqApiClient.isEnabled()) {
            try {
                List<Map<String, String>> messages = new ArrayList<>();
                Map<String, String> system = new HashMap<>();
                system.put("role", "system");
                system.put("content",
                    "你是加密貨幣助理，從用戶訊息中提取幣種代碼。" +
                    "只回傳大寫英文代碼（如 BTC、ETH、SOL），不要有其他文字。" +
                    "若訊息中沒有明確幣種，回傳 UNKNOWN。");
                messages.add(system);
                Map<String, String> user = new HashMap<>();
                user.put("role", "user");
                user.put("content", text);
                messages.add(user);
                String result = groqApiClient.chat(messages);
                if (result != null) {
                    result = result.trim().toUpperCase();
                    if (!result.equals("UNKNOWN") && result.matches("[A-Z0-9]{2,10}")) {
                        return result;
                    }
                }
                return null;
            } catch (Exception e) {
                log.debug("Groq 提取幣種失敗，使用簡易提取: {}", e.getMessage());
            }
        }
        // 降級：簡易關鍵字匹配
        String t = text.toUpperCase();
        String[] symbols = {"BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA", "AVAX"};
        for (String s : symbols) {
            if (t.contains(s)) return s;
        }
        if (t.contains("比特幣")) return "BTC";
        if (t.contains("以太幣") || t.contains("以太坊")) return "ETH";
        if (t.contains("狗狗幣")) return "DOGE";
        return null;
    }

    private String calculateChange(MdKline k) {
        try {
            double open = k.getOpenPrice().doubleValue();
            double close = k.getClosePrice().doubleValue();
            double change = ((close - open) / open) * 100;
            return String.format("%s 漲跌：%+.2f%%", change >= 0 ? "🟢" : "🔴", change);
        } catch (Exception e) {
            return "";
        }
    }

    private String queryAllAvailablePrices() {
        List<String> symbols = klineRepository.findDistinctSymbols();
        if (symbols.isEmpty()) return "目前沒有行情資料 📊";
        StringBuilder sb = new StringBuilder("📊 可查詢的交易對\n\n");
        for (String s : symbols) {
            sb.append("• ").append(s).append("\n");
        }
        sb.append("\n請說出想查的幣種，例如：「BTC 現在多少？」");
        return sb.toString().trim();
    }
}
