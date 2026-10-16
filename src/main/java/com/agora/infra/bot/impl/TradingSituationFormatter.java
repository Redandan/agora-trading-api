package com.agora.infra.bot.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Human-readable projection of the existing read-only reports. Missing data is never zero. */
public final class TradingSituationFormatter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FIELD = Pattern.compile("([A-Za-z][A-Za-z0-9]*)=([^\\s]+)");
    private static final String DRA = "BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1";
    private static final String TV = "TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2@v2";
    private static final String DONCHIAN = "BTC_DONCHIAN_20D_10D_V1@v1";
    private TradingSituationFormatter() { }

    public static String format(String positions, String safety, String catalog, String grid) {
        positions = Objects.toString(positions, "");
        catalog = Objects.toString(catalog, "");
        String inventory = positions.split("\\n\\n", 2)[0];
        Map<String, String> inventoryFields = fields(inventory);
        List<Map<String, String>> rows = inventory.lines().filter(s -> s.startsWith("- id="))
                .map(TradingSituationFormatter::fields).toList();
        boolean inventoryComplete = number(inventoryFields.get("count")) != null
                && number(inventoryFields.get("count")).compareTo(BigDecimal.valueOf(rows.size())) == 0;
        JsonNode observations = jsonAfter(catalog, "STRATEGY_RUNTIME_OBSERVATIONS\n");
        StringBuilder out = new StringBuilder("【BTC 策略運行總覽】\n持倉資料：")
                .append(time(inventoryFields.get("asOf"))).append("（台灣時間）")
                .append("\n").append(safety(safety));
        appendLane(out, "509 日線買入", TV, "TV509_OWNED", "TV509", rows,
                inventoryComplete, positions, catalog, observations);
        appendLane(out, "DRA 日線反轉", DRA, "DRA_V1_OWNED", "DRA_V1", rows,
                inventoryComplete, positions, catalog, observations);
        appendGrid(out, jsonAfter(grid, ""));
        JsonNode donchian = lane(observations, DONCHIAN);
        out.append("\n\nDonchian 突破｜").append(mode(catalog, DONCHIAN));
        if ("SHADOW".equals(rawMode(catalog, DONCHIAN))) {
            out.append("\n只做模擬觀察，不會實際下單");
        }
        appendFreshness(out, donchian);
        if (freshDecision(donchian) && "NO_SIGNAL_STATE_ADVANCED".equals(donchian.path("lastDecisionReason").asText())) {
            out.append("\n最新評估：本輪沒有新模擬訊號");
        } else {
            out.append("\n最新評估：未確認；模擬績效另行檢視");
        }
        List<Map<String, String>> legacy = rows.stream().filter(r -> Set.of("ADOPTED_FROM_OCO", "NATIVE_BTC_BASE")
                .contains(r.getOrDefault("managementState", ""))).toList();
        if (!legacy.isEmpty()) {
            out.append("\n\n舊 BTC 持倉｜非現行策略績效");
            appendPosition(out, legacy, inventoryComplete);
            out.append("\n自動出場：未設定");
        }
        long other = rows.stream().filter(r -> !Set.of("TV509_OWNED", "DRA_V1_OWNED", "ADOPTED_FROM_OCO", "NATIVE_BTC_BASE")
                .contains(r.getOrDefault("managementState", ""))).count();
        if (other > 0) out.append("\n\n另有 ").append(other).append(" 筆其他或待核對持倉，請查看明細");
        return out.append("\n\n口徑：現貨浮盈虧未含完整買賣費用；網格為交易所估算，仍會變動。")
                .append("\n各項不合併為全帳戶淨利；模擬結果不計入實盤收益。").toString();
    }

    private static void appendLane(StringBuilder out, String name, String contract, String state, String owner,
                                   List<Map<String, String>> all, boolean complete, String positions,
                                   String catalog, JsonNode observations) {
        JsonNode lane = lane(observations, contract);
        out.append("\n\n").append(name).append("｜").append(mode(catalog, contract));
        String armed = fields(catalog).get(DRA.equals(contract) ? "draExecutionArmed" : "owner509ExecutionArmed");
        out.append("\n新買入開關：").append("true".equals(armed) ? "已啟用" : "false".equals(armed) ? "未啟用" : "待確認");
        List<Map<String, String>> owned = all.stream().filter(r -> state.equals(r.get("managementState"))).toList();
        appendPosition(out, owned, complete);
        if ("AVAILABLE".equals(lane.path("observationStatus").asText())) {
            out.append("\n記錄投入／額度：").append(money(number(lane.path("recordedOpenCostUsdt")), 2))
                    .append(" / ").append(money(number(lane.path("configuredExposureCapUsdt")), 2)).append(" USDT");
            BigDecimal age = number(lane.path("oldestOpenRecordAgeHours"));
            if (age != null && !owned.isEmpty()) out.append("｜最久約 ").append(money(age.divide(BigDecimal.valueOf(24), 1, RoundingMode.HALF_UP), 1)).append(" 天");
        }
        appendRealized(out, positions, owner);
        appendFreshness(out, lane);
        if (DRA.equals(contract) && freshDecision(lane)) {
            String stage = lane.path("entryDecision").path("stage").asText();
            if ("WAITING_FOR_UTC_DAILY_CLOSE".equals(stage)) out.append("\n最新評估：等待下一次日線收盤確認");
            else if (lane.path("lastLiveEntryEvaluation").path("candidate").isBoolean()
                    && !lane.path("lastLiveEntryEvaluation").path("candidate").asBoolean()) out.append("\n最新評估：本輪沒有新買入候選");
        }
        if (!owned.isEmpty()) out.append(DRA.equals(contract)
                ? "\n出場規則：每小時評估，預估淨報酬達 +5% 時嘗試賣出"
                : "\n出場規則：每日收盤評估，預估淨報酬達 +5% 時嘗試賣出")
                .append("；無自動止損或持倉期限");
    }

    private static void appendPosition(StringBuilder out, List<Map<String, String>> rows, boolean complete) {
        if (!complete) { out.append("\n持倉與浮盈虧：資料未完整取得"); return; }
        out.append("\n持倉：").append(rows.size()).append(" 筆");
        if (rows.isEmpty()) return;
        BigDecimal pnl = sum(rows, "grossUnrealizedPnl"), cost = sum(rows, "entryCost");
        if (rows.stream().anyMatch(r -> !"false".equals(r.get("executionUnresolved")))) pnl = null;
        out.append("｜浮盈虧：").append(signed(pnl)).append(" USDT");
        if (pnl != null && cost != null && cost.signum() > 0) {
            out.append("（").append(signed(pnl.multiply(BigDecimal.valueOf(100)).divide(cost, 4, RoundingMode.HALF_UP), 2)).append("%）");
        }
    }

    private static void appendRealized(StringBuilder out, String report, String owner) {
        int start = report.indexOf("\ncumulative:\n"), end = report.indexOf("\ncumulativeSummary ");
        if (start < 0 || end <= start) { out.append("\n已實現損益：資料不足"); return; }
        Optional<Map<String, String>> record = report.substring(start, end).lines().filter(s -> s.startsWith("- owner="))
                .map(TradingSituationFormatter::fields).filter(r -> owner.equals(r.get("owner"))).findFirst();
        if (record.isEmpty()) { out.append("\n已實現損益：尚無平倉紀錄"); return; }
        Map<String, String> r = record.get();
        BigDecimal closed = number(r.get("closedLots")), exact = number(r.get("exactNetLots"));
        boolean allExact = closed != null && closed.signum() > 0 && closed.equals(exact) && number(r.get("exactNetRealizedPnl")) != null;
        out.append(allExact ? "\n已核對淨利：" : "\n已記錄平倉損益：")
                .append(signed(number(r.get(allExact ? "exactNetRealizedPnl" : "recordedRealizedPnl")))).append(" USDT");
        if (!allExact) out.append("（費用或成交憑證未完整核對）");
    }

    private static void appendGrid(StringBuilder out, JsonNode grid) {
        out.append("\n\nOKX 現貨網格｜實盤");
        if (!grid.path("active").isArray() || !grid.path("activeCount").isIntegralNumber()
                || grid.path("activeCount").asInt() != grid.path("active").size()) {
            out.append("\n資料未取得，無法判定運行狀態或損益"); return;
        }
        if (grid.path("active").isEmpty()) { out.append("\n目前沒有運行中的網格"); return; }
        int shown = 0;
        for (JsonNode bot : grid.path("active")) {
            if (shown++ == 3) { out.append("\n另有網格未展開，請查看明細"); break; }
            JsonNode range = MissingNode.getInstance();
            for (JsonNode r : grid.path("rangeObservations")) {
                if (bot.path("algoId").asText().equals(r.path("algoId").asText())) { range = r; break; }
            }
            String state = "running".equals(bot.path("state").asText()) ? "運行中" : "狀態待確認";
            String rangeStatus = switch (range.path("rangeStatus").asText()) {
                case "WITHIN_RANGE" -> "價格在區間內";
                case "BELOW_RANGE" -> "價格低於下界，需檢視";
                case "ABOVE_RANGE" -> "價格高於上界，需檢視";
                default -> "區間狀態待確認";
            };
            out.append("\n").append(bot.path("instId").asText("交易對待確認")).append("｜").append(state).append("，").append(rangeStatus)
                    .append("\n投入：").append(money(number(bot.path("investment")), 2)).append(" USDT")
                    .append("｜配對次數：").append(plain(number(bot.path("arbitrageNum"))))
                    .append("\n區間：").append(money(number(bot.path("minPx")), 0)).append("–")
                    .append(money(number(bot.path("maxPx")), 0)).append("｜").append(plain(number(bot.path("gridNum")))).append(" 格")
                    .append("\n總損益：").append(signed(number(bot.path("totalPnl")))).append(" USDT")
                    .append("\n配對收益：").append(signed(number(bot.path("gridProfit"))))
                    .append("｜浮動損益：").append(signed(number(bot.path("floatProfit")))).append(" USDT")
                    .append("\n網格編號：").append(bot.path("algoId").asText("待確認"));
        }
    }

    private static void appendFreshness(StringBuilder out, JsonNode lane) {
        String status = lane.path("marketDataStatus").asText();
        out.append("\n行情資料：").append("CURRENT".equals(status) ? "已更新" : "STALE".equals(status) ? "已過期，勿當作即時判斷" : "未確認")
                .append("｜收盤 ").append(time(lane.path("latestClosedBarUtc").asText()));
        if (!freshDecision(lane)) out.append("\n決策證據：不足或未對應最新收盤，不能判定本輪動作");
    }
    private static boolean freshDecision(JsonNode lane) {
        return "AVAILABLE".equals(lane.path("observationStatus").asText()) && "CURRENT".equals(lane.path("marketDataStatus").asText())
                && "OBSERVED".equals(lane.path("decisionEvidenceStatus").asText()) && lane.path("decisionMatchesLatestClosedBar").asBoolean(false);
    }
    private static JsonNode lane(JsonNode observations, String contract) {
        for (JsonNode lane : observations.path("strategies")) if (contract.equals(lane.path("contract").asText())) return lane;
        return MissingNode.getInstance();
    }
    private static String rawMode(String catalog, String contract) {
        return catalog.lines().filter(s -> s.startsWith("- " + contract + " ")).map(TradingSituationFormatter::fields)
                .map(r -> r.getOrDefault("mode", "")).findFirst().orElse("");
    }
    private static String mode(String catalog, String contract) {
        return switch (rawMode(catalog, contract)) {
            case "LIVE" -> "實盤"; case "SHADOW" -> "模擬觀察"; case "PAPER" -> "模擬交易";
            case "OFF" -> "已停用"; case "ARCHIVED" -> "已封存"; default -> "模式待確認";
        };
    }
    private static String safety(String raw) {
        Map<String, String> f = fields(Objects.toString(raw, ""));
        if ("OK".equals(f.get("status")) && ("0".equals(f.get("issues")) || "0".equals(f.get("openPositions"))))
            return "執行檢查：未發現待處理異常（不代表沒有投資風險）";
        return "REVIEW".equals(f.get("status")) ? "執行檢查：有待處理異常，請查看訂單／持倉明細" : "執行檢查：資料未取得";
    }
    private static Map<String, String> fields(String text) {
        Map<String, String> result = new HashMap<>(); var matcher = FIELD.matcher(text);
        while (matcher.find()) result.put(matcher.group(1), matcher.group(2)); return result;
    }
    private static JsonNode jsonAfter(String raw, String marker) {
        if (raw == null || !raw.contains(marker)) return MissingNode.getInstance();
        try { JsonNode value = JSON.readTree(raw.substring(raw.indexOf(marker) + marker.length())); return value == null ? MissingNode.getInstance() : value; }
        catch (Exception ignored) { return MissingNode.getInstance(); }
    }
    private static BigDecimal sum(List<Map<String, String>> rows, String key) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, String> row : rows) { BigDecimal value = number(row.get(key)); if (value == null) return null; total = total.add(value); }
        return total;
    }
    private static BigDecimal number(JsonNode n) { return number(n.asText()); }
    private static BigDecimal number(String n) { try { return new BigDecimal(n); } catch (Exception ignored) { return null; } }
    private static String plain(BigDecimal n) { return n == null ? "待確認" : n.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal n, int scale) { return n == null ? "待確認" : String.format(Locale.ROOT, "%,." + scale + "f", n); }
    private static String signed(BigDecimal n) { return signed(n, 4); }
    private static String signed(BigDecimal n, int scale) { return n == null ? "待確認" : (n.signum() > 0 ? "+" : "") + money(n, scale); }
    private static String time(String utc) {
        if (utc == null || utc.isBlank()) return "待確認";
        try {
            Instant instant = utc.endsWith("Z") ? Instant.parse(utc) : LocalDateTime.parse(utc).toInstant(ZoneOffset.UTC);
            return DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.of("Asia/Taipei")).format(instant);
        } catch (Exception ignored) { return "待確認"; }
    }
}
