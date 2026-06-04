package com.agora.service;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * #370 — Read-only kline query service shared by KlineMarketController +
 * KlineAdminController.
 *
 * <p>Both controllers previously injected {@link MdKlineRepository} directly
 * (arch boundary violation). Centralised here for future query logic
 * (caching, aggregation) without touching controllers.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KlineQueryService {

    private final MdKlineRepository klineRepository;

    public List<String> findDistinctSymbols() {
        return klineRepository.findDistinctSymbols();
    }

    public List<String> findDistinctIntervalsBySymbol(String symbol) {
        return klineRepository.findDistinctIntervalsBySymbol(symbol);
    }

    public List<MdKline> findLatest(String symbol, String intervalCode, Pageable pageable) {
        return klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(symbol, intervalCode, pageable);
    }

    public List<MdKline> findInRange(String symbol, String intervalCode,
                                       LocalDateTime startTime, LocalDateTime endTime) {
        return klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                symbol, intervalCode, startTime, endTime);
    }

    public List<Object[]> volumeStats(String symbol, String intervalCode,
                                       LocalDateTime startTime, LocalDateTime endTime) {
        return klineRepository.volumeStats(symbol, intervalCode, startTime, endTime);
    }
}
