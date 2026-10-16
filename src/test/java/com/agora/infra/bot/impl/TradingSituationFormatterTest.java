package com.agora.infra.bot.impl;

import com.agora.mcp.ExecutionSafetyMcpTools;
import com.agora.mcp.OkxNativeGridMcpTools;
import com.agora.mcp.StrategyCatalogMcpTools;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingSituationFormatterTest {
    private static final String POSITIONS = """
            OPEN_SPOT_POSITIONS
            - id=1 managementState=DRA_V1_OWNED executionUnresolved=false entryCost=30 grossUnrealizedPnl=-0.96
            - id=2 managementState=ADOPTED_FROM_OCO executionUnresolved=false entryCost=30 grossUnrealizedPnl=8.63
            count=2 pricedCount=2 valuationComplete=true
            asOf=2026-10-16T04:01:29Z

            REALIZED_SPOT_PNL_LEDGER
            cumulative:
            - owner=DRA_V1 closedLots=1 recordedRealizedPnl=1.58 exactNetLots=0 exactNetRealizedPnl=N/A
            cumulativeSummary comparisonStatus=MISSING_PROOF
            """;
    private static final String CATALOG = """
            STRATEGY_RUNTIME_CATALOG
            - BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1 mode=LIVE
            - TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2@v2 mode=LIVE
            - BTC_DONCHIAN_20D_10D_V1@v1 mode=SHADOW
            draExecutionArmed=true
            owner509ExecutionArmed=false
            STRATEGY_RUNTIME_OBSERVATIONS
            {"strategies":[{"contract":"BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1","observationStatus":"AVAILABLE","marketDataStatus":"CURRENT","latestClosedBarUtc":"2026-10-16T04:00","decisionEvidenceStatus":"OBSERVED","decisionMatchesLatestClosedBar":true,"recordedOpenCostUsdt":30,"configuredExposureCapUsdt":30,"oldestOpenRecordAgeHours":292,"entryDecision":{"stage":"WAITING_FOR_UTC_DAILY_CLOSE"}}]}
            """;
    private static final String GRID = """
            {"activeCount":1,"active":[{"algoId":"123","instId":"BTC-USDT","state":"running","investment":"13.62","arbitrageNum":"9","minPx":"78000","maxPx":"90000","gridNum":"10","gridProfit":"0.1153","floatProfit":"-0.2304","totalPnl":"-0.1151"}],"rangeObservations":[{"algoId":"123","rangeStatus":"WITHIN_RANGE"}]}
            """;

    @Test void separatesGridTotalFromCycleProfitAndLegacyFromLive() {
        String text = TradingSituationFormatter.format(POSITIONS, "issues=0\nstatus=OK", CATALOG, GRID);
        assertTrue(text.contains("總損益：-0.1151 USDT"));
        assertTrue(text.contains("配對收益：+0.1153｜浮動損益：-0.2304 USDT"));
        assertTrue(text.contains("浮盈虧：-0.9600 USDT（-3.20%）"));
        assertTrue(text.contains("舊 BTC 持倉｜非現行策略績效"));
        assertTrue(text.contains("已記錄平倉損益：+1.5800 USDT（費用或成交憑證未完整核對）"));
        assertFalse(text.contains("已核對淨利："));
        assertTrue(text.contains("10-16 12:01（台灣時間）"));
        assertTrue(text.contains("只做模擬觀察，不會實際下單"));
        assertTrue(text.length() < 4096);
    }

    @Test void missingOrMalformedInputsNeverBecomeEmptyPositionsOrHealthyState() {
        for (String bad : new String[]{null, "", "unavailable", "{"}) {
            String text = TradingSituationFormatter.format(bad, bad, bad, bad);
            assertTrue(text.contains("資料未完整取得"));
            assertTrue(text.contains("無法判定運行狀態或損益"));
            assertFalse(text.contains("持倉：0 筆"));
            assertFalse(text.contains("未發現待處理異常"));
            assertFalse(text.contains("尚無平倉紀錄"));
        }
    }

    @Test void unresolvedAndUnpricedPositionsNeverShowProfit() {
        for (String modified : new String[]{POSITIONS.replace("executionUnresolved=false", "executionUnresolved=true"),
                POSITIONS.replace("grossUnrealizedPnl=-0.96", "grossUnrealizedPnl=N/A")}) {
            String text = TradingSituationFormatter.format(modified, "status=REVIEW", CATALOG, GRID);
            assertFalse(text.contains("浮盈虧：-0.9600"));
            assertTrue(text.contains("有待處理異常"));
        }
    }

    @Test void staleDecisionAndOutOfRangeBotCannotLookCurrent() {
        String text = TradingSituationFormatter.format(POSITIONS, "", CATALOG.replace("CURRENT", "STALE"),
                GRID.replace("WITHIN_RANGE", "BELOW_RANGE"));
        assertFalse(text.contains("最新評估：等待下一次日線收盤確認"));
        assertTrue(text.contains("已過期，勿當作即時判斷"));
        assertTrue(text.contains("價格低於下界，需檢視"));
    }

    @Test void exactRealizedRequiresCompleteCoverageAndMalformedGridIsNotEmpty() {
        String exact = POSITIONS.replace("exactNetLots=0 exactNetRealizedPnl=N/A", "exactNetLots=1 exactNetRealizedPnl=1.58");
        assertTrue(TradingSituationFormatter.format(exact, "", CATALOG, "{}").contains("已核對淨利：+1.5800"));
        String partial = exact.replace("closedLots=1", "closedLots=2");
        assertFalse(TradingSituationFormatter.format(partial, "", CATALOG, "{}").contains("已核對淨利："));
        assertFalse(TradingSituationFormatter.format(POSITIONS, "", CATALOG, "{}").contains("目前沒有運行中的網格"));
    }

    @Test void failedProviderSectionKeepsOtherEvidenceWithoutCallingMutationOrHistory() {
        var execution = new ExecutionSafetyMcpTools(null, null, null, null, null) {
            @Override public String getOpenSpotPositions() { return POSITIONS; }
            @Override public String getExecutionSafetyStatus() { return "status=OK\nissues=0"; }
        };
        var catalog = new StrategyCatalogMcpTools(null, null, null, null, null, null, null) {
            @Override public String getStrategyRuntimeCatalog() { return CATALOG; }
        };
        int[] calls = {0};
        var grid = new OkxNativeGridMcpTools(null, null) {
            @Override public String getOkxNativeSpotGridStatus(Boolean includeHistory) {
                calls[0]++;
                assertEquals(Boolean.FALSE, includeHistory);
                throw new IllegalStateException("unavailable");
            }
        };
        String text = new TradingReportFacadeImpl(execution, catalog, null, grid).currentSituation();
        assertTrue(text.contains("浮盈虧：-0.9600"));
        assertTrue(text.contains("無法判定運行狀態或損益"));
        assertEquals(1, calls[0]);
    }
}
