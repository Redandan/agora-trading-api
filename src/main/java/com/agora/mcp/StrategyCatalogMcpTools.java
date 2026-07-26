package com.agora.mcp;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.BtcDraShadowProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.strategy.StrategyRuntimeCatalog;
import com.agora.service.strategy.StrategyRuntimeDefinition;
import com.agora.service.tradingview.TradingViewScoreBuyAutoExitStrategyContract;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Read-only strategy catalog tools. Database enabled flags are inventory only;
 * the catalog is the sole runtime authority.
 */
@Service
@RequiredArgsConstructor
public class StrategyCatalogMcpTools {

    private final StrategyRuntimeCatalog catalog;
    private final BtStrategyRepository strategyRepository;
    private final TradingViewLocalSignalProperties localProperties;
    private final OkxTradingProperties okxProperties;
    private final BtcDraShadowProperties draProperties;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Read-only strategy runtime catalog. Shows owner 509 LIVE, archived owner 508 V1, Donchian SHADOW, and the default-OFF DRA SHADOW candidate; all unlisted database strategies are ARCHIVED. No strategy, order, Grid, fund, or database state is changed.")
    public String getStrategyRuntimeCatalog() {
        StringBuilder result = new StringBuilder("STRATEGY_RUNTIME_CATALOG\n");
        for (StrategyRuntimeDefinition definition : catalog.definitions()) {
            append(result, definition);
        }
        result.append("databaseInventory:\n");
        try {
            List<BtStrategy> strategies = strategyRepository.findAll().stream()
                    .sorted(Comparator.comparing(BtStrategy::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            for (BtStrategy strategy : strategies) {
                StrategyRuntimeDefinition definition = catalog.describeDatabaseStrategy(strategy);
                result.append("- databaseId=").append(strategy.getId())
                        .append(" name=").append(safe(strategy.getName()))
                        .append(" databaseEnabled=").append(Boolean.TRUE.equals(strategy.getEnabled()))
                        .append(" runtimeMode=").append(definition.mode())
                        .append(" runtimeKey=").append(definition.versionedKey())
                        .append('\n');
            }
        } catch (Exception e) {
            result.append("- unavailable: ").append(e.getClass().getSimpleName()).append('\n');
        }
        result.append("draConfiguredEnabled=")
                .append(draProperties.enabled())
                .append('\n');
        result.append("exchangeOrderAuthorized=").append(executionArmed()).append('\n');
        return result.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "Read-only owner Strategy 509 runtime status. Confirms its daily Binance BTCUSDT signal scope, weighted OKX spot execution, and automatic per-lot profit exits. Never sends an order.")
    public String getOwner509RuntimeStatus() {
        StrategyRuntimeDefinition definition =
                catalog.require(TradingViewScoreBuyAutoExitStrategyContract.KEY);
        return "OWNER_509_RUNTIME_STATUS\n"
                + "contract=" + definition.versionedKey() + "\n"
                + "entryContract=" + TradingViewScoreBuyAutoExitStrategyContract.ENTRY_CONTRACT_KEY + "\n"
                + "ownerAlias=" + definition.ownerAlias() + "\n"
                + "databaseStrategyId=" + definition.databaseStrategyId() + "\n"
                + "mode=" + definition.mode() + "\n"
                + "symbol=" + definition.symbol() + "\n"
                + "interval=" + definition.interval() + "\n"
                + "source=" + definition.source() + "\n"
                + "configuredEnabled=" + localProperties.enabled() + "\n"
                + "configuredExecutionMode=" + localProperties.executionMode() + "\n"
                + "configuredStrategyId=" + localProperties.strategyId() + "\n"
                + "baseNotionalUsdt=" + localProperties.defaultNotionalUsdt() + "\n"
                + "maxOrderNotionalUsdt=" + localProperties.maxNotionalUsdt() + "\n"
                + "maxExposureUsdt=" + localProperties.btcBaseMaxExposureUsdt() + "\n"
                + "liveMaxSignalAgeMinutes=" + localProperties.liveMaxSignalAgeMinutes() + "\n"
                + "exitPolicy=" + TradingViewScoreBuyAutoExitStrategyContract.EXIT_POLICY + "\n"
                + "netProfitTrigger=" + TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER + "\n"
                + "minRealizedNetProfit=" + TradingViewScoreBuyAutoExitStrategyContract.MIN_REALIZED_NET_PROFIT + "\n"
                + "okxTradingEnabled=" + okxProperties.isEnabled() + "\n"
                + "okxPrivateCredentialsConfigured=" + okxProperties.hasPrivateCredentials() + "\n"
                + "exchangeOrderAuthorized=" + executionArmed() + "\n"
                + "executionArmed=" + executionArmed() + "\n";
    }

    private void append(StringBuilder result, StrategyRuntimeDefinition definition) {
        result.append("- ").append(definition.versionedKey())
                .append(" mode=").append(definition.mode())
                .append(" symbol=").append(safe(definition.symbol()))
                .append(" interval=").append(safe(definition.interval()))
                .append(" source=").append(safe(definition.source()))
                .append(" exchangeOrderAllowed=").append(definition.mode().exchangeOrderAllowed())
                .append('\n');
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "N/A" : value;
    }

    private boolean executionArmed() {
        return catalog.isMode(
                TradingViewScoreBuyAutoExitStrategyContract.KEY,
                com.agora.service.strategy.StrategyLifecycleMode.LIVE)
                && localProperties.effectiveExecutionLiveOrderEnabled()
                && okxProperties.isEnabled()
                && okxProperties.hasPrivateCredentials();
    }
}
