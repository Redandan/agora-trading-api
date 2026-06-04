package com.agora.service.backtest;

import com.agora.dto.backtest.BacktestResultResponse;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BacktestDiagnosticCollector {

    public static final String CONFIG_KEY = "__backtestDiagnosticCollector";
    public static final String DIAGNOSTICS_CONFIG_KEY = "diagnostics";

    private final Map<String, DiagnosticEntry> entries = new LinkedHashMap<String, DiagnosticEntry>();
    private final Set<String> disabledCodes = new HashSet<String>();

    public BacktestDiagnosticCollector() {
        this(null);
    }

    public BacktestDiagnosticCollector(Map<DiagnosticCode, Boolean> diagnostics) {
        if (diagnostics == null) {
            return;
        }
        for (Map.Entry<DiagnosticCode, Boolean> entry : diagnostics.entrySet()) {
            if (Boolean.FALSE.equals(entry.getValue())) {
                disabledCodes.add(entry.getKey().getCode());
            }
        }
    }

    public void record(DiagnosticCode code, LocalDateTime time, String detail) {
        if (!code.isEnable() || disabledCodes.contains(code.getCode())) {
            return;
        }
        doRecord(code.getCode(), time, detail);
    }

    public void record(String code, LocalDateTime time, String detail) {
        if (code == null) {
            return;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty() || disabledCodes.contains(trimmed)) {
            return;
        }
        doRecord(trimmed, time, detail);
    }

    private void doRecord(String code, LocalDateTime time, String detail) {
        DiagnosticEntry entry = entries.get(code);
        if (entry == null) {
            entry = new DiagnosticEntry(code);
            entries.put(code, entry);
        }
        entry.record(time, detail);
    }

    public List<BacktestResultResponse.DiagnosticLogDto> snapshotLogs() {
        List<DiagnosticEntry> sorted = new ArrayList<DiagnosticEntry>(entries.values());
        sorted.sort(Comparator.comparingInt(DiagnosticEntry::getCount).reversed());

        List<BacktestResultResponse.DiagnosticLogDto> logs = new ArrayList<BacktestResultResponse.DiagnosticLogDto>();
        for (DiagnosticEntry entry : sorted) {
            logs.add(entry.toDto());
        }
        return logs;
    }

    public static BacktestDiagnosticCollector fromConfig(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        Object value = config.get(CONFIG_KEY);
        return value instanceof BacktestDiagnosticCollector ? (BacktestDiagnosticCollector) value : null;
    }

    private static final class DiagnosticEntry {
        private final String code;
        private int count;
        private LocalDateTime firstTime;
        private LocalDateTime lastTime;
        private String sampleDetail;

        private DiagnosticEntry(String code) {
            this.code = code;
        }

        private void record(LocalDateTime time, String detail) {
            count++;
            if (firstTime == null && time != null) {
                firstTime = time;
            }
            if (time != null) {
                lastTime = time;
            }
            if (detail != null && !detail.trim().isEmpty()) {
                sampleDetail = detail;
            }
        }

        private int getCount() {
            return count;
        }

        private BacktestResultResponse.DiagnosticLogDto toDto() {
            BacktestResultResponse.DiagnosticLogDto dto = new BacktestResultResponse.DiagnosticLogDto();
            dto.setCode(code);
            dto.setCount(count);
            dto.setFirstOccurredAt(firstTime);
            dto.setLastOccurredAt(lastTime);
            dto.setSampleDetail(sampleDetail);
            return dto;
        }
    }
}