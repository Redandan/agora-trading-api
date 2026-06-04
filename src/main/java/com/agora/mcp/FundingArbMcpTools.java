package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtFundingArb;
import com.agora.repository.trading.BtFundingArbRepository;
import com.agora.service.trading.FundingArbService;
import com.agora.service.trading.OkxTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import com.agora.mcp.util.McpParamValidator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Funding Rate Arbitrage MCP 工具集。
 *
 * <p>控制類:{@code createFundingArb} / {@code closeFundingArb}(都 DEV)
 * <br>觀測類:{@code listFundingArb} / {@code fundingArbStats}(OPS)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingArbMcpTools {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final FundingArbService service;
    private final BtFundingArbRepository repo;
    private final OkxTradingService okxTradingService;

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "手動建立 Funding Arb delta-neutral position(SPOT 多 + perp 空配對)。" +
            "系統會檢查當前 funding rate ≥ minFundingRate、無既有 active、餘額充足,符合才真正開倉。" +
            "dry-run 模式下只 log 不下單。" +
            "params: symbol(僅 BTCUSDT), notionalUsdt(建議 ≥ 800 避免 perp 最小合約不足), " +
            "minFundingRate(小數,null 用 config 預設 0.0002=0.02%/8h), " +
            "targetProfitUsdt(累積 funding 達此值即出場,null 用 notional×1%)")
    public String createFundingArb(String symbol, BigDecimal notionalUsdt,
                                    BigDecimal minFundingRate, BigDecimal targetProfitUsdt) {
        { String _e = McpParamValidator.requireNonBlank(symbol, "symbol"); if (_e != null) return _e; }
        if (notionalUsdt == null || notionalUsdt.compareTo(BigDecimal.valueOf(10)) < 0) {
            return "❌ notionalUsdt 需 ≥ 10";
        }
        FundingArbService.OpenResult res = service.tryOpen(
                symbol.toUpperCase(), notionalUsdt, "MCP_CREATE");
        if (res.ok()) {
            return String.format("%s FundingArb position_id=%d (%s)",
                    res.isDryRun() ? "🧪 DRY_RUN" : "✅ OPEN",
                    res.positionId(), res.message());
        }
        return "❌ " + res.message();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "強制平倉指定 Funding Arb position。支援應急出場,不受 funding rate 條件限制。" +
            "reason 必填供 audit。params: positionId, reason")
    public String closeFundingArb(Long positionId, String reason) {
        { String _e = McpParamValidator.requireNonNull(positionId, "positionId"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(reason, "reason"); if (_e != null) return _e; }
        FundingArbService.CloseResult res = service.close(positionId, "MANUAL: " + reason);
        if (res.ok()) {
            return String.format("✅ CLOSED position_id=%d  realized=%s\nreason: %s",
                    positionId,
                    res.realizedPnl() != null ? res.realizedPnl() : "-",
                    res.message());
        }
        return "❌ " + res.message();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "列出所有 active funding arb positions(OPEN/OPENING/CLOSING)含當前 funding rate、" +
            "累積收益、持倉時長。")
    public String listFundingArb() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BtFundingArb> active = repo.findByStatusIn(List.of("OPEN", "OPENING", "CLOSING"));
        if (active.isEmpty()) {
            return "ℹ️ 當前無 active Funding Arb position";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Active Funding Arb (").append(active.size()).append(") ===\n\n");
        for (BtFundingArb p : active) {
            double funding = 0;
            try {
                funding = okxTradingService.getCurrentFundingRate(p.getSymbol());
            } catch (Exception ignored) {}
            long heldMin = p.getOpenedAt() != null
                    ? java.time.Duration.between(p.getOpenedAt(), now).toMinutes() : 0;
            sb.append(String.format(
                    "[%d] %s  status=%s  notional=$%s\n" +
                    "  spot qty=%s @ $%s  |  perp qty=%s @ $%s\n" +
                    "  funding now=%.6f(%.4f%%/8h = 年化 ~%.1f%%)\n" +
                    "  acc_funding=$%s  periods=%d  held=%dh%dm\n" +
                    "  opened=%s UTC  posId=%d\n---\n",
                    p.getId(), p.getSymbol(), p.getStatus(), p.getNotionalUsdt(),
                    p.getSpotQty(),         p.getSpotEntryPrice(),
                    p.getPerpContractQty(), p.getPerpEntryPrice(),
                    funding, funding * 100, funding * 3 * 365 * 100,
                    p.getAccumulatedFunding(), p.getFundingPeriods(),
                    heldMin / 60, heldMin % 60,
                    p.getOpenedAt() != null ? p.getOpenedAt().format(FMT) : "-",
                    p.getId()));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.READ_TRADING})
    @Tool(description = "Funding Arb 歷史績效:近 N 天已平倉 position 的平均持倉時長、總 funding 收益、" +
            "平均 realized PnL、成功率。params: days(1-90,預設 30)")
    public String fundingArbStats(Integer days) {
        int d = (days == null || days <= 0 || days > 90) ? 30 : days;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<BtFundingArb> closed = repo.findByStatusAndClosedAtAfterOrderByClosedAtDesc("CLOSED", since);
        if (closed.isEmpty()) {
            return "ℹ️ 近 " + d + " 天無 CLOSED funding arb position";
        }

        BigDecimal totalFunding = BigDecimal.ZERO;
        BigDecimal totalRealized = BigDecimal.ZERO;
        long totalMinutes = 0;
        int winCount = 0;
        for (BtFundingArb p : closed) {
            totalFunding  = totalFunding.add(p.getAccumulatedFunding() == null ? BigDecimal.ZERO : p.getAccumulatedFunding());
            if (p.getRealizedPnl() != null) {
                totalRealized = totalRealized.add(p.getRealizedPnl());
                if (p.getRealizedPnl().compareTo(BigDecimal.ZERO) > 0) winCount++;
            }
            if (p.getOpenedAt() != null && p.getClosedAt() != null) {
                totalMinutes += java.time.Duration.between(p.getOpenedAt(), p.getClosedAt()).toMinutes();
            }
        }
        int n = closed.size();
        double avgHeldHr = (totalMinutes / (double) n) / 60.0;
        double winRate = 100.0 * winCount / n;

        return String.format(
                "=== Funding Arb Stats (近 %d 天, %d 筆 CLOSED)===\n" +
                "總 funding 收益: $%s\n" +
                "總 realized PnL: $%s(含手續費 + delta drift)\n" +
                "平均持倉: %.1f 小時\n" +
                "成功筆數(PnL > 0): %d / %d(%.1f%%)\n" +
                "日均收益估計: $%.4f",
                d, n,
                totalFunding.setScale(4, java.math.RoundingMode.HALF_UP),
                totalRealized.setScale(4, java.math.RoundingMode.HALF_UP),
                avgHeldHr, winCount, n, winRate,
                totalRealized.doubleValue() / Math.max(d, 1));
    }
}
