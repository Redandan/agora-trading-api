package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtOcoAdjustmentAudit;
import com.agora.repository.trading.BtOcoAdjustmentAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcoAdjustmentAuditWriter {

    private final BtOcoAdjustmentAuditRepository repository;

    @Async("metaAuditExecutor")
    public void log(BtLiveSignal pos,
                    String action,
                    Long oldOcoId,
                    Long newOcoId,
                    BigDecimal oldTp,
                    BigDecimal newTp,
                    BigDecimal oldSl,
                    BigDecimal newSl,
                    BigDecimal oldQty,
                    BigDecimal newQty,
                    String source,
                    String reason) {
        if (pos == null || pos.getId() == null || pos.getSymbol() == null) {
            return;
        }
        try {
            BtOcoAdjustmentAudit audit = new BtOcoAdjustmentAudit();
            audit.setLiveSignalId(pos.getId());
            audit.setStrategyId(pos.getStrategyId());
            audit.setSymbol(pos.getSymbol());
            audit.setSide(pos.getSide() == null || pos.getSide().isBlank() ? "LONG" : pos.getSide());
            audit.setAction(truncate(action, 32));
            audit.setOldOcoOrderListId(oldOcoId);
            audit.setNewOcoOrderListId(newOcoId);
            audit.setOldTp(oldTp);
            audit.setNewTp(newTp);
            audit.setOldSl(oldSl);
            audit.setNewSl(newSl);
            audit.setOldQty(oldQty);
            audit.setNewQty(newQty);
            audit.setSource(truncate(source, 64));
            audit.setReason(truncate(reason, 500));
            audit.setEffectiveAt(LocalDateTime.now(ZoneOffset.UTC));
            repository.save(audit);
        } catch (Exception e) {
            log.warn("[OcoAdjustmentAudit] write failed posId={} action={} err={}",
                    pos.getId(), action, e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
