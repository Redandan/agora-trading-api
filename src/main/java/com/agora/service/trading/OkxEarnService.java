package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * OKX Simple Earn（靈活儲蓄）API 封裝。
 *
 * <p>OKX Simple Earn 讓你把 USDT/BTC 等放入靈活存款池，每日計息，隨時贖回。
 * 年化收益率約 USDT 5-8%，BTC 1-3%（浮動，依市場需求）。
 *
 * <p>使用相同的 OKX API Key（需要有 Finance 權限）。
 *
 * <h3>主要 API</h3>
 * <pre>
 *   GET  /api/v5/finance/savings/balance              — 查詢當前存款餘額與累計利息
 *   GET  /api/v5/finance/savings/lending-rate-summary — 查詢當前 APY（年化利率）
 *   POST /api/v5/finance/savings/purchase-redempt     — 申購（side=purchase）/ 贖回（side=redempt）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxEarnService {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    // OKX Simple Earn 最低出借利率（年利率 decimal，與 lending-rate-summary 同單位）
    // 0.01 = 1% 年化，設低確保資金快速出借，實際市場利率通常更高（USDT 熊市 2-5%，牛市 5-8%）
    private static final String MIN_LENDING_RATE = "0.01";

    /** 交易帳戶 USDT 緩衝目標：不足 threshold 時自動補至 targetUsdt */
    @org.springframework.beans.factory.annotation.Value("${okx.earn-topup.target-usdt:350}")
    private BigDecimal topupTargetUsdt;

    /** 低於此值觸發補充（設略低於 target 避免頻繁小額轉帳）*/
    @org.springframework.beans.factory.annotation.Value("${okx.earn-topup.threshold-usdt:300}")
    private BigDecimal topupThresholdUsdt;

    /** Hard opt-in for any Earn redemption/transfer path used by topUpTradingBuffer. */
    @org.springframework.beans.factory.annotation.Value("${okx.earn-topup.enabled:false}")
    private boolean topupEnabled;

    private final OkxTradingProperties props;
    private final ObjectMapper objectMapper;

    private OkHttpClient httpClient;

    @PostConstruct
    void init() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────

    public BigDecimal getTopupTargetUsdt() {
        return topupTargetUsdt;
    }

    public BigDecimal getTopupThresholdUsdt() {
        return topupThresholdUsdt;
    }

    /**
     * 查詢指定幣種（或全部）的 Simple Earn 存款餘額。
     *
     * @param ccy 幣種（如 "USDT"）；null 或空字串表示查全部
     * @return 餘額列表，每項含：幣種、本金、累計利息、當前日利率
     */
    public List<EarnBalance> getBalance(String ccy) {
        String path = "/api/v5/finance/savings/balance";
        if (ccy != null && !ccy.isBlank()) {
            path += "?ccy=" + ccy.toUpperCase();
        }
        JsonNode root = get(path);
        List<EarnBalance> result = new ArrayList<>();
        JsonNode data = root.path("data");
        for (JsonNode item : data) {
            result.add(new EarnBalance(
                    item.path("ccy").asText(),
                    toBD(item.path("amt").asText()),
                    toBD(item.path("earnings").asText()),
                    toBD(item.path("rate").asText()),
                    toBD(item.path("loanAmt").asText()),
                    toBD(item.path("pendingAmt").asText())
            ));
        }
        return result;
    }

    /**
     * 查詢幣種的當前出借利率摘要（換算年化 APY）。
     *
     * @param ccy 幣種（如 "USDT"）
     * @return 利率摘要，含 avgRate / preRate / estRate（均為日利率）及換算的年化 APY
     */
    public EarnRateSummary getRateSummary(String ccy) {
        String path = "/api/v5/finance/savings/lending-rate-summary?ccy=" + ccy.toUpperCase();
        JsonNode root = get(path);
        JsonNode item = root.path("data").path(0);
        if (item.isMissingNode()) {
            throw new RuntimeException("No rate data for " + ccy + "（該幣種可能不支援 Simple Earn）");
        }
        return new EarnRateSummary(
                item.path("ccy").asText(),
                toBD(item.path("avgRate").asText()),
                toBD(item.path("preRate").asText()),
                toBD(item.path("estRate").asText())
        );
    }

    /**
     * 申購 Simple Earn（把資金放入靈活存款）。
     *
     * @param ccy    幣種（如 "USDT"）
     * @param amount 金額（USDT 最小 1，BTC 最小 0.0001）
     */
    public void subscribe(String ccy, BigDecimal amount) {
        // Simple Earn 需要資金在 Funding Account（帳戶類型 6）。
        // 先查資金帳戶餘額，不足時自動從交易帳戶（18）轉入。
        BigDecimal fundingBal = getFundingBalance(ccy);
        if (fundingBal.compareTo(amount) < 0) {
            BigDecimal deficit = amount.subtract(fundingBal);
            log.info("[OKXEarn] Funding balance {} {} < required {}; transferring {} from trading account",
                    fundingBal.toPlainString(), ccy, amount.toPlainString(), deficit.toPlainString());
            transferToFunding(ccy, deficit);
        }

        String body = objectMapper.createObjectNode()
                .put("ccy",  ccy.toUpperCase())
                .put("amt",  amount.toPlainString())
                .put("side", "purchase")
                .put("rate", MIN_LENDING_RATE)
                .toString();
        JsonNode resp = post("/api/v5/finance/savings/purchase-redempt", body);
        checkOkxResponse(resp, "subscribe " + ccy);
        log.info("[OKXEarn] Subscribed {} {}", amount.toPlainString(), ccy);
    }

    /**
     * 查詢交易帳戶（Trading/Unified Account, type=18）中指定幣種的可用餘額。
     * 用於 topUpTradingBuffer 鎖內 double-check，避免多 thread 重複補款。
     *
     * @param ccy 幣種（如 "USDT"）
     * @return 可用餘額；查詢失敗或無此幣種則回傳 ZERO
     */
    private BigDecimal queryTradingBalance(String ccy) {
        try {
            String path = "/api/v5/account/balance?ccy=" + ccy.toUpperCase();
            JsonNode root = get(path);
            JsonNode details = root.path("data").path(0).path("details");
            for (JsonNode item : details) {
                if (ccy.equalsIgnoreCase(item.path("ccy").asText())) {
                    if (item.hasNonNull("availBal")) {
                        return toBD(item.path("availBal").asText());
                    }
                    return toBD(item.path("availEq").asText());
                }
            }
        } catch (Exception e) {
            log.warn("[OKXEarn] queryTradingBalance failed (using caller value): {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /**
     * 查詢資金帳戶（Funding Account）中指定幣種的可用餘額。
     *
     * @param ccy 幣種（如 "USDT"）
     * @return 可用餘額；若無此幣種則回傳 ZERO
     */
    public BigDecimal getFundingBalance(String ccy) {
        String path = "/api/v5/asset/balances?ccy=" + ccy.toUpperCase();
        JsonNode root = get(path);
        JsonNode data = root.path("data");
        for (JsonNode item : data) {
            if (ccy.equalsIgnoreCase(item.path("ccy").asText())) {
                return toBD(item.path("availBal").asText());
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 從交易帳戶（Trading Account, type=18）轉帳到資金帳戶（Funding Account, type=6）。
     *
     * @param ccy 幣種
     * @param amount 金額
     */
    private void transferToFunding(String ccy, BigDecimal amount) {
        String body = objectMapper.createObjectNode()
                .put("ccy",  ccy.toUpperCase())
                .put("amt",  amount.toPlainString())
                .put("from", "18")   // Unified Trading Account
                .put("to",   "6")    // Funding Account
                .put("type", "0")    // master account internal transfer
                .toString();
        JsonNode resp = post("/api/v5/asset/transfer", body);
        checkOkxResponse(resp, "transfer to funding " + ccy);
        log.info("[OKXEarn] Transferred {} {} from trading to funding account", amount.toPlainString(), ccy);
    }

    /**
     * 主動維護 USDT 交易帳戶緩衝。
     *
     * <p>由 {@code OcoPositionPollerScheduler}（每 10 分鐘）呼叫，確保交易帳戶隨時有充裕餘額：
     * <ul>
     *   <li>currentBalance ≥ threshold → 不需動作，直接回傳 false</li>
     *   <li>currentBalance &lt; threshold → 從 Earn 贖回，補至 target</li>
     * </ul>
     *
     * <p>也作為 {@code LiveSignalEvaluator.autoTrade()} 的 reactive 安全網（正常情況不觸發）。
     *
     * @param currentTradingBalance 當前交易帳戶 USDT 餘額（由 caller 傳入，避免重複 API 查詢）
     * @return true = 執行了補充；false = 餘額充足或 Earn 餘額不足
     */
    public synchronized boolean topUpTradingBuffer(BigDecimal currentTradingBalance) {
        if (!topupEnabled) {
            log.debug("[OKXEarn] TopUp skipped: okx.earn-topup.enabled=false");
            return false;
        }

        // 鎖內重查真實餘額：避免多個 caller 帶著舊讀值同時排隊，第一個補完後第二個再重複補
        BigDecimal actualBalance = queryTradingBalance("USDT");
        if (actualBalance.compareTo(BigDecimal.ZERO) > 0) {
            currentTradingBalance = actualBalance;  // 用新值覆蓋 caller 傳入的舊值
        }
        if (currentTradingBalance.compareTo(topupThresholdUsdt) >= 0) return false;

        BigDecimal toRedeem = topupTargetUsdt.subtract(currentTradingBalance);
        if (toRedeem.compareTo(BigDecimal.ZERO) <= 0) return false;

        // 查 Earn 餘額
        List<EarnBalance> balances = getBalance("USDT");
        if (balances.isEmpty()) {
            log.warn("[OKXEarn] TopUp: no USDT in Earn, skipping");
            return false;
        }
        BigDecimal earnAmt = balances.get(0).amt();
        if (earnAmt.compareTo(BigDecimal.ONE) < 0) {
            log.warn("[OKXEarn] TopUp: Earn balance {} too small", earnAmt);
            return false;
        }

        // 不超過 Earn 總額
        if (toRedeem.compareTo(earnAmt) > 0) toRedeem = earnAmt;

        log.info("[OKXEarn] TopUp: trading={} < threshold={}, redeeming {} → target={}",
                currentTradingBalance.toPlainString(), topupThresholdUsdt,
                toRedeem.toPlainString(), topupTargetUsdt);
        redeem("USDT", toRedeem);

        // OKX Simple Earn 贖回後資金需約 2-5 秒才出現在 Funding Account 可用餘額。
        // 最多等 10 秒，確認有餘額後再轉帳，避免 58350 Insufficient balance。
        BigDecimal availInFunding = waitForFundingBalance("USDT", toRedeem, 10);
        if (availInFunding.compareTo(BigDecimal.ONE) < 0) {
            log.warn("[OKXEarn] TopUp: funding balance still 0 after wait, redeem may be delayed");
            return false;
        }
        // 只轉入實際可用金額（避免浮點差異）
        BigDecimal actualTransfer = availInFunding.min(toRedeem);
        transferFromFunding("USDT", actualTransfer);
        log.info("[OKXEarn] TopUp complete: transferred {} USDT to trading account", actualTransfer);
        return true;
    }

    /**
     * 等待 Funding Account 出現可用餘額（贖回後最多 waitSecs 秒，每 2 秒查一次）。
     *
     * @return 實際可用餘額（可能低於 expected；超時則回傳最後查到的值）
     */
    private BigDecimal waitForFundingBalance(String ccy, BigDecimal expected, int waitSecs) {
        int elapsed = 0;
        while (elapsed < waitSecs) {
            BigDecimal bal = getFundingBalance(ccy);
            if (bal.compareTo(BigDecimal.ONE) >= 0) {
                log.debug("[OKXEarn] Funding balance {} available after {}s", bal, elapsed);
                return bal;
            }
            try { java.util.concurrent.TimeUnit.SECONDS.sleep(2); } catch (InterruptedException ignored) {}
            elapsed += 2;
        }
        return getFundingBalance(ccy);  // 最後再查一次
    }

    /**
     * 從資金帳戶（Funding Account, type=6）轉帳到交易帳戶（Trading Account, type=18）。
     */
    public void transferFromFunding(String ccy, BigDecimal amount) {
        String body = objectMapper.createObjectNode()
                .put("ccy",  ccy.toUpperCase())
                .put("amt",  amount.toPlainString())
                .put("from", "6")    // Funding Account
                .put("to",   "18")   // Unified Trading Account
                .put("type", "0")
                .toString();
        JsonNode resp = post("/api/v5/asset/transfer", body);
        checkOkxResponse(resp, "transfer from funding " + ccy);
        log.info("[OKXEarn] Transferred {} {} from funding to trading account", amount.toPlainString(), ccy);
    }

    /**
     * 贖回 Simple Earn（把資金從存款池取出到現貨帳戶）。
     *
     * @param ccy    幣種（如 "USDT"）
     * @param amount 金額；傳 null 或 "ALL" 則贖回全部
     */
    public void redeem(String ccy, BigDecimal amount) {
        String amtStr;
        if (amount == null) {
            // 查詢餘額後全額贖回
            List<EarnBalance> balances = getBalance(ccy);
            if (balances.isEmpty()) throw new RuntimeException("無 " + ccy + " 的 Simple Earn 存款");
            amtStr = balances.get(0).amt().toPlainString();
        } else {
            amtStr = amount.toPlainString();
        }
        String body = objectMapper.createObjectNode()
                .put("ccy",  ccy.toUpperCase())
                .put("amt",  amtStr)
                .put("side", "redempt")
                .toString();
        JsonNode resp = post("/api/v5/finance/savings/purchase-redempt", body);
        checkOkxResponse(resp, "redeem " + ccy);
        log.info("[OKXEarn] Redeemed {} {}", amtStr, ccy);
    }

    // ────────────────────────────────────────────────────────────
    //  DTOs
    // ────────────────────────────────────────────────────────────

    /**
     * @param amt        本金（已申購金額）
     * @param earnings   累計已獲利息
     * @param rate       OKX balance API 回傳的日利率，**百分比形式**（如 0.01 = 0.01%/day ≈ 3.65% APY）。
     *                   注意：這是實際市場成交的日利率，不是 subscribe 時設的 MIN_LENDING_RATE。
     *                   換算公式：apyAnnualized() = rate × 365。勿再 ×100（已是 % 形式）。
     * @param loanAmt    已出借金額
     * @param pendingAmt 待匹配金額（尚未被借出）
     */
    public record EarnBalance(
            String ccy,
            BigDecimal amt,
            BigDecimal earnings,
            BigDecimal rate,
            BigDecimal loanAmt,
            BigDecimal pendingAmt
    ) {
        /**
         * 年化 APY（%）。
         * rate 來自 OKX balance API = 日利率百分比（如 0.01 = 0.01%/day）；
         * 年化 = rate × 365，不需再 ×100。
         */
        public BigDecimal apyAnnualized() {
            return rate.multiply(BigDecimal.valueOf(365))
                       .setScale(2, RoundingMode.HALF_UP);
        }
    }

    /**
     * @param avgRate  近期平均年化利率（OKX 回傳年利率 decimal，如 0.025 = 2.5% APY）
     * @param preRate  前一期年化利率
     * @param estRate  下一期預估年化利率
     *
     * <p>注意：lending-rate-summary 的三個 rate 欄位是年利率 decimal（× 100 轉百分比）；
     * balance 的 rate 欄位是日利率百分比形式（× 365 轉年化 APY，不需再 ×100）。
     */
    public record EarnRateSummary(
            String ccy,
            BigDecimal avgRate,
            BigDecimal preRate,
            BigDecimal estRate
    ) {
        public BigDecimal avgApyPct()  { return toApyPct(avgRate); }
        public BigDecimal preApyPct()  { return toApyPct(preRate); }
        public BigDecimal estApyPct()  { return toApyPct(estRate); }

        /** 年化利率（summary endpoint 回傳年利率 decimal，直接 × 100 轉百分比） */
        private static BigDecimal toApyPct(BigDecimal annualRate) {
            return annualRate.multiply(BigDecimal.valueOf(100))
                             .setScale(2, RoundingMode.HALF_UP);
        }
    }

    // ────────────────────────────────────────────────────────────
    //  HTTP helpers（與 OkxTradingService 相同 signing 邏輯）
    // ────────────────────────────────────────────────────────────

    private JsonNode get(String path) {
        String ts = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + path)
                .headers(buildHeaders(ts, "GET", path, ""))
                .get()
                .build();
        return execute(req, path);
    }

    private JsonNode post(String path, String jsonBody) {
        String ts = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
        RequestBody body = RequestBody.create(jsonBody, JSON_TYPE);
        Request req = new Request.Builder()
                .url(props.getBaseUrl() + path)
                .headers(buildHeaders(ts, "POST", path, jsonBody))
                .post(body)
                .build();
        return execute(req, path);
    }

    private okhttp3.Headers buildHeaders(String timestamp, String method, String path, String body) {
        if (!props.hasPrivateCredentials()) {
            throw new IllegalStateException(
                    "OKX private API credentials are not configured (trading.okx.api-key/secret-key/passphrase)");
        }
        return new okhttp3.Headers.Builder()
                .add("OK-ACCESS-KEY",        props.getApiKey())
                .add("OK-ACCESS-SIGN",       sign(timestamp, method, path, body))
                .add("OK-ACCESS-TIMESTAMP",  timestamp)
                .add("OK-ACCESS-PASSPHRASE", props.getPassphrase())
                .add("Content-Type",         "application/json")
                .build();
    }

    private JsonNode execute(Request req, String path) {
        try (Response resp = httpClient.newCall(req).execute()) {
            String bodyStr = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException("OKX HTTP " + resp.code() + " [" + path + "]: " + bodyStr);
            }
            return objectMapper.readTree(bodyStr);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OKX Earn call failed [" + path + "]: " + e.getMessage(), e);
        }
    }

    private String sign(String timestamp, String method, String path, String body) {
        try {
            String prehash = timestamp + method + path + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(prehash.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("OKX signing failed", e);
        }
    }

    private void checkOkxResponse(JsonNode root, String op) {
        String code = root.path("code").asText("1");
        if (!"0".equals(code)) {
            String msg = root.path("msg").asText("unknown error");
            throw new RuntimeException("OKX Earn " + op + " failed: [" + code + "] " + msg);
        }
    }

    private BigDecimal toBD(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(value); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }
}
