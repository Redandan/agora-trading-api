package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.OkxEarnService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import com.agora.mcp.util.McpParamValidator;
import java.math.BigDecimal;
import java.util.List;

/**
 * OKX Simple Earn MCP 工具集。
 *
 * <p>觀測類:{@code getEarnBalance} / {@code getEarnRates}（OPS）
 * <br>控制類:{@code subscribeEarn} / {@code redeemEarn}（DEV）
 *
 * <p>OKX Simple Earn 靈活存款：每日計息，隨時贖回。
 * USDT 年化約 5-8%，BTC 約 1-3%（浮動）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarnMcpTools {

    private final OkxEarnService earnService;

    // ─────────────────────────────────────────────
    //  觀測類（OPS）
    // ─────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "查詢 OKX Simple Earn（靈活存款）餘額。" +
            "顯示本金、累計利息、當前日利率與換算年化 APY。" +
            "currency 傳 null 或空字串查全部幣種；傳 'USDT' 只查 USDT。")
    public String getEarnBalance(String currency) {
        try {
            List<OkxEarnService.EarnBalance> balances = earnService.getBalance(currency);
            if (balances.isEmpty()) {
                String hint = (currency != null && !currency.isBlank())
                        ? currency.toUpperCase() + " 目前無存款。可用 subscribeEarn 申購。"
                        : "目前無任何 Simple Earn 存款。";
                return "📭 " + hint;
            }

            StringBuilder sb = new StringBuilder("💰 OKX Simple Earn 餘額\n\n");
            for (OkxEarnService.EarnBalance b : balances) {
                sb.append(String.format("▸ %s\n", b.ccy()));
                sb.append(String.format("  本金:           %s\n", b.amt().toPlainString()));
                sb.append(String.format("  累計利息:       %s\n", b.earnings().toPlainString()));
                sb.append(String.format("  最低出借年化:   %s%%（市場實際利率見 getEarnRates）\n", b.apyAnnualized().toPlainString()));
                sb.append(String.format("  已出借:         %s\n", b.loanAmt().toPlainString()));
                sb.append(String.format("  待匹配:         %s\n\n", b.pendingAmt().toPlainString()));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("[EarnMcpTools] getEarnBalance failed", e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "查詢 OKX Simple Earn 指定幣種的當前出借利率摘要，含平均/前期/預估 APY。" +
            "currency 為必填（如 'USDT'、'BTC'）。" +
            "可藉此評估當前存款划算程度，再決定是否 subscribeEarn。")
    public String getEarnRates(String currency) {
        { String _e = McpParamValidator.requireNonBlank(currency, "currency"); if (_e != null) return _e; }
        try {
            OkxEarnService.EarnRateSummary r = earnService.getRateSummary(currency);
            return String.format("""
                    📈 OKX Simple Earn 利率摘要 — %s

                    ▸ 近期平均 APY: %s%%（年利率 %s）
                    ▸ 前一期 APY:   %s%%（年利率 %s）
                    ▸ 下期預估 APY: %s%%（年利率 %s）

                    💡 USDT 正常區間 5-8%%，BTC 1-3%%（市場需求浮動）
                    """,
                    r.ccy(),
                    r.avgApyPct(), r.avgRate().toPlainString(),
                    r.preApyPct(), r.preRate().toPlainString(),
                    r.estApyPct(), r.estRate().toPlainString());
        } catch (Exception e) {
            log.error("[EarnMcpTools] getEarnRates failed", e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─────────────────────────────────────────────
    //  控制類（DEV）
    // ─────────────────────────────────────────────

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "申購 OKX Simple Earn（把資金存入靈活存款池，開始每日計息）。" +
            "currency 為幣種（如 'USDT'），amount 為申購金額（USDT 最小 1，BTC 最小 0.0001）。" +
            "資金來源為 OKX 現貨帳戶；申購後隨時可用 redeemEarn 贖回。" +
            "每日利息自動複利，無鎖倉期。")
    public String subscribeEarn(String currency, BigDecimal amount) {
        { String _e = McpParamValidator.requireNonBlank(currency, "currency"); if (_e != null) return _e; }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return "❌ amount 需 > 0";
        try {
            earnService.subscribe(currency, amount);
            return String.format("✅ 已申購 %s %s 至 OKX Simple Earn。\n" +
                    "資金已從現貨帳戶轉入，將於下個計息週期開始產息。\n" +
                    "可用 getEarnBalance 確認餘額。",
                    amount.toPlainString(), currency.toUpperCase());
        } catch (Exception e) {
            log.error("[EarnMcpTools] subscribeEarn failed", e);
            return "❌ 申購失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "贖回 OKX Simple Earn（把資金從存款池取回到現貨帳戶）。" +
            "currency 為幣種（如 'USDT'），amount 為贖回金額；傳 null 或 'ALL' 贖回全部。" +
            "贖回通常即時到帳（T+0），利息持續到贖回時為止。" +
            "注意：amount 為 String 以支援 'ALL' 關鍵字。")
    public String redeemEarn(String currency, String amount) {
        { String _e = McpParamValidator.requireNonBlank(currency, "currency"); if (_e != null) return _e; }
        try {
            BigDecimal amtBd = null;
            if (amount != null && !amount.isBlank() && !"ALL".equalsIgnoreCase(amount.trim())) {
                try {
                    amtBd = new BigDecimal(amount.trim());
                    if (amtBd.compareTo(BigDecimal.ZERO) <= 0) return "❌ amount 需 > 0（或傳 null/ALL 贖回全部）";
                } catch (NumberFormatException e) {
                    return "❌ amount 格式不正確（數字或 'ALL'）: " + amount;
                }
            }
            String displayAmt = (amtBd == null) ? "全部" : amtBd.toPlainString();
            earnService.redeem(currency, amtBd);
            return String.format("✅ 已贖回 %s %s 從 OKX Simple Earn 至現貨帳戶。\n" +
                    "可用 getBalance 確認現貨餘額。",
                    displayAmt, currency.toUpperCase());
        } catch (Exception e) {
            log.error("[EarnMcpTools] redeemEarn failed", e);
            return "❌ 贖回失敗: " + e.getMessage();
        }
    }
}
