package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OCO 補掛業務邏輯服務。
 * 被 AdminOcoController（localhost REST）和 PositionMcpTools（MCP）共用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcoManagementService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final String SOFT_EXIT_NO_HARD_SL_MARKER = "SOFT_EXIT_NO_HARD_SL";

    /** Per-position mutex: prevents concurrent retryOco calls from double-cancelling/double-placing. */
    private final ConcurrentHashMap<Long, Boolean> retryInProgress = new ConcurrentHashMap<>();

    private final BtLiveSignalRepository liveSignalRepository;
    private final TradingService tradingService;
    private final OkxTradingService okxTradingService;
    private final NotificationPort notificationPort;
    private final OcoAdjustmentAuditWriter ocoAdjustmentAuditWriter;

    /**
     * 補掛 OCO 止盈/止損。
     * 流程：驗證倉位 → 查 OKX availBal → cancel 舊 OCO（best-effort）→ place 新 OCO → 更新 DB → TG 通知
     *
     * @param positionId BtLiveSignal.id
     * @return 結果摘要字串（給 MCP 或 REST 回應用）
     * @throws IllegalArgumentException 倉位驗證失敗（找不到、已平倉、資料不完整）
     * @throws RuntimeException         OKX placeOco 失敗
     */
    public String retryOco(Long positionId) {
        if (retryInProgress.putIfAbsent(positionId, Boolean.TRUE) != null) {
            throw new IllegalStateException("OCO retry already in progress for position " + positionId + ", please wait.");
        }
        try {
            return doRetryOco(positionId);
        } finally {
            retryInProgress.remove(positionId);
        }
    }

    private String doRetryOco(Long positionId) {
        log.info("[OcoManagement] OCO retry: positionId={}", positionId);

        // 1. Load & validate
        BtLiveSignal pos = liveSignalRepository.findById(positionId).orElse(null);
        if (pos == null)
            throw new IllegalArgumentException("Position not found: id=" + positionId);
        if (!Boolean.TRUE.equals(pos.getAutoTraded()))
            throw new IllegalArgumentException("Not an auto-traded position.");
        if (pos.getExitTime() != null)
            throw new IllegalArgumentException("Position already closed at " + pos.getExitTime().format(FMT));
        if (pos.getTradedQty() == null || pos.getTradedQty().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Invalid tradedQty.");
        if (pos.getSuggestedTp() == null || pos.getSuggestedSl() == null)
            throw new IllegalArgumentException("Missing suggestedTp or suggestedSl.");

        String symbol   = pos.getSymbol();
        BigDecimal dbQty = pos.getTradedQty();
        BigDecimal tp    = pos.getSuggestedTp();
        BigDecimal sl    = pos.getSuggestedSl();
        boolean isShort  = "SHORT".equals(pos.getSide());

        if (isShort) {
            return retrySwapOco(pos, positionId, symbol, dbQty, tp, sl);
        }

        // ── LONG（現貨）路徑 ────────────────────────────────────────────────

        // 2. 查 OKX 實際 availBal — #407 effective-avail computation
        //
        // retryOco runs in two scenarios:
        //   (a) ocoOrderListId == null  — placeOcoWithRetry failed previously,
        //       no OCO is active. availBal is final — what we see is what we
        //       can place. If availBal < dbQty, it means BTC is locked in
        //       another algo (Grid HOLDING etc) and we can only place partial
        //       protection. cancel below is a no-op.
        //   (b) ocoOrderListId != null  — caller is retrying while an OCO is
        //       still active. dbQty is locked in that OCO; cancel below will
        //       release it. effectiveAvail = availBal + dbQty.
        // Pre-#407 follow-up the code naively used min(dbQty, availBal),
        // which silently shrank the new OCO to availBal in case (b) when a
        // user manually called retryOco against an active OCO — the same
        // 40%-protection bug as #407 but reachable by a different code path.
        String base = symbol.replace("USDT", "");
        BigDecimal ocoQty = dbQty;
        String qtyNote;
        try {
            BigDecimal availBal = okxTradingService.getSpotHoldings().stream()
                    .filter(h -> base.equals(h.ccy))
                    .map(h -> h.availBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
            BigDecimal effectiveAvail = pos.getOcoOrderListId() != null
                    ? availBal.add(dbQty)  // case (b) — dbQty will release on cancel
                    : availBal;            // case (a) — no active OCO, availBal is final
            log.info("[OcoManagement] retryOco availBal for {}: raw={} effective={} (dbQty={}, oldOcoActive={})",
                    base, availBal, effectiveAvail, dbQty, pos.getOcoOrderListId() != null);
            if (effectiveAvail.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "OKX effective avail for " + base + " is 0 — position may already be closed.");
            }
            if (effectiveAvail.compareTo(dbQty) < 0) {
                ocoQty = effectiveAvail;
                qtyNote = String.format("Adjusted to effectiveAvail %s (raw availBal=%s, dbQty=%s — partial coverage, BTC locked elsewhere?)",
                        effectiveAvail.toPlainString(), availBal.toPlainString(), dbQty.toPlainString());
                log.warn("[OcoManagement] retryOco: effectiveAvail < dbQty — capping new OCO at {}", ocoQty);
            } else {
                qtyNote = "dbQty=" + dbQty.toPlainString();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OcoManagement] Could not query OKX availBal, using dbQty: {}", e.getMessage());
            qtyNote = "OKX balance query failed, using dbQty=" + dbQty.toPlainString();
        }

        // 3. Cancel existing OCO (best-effort)
        String cancelNote = "N/A";
        Long oldOcoId = pos.getOcoOrderListId();
        if (oldOcoId != null) {
            try {
                tradingService.cancelOco(symbol, oldOcoId);
                cancelNote = "Cancelled algoId=" + oldOcoId;
                log.info("[OcoManagement] Cancelled old OCO: symbol={} algoId={}", symbol, oldOcoId);
            } catch (Exception e) {
                cancelNote = "Cancel failed (ignored): " + e.getMessage();
                log.warn("[OcoManagement] Cancel OCO failed, proceeding anyway: {}", e.getMessage());
            }
        }

        // 4. Place new OCO
        Long newOcoId;
        try {
            newOcoId = tradingService.placeOco(symbol, ocoQty, tp, sl);
        } catch (Exception e) {
            log.error("[OcoManagement] placeOco failed: symbol={} positionId={} qty={} error={}",
                    symbol, positionId, ocoQty, e.getMessage());
            notifyTg(String.format(
                    "❌ <b>OCO 手動重試失敗</b>\n倉位 #%d %s\n數量: %s\n錯誤: <pre>%s</pre>\n⚠️ 倉位仍無 SL/TP 保護！",
                    positionId, escHtml(symbol), ocoQty.toPlainString(), escHtml(e.getMessage())));
            throw new RuntimeException("placeOco failed: " + e.getMessage(), e);
        }

        // 5. Persist
        // NOTE: We intentionally do NOT update tradedQty to ocoQty here.
        // tradedQty must reflect the actual purchased quantity for reconcileHoldings to work correctly.
        // The OCO coverage gap (dbQty - ocoQty, caused by grid sell orders locking spot balance) is a
        // known architectural constraint. PnL accuracy vs. reconcile correctness: reconcile wins.
        pos.setOcoOrderListId(newOcoId);
        clearSoftExitMarker(pos);
        liveSignalRepository.save(pos);
        ocoAdjustmentAuditWriter.log(pos, "RETRY_OCO", oldOcoId, newOcoId,
                tp, tp, sl, sl, dbQty, ocoQty, "OcoManagementService.retryOco", cancelNote);
        log.info("[OcoManagement] OCO retry OK: symbol={} positionId={} ocoQty={} newAlgoId={}",
                symbol, positionId, ocoQty, newOcoId);

        // 6. Telegram
        notifyTg(String.format(
                "✅ <b>OCO 手動重試成功</b>\n倉位 #%d %s\n數量: %s | TP: %s | SL: %s\n新 AlgoId: %d",
                positionId, escHtml(symbol),
                ocoQty.toPlainString(), tp.toPlainString(), sl.toPlainString(), newOcoId));

        return String.format(
                "✅ OCO 補掛成功\n倉位: #%d %s\n數量: %s (%s)\nTP: %s | SL: %s\n新 AlgoId: %d\n取消舊 OCO: %s",
                positionId, symbol, ocoQty.toPlainString(), qtyNote,
                tp.toPlainString(), sl.toPlainString(), newOcoId, cancelNote);
    }

    /**
     * SHORT（SWAP 合約）路徑的 OCO 補掛。
     * qty = 合約張數（整數），直接使用 DB tradedQty，無需查詢現貨餘額。
     */
    private String retrySwapOco(BtLiveSignal pos, Long positionId,
                                 String symbol, BigDecimal contractQty,
                                 BigDecimal tp, BigDecimal sl) {
        // Cancel old SWAP OCO (best-effort)
        String cancelNote = "N/A";
        Long oldOcoId = pos.getOcoOrderListId();
        if (oldOcoId != null) {
            try {
                okxTradingService.cancelSwapOco(symbol, oldOcoId);
                cancelNote = "Cancelled algoId=" + oldOcoId;
            } catch (Exception e) {
                cancelNote = "Cancel failed (ignored): " + e.getMessage();
                log.warn("[OcoManagement] Cancel SWAP OCO failed, proceeding: {}", e.getMessage());
            }
        }

        // Place new SWAP OCO
        Long newOcoId;
        try {
            newOcoId = okxTradingService.placeSwapOco(symbol, contractQty, tp, sl);
        } catch (Exception e) {
            log.error("[OcoManagement] placeSwapOco failed: positionId={} error={}", positionId, e.getMessage());
            notifyTg(String.format(
                    "❌ <b>SWAP OCO 手動重試失敗</b>\n倉位 #%d %s (SHORT)\n合約: %s\n錯誤: <pre>%s</pre>",
                    positionId, escHtml(symbol), contractQty.toPlainString(), escHtml(e.getMessage())));
            throw new RuntimeException("placeSwapOco failed: " + e.getMessage(), e);
        }

        pos.setOcoOrderListId(newOcoId);
        liveSignalRepository.save(pos);
        ocoAdjustmentAuditWriter.log(pos, "RETRY_OCO", oldOcoId, newOcoId,
                tp, tp, sl, sl, contractQty, contractQty, "OcoManagementService.retrySwapOco", cancelNote);
        log.info("[OcoManagement] SWAP OCO retry OK: symbol={} positionId={} contracts={} newAlgoId={}",
                symbol, positionId, contractQty, newOcoId);

        notifyTg(String.format(
                "✅ <b>SWAP OCO 補掛成功</b>\n倉位 #%d %s (SHORT)\n合約: %s | TP: %s | SL: %s\n新 AlgoId: %d",
                positionId, escHtml(symbol),
                contractQty.toPlainString(), tp.toPlainString(), sl.toPlainString(), newOcoId));

        return String.format(
                "✅ SWAP OCO 補掛成功\n倉位: #%d %s (SHORT)\n合約: %s 張\nTP: %s | SL: %s\n新 AlgoId: %d\n取消舊 OCO: %s",
                positionId, symbol, contractQty.toPlainString(),
                tp.toPlainString(), sl.toPlainString(), newOcoId, cancelNote);
    }

    /**
     * 修改現有 OCO 的 SL（和可選的 TP）。
     * 流程：驗證 → 取消舊 OCO → 用新價格掛新 OCO → 更新 DB（含 suggestedSl/Tp）→ TG 通知
     *
     * @param positionId BtLiveSignal.id
     * @param newSl      新止損價（必填）
     * @param newTp      新止盈價（null = 保留現有 TP 不動）
     */
    public String modifyOco(Long positionId, BigDecimal newSl, BigDecimal newTp) {
        if (retryInProgress.putIfAbsent(positionId, Boolean.TRUE) != null) {
            throw new IllegalStateException("OCO operation already in progress for position " + positionId);
        }
        try {
            return doModifyOco(positionId, newSl, newTp);
        } finally {
            retryInProgress.remove(positionId);
        }
    }

    private String doModifyOco(Long positionId, BigDecimal newSl, BigDecimal newTp) {
        log.info("[OcoManagement] modifyOco: positionId={} newSl={} newTp={}", positionId, newSl, newTp);

        // 1. Load & validate
        BtLiveSignal pos = liveSignalRepository.findById(positionId).orElse(null);
        if (pos == null)
            throw new IllegalArgumentException("Position not found: id=" + positionId);
        if (!Boolean.TRUE.equals(pos.getAutoTraded()))
            throw new IllegalArgumentException("Not an auto-traded position.");
        if (pos.getExitTime() != null)
            throw new IllegalArgumentException("Position already closed at " + pos.getExitTime().format(FMT));
        if (pos.getTradedQty() == null || pos.getTradedQty().compareTo(BigDecimal.ZERO) <= 0)
            throw new IllegalArgumentException("Invalid tradedQty.");

        BigDecimal tp = (newTp != null) ? newTp : pos.getSuggestedTp();
        BigDecimal sl = newSl;

        if (tp == null)
            throw new IllegalArgumentException("newTp not specified and no existing TP on record.");

        boolean isShort = "SHORT".equals(pos.getSide());
        if (!isShort && sl.compareTo(tp) >= 0)
            throw new IllegalArgumentException("SL must be below TP for LONG positions.");

        String symbol  = pos.getSymbol();
        BigDecimal dbQty = pos.getTradedQty();
        BigDecimal oldSl = pos.getSuggestedSl();
        BigDecimal oldTp = pos.getSuggestedTp();

        if (isShort) {
            return modifySwapOco(pos, positionId, symbol, dbQty, tp, sl, oldSl, oldTp);
        }

        // ── LONG（現貨）路徑 ────────────────────────────────────────────────

        // 2. 查 OKX availBal — #407 effective-avail computation
        //
        // The old OCO (oldOcoId, see step 3 below) currently locks dbQty in OKX.
        // OKX's reported availBal EXCLUDES that locked qty, so availBal < dbQty
        // is the normal case here. We will cancel the old OCO immediately after
        // this read; that releases dbQty back into avail. Therefore the effective
        // availBal at the moment we place the new OCO will be (availBal + dbQty).
        //
        // Pre-#407 the code compared raw availBal against dbQty and shrank ocoQty
        // to availBal whenever availBal < dbQty — which was always, since the
        // current OCO was still holding dbQty. That capped the new OCO to "free
        // BTC outside this position" (e.g. Grid HOLDING) rather than the actual
        // position size, leaving the position 60% unprotected and erroneously
        // locking unrelated BTC into the new OCO. retryOco only avoided the same
        // shape because in its scenario the old OCO is already gone, so availBal
        // already includes dbQty.
        String base = symbol.replace("USDT", "");
        BigDecimal ocoQty = dbQty;
        String qtyNote;
        try {
            BigDecimal availBal = okxTradingService.getSpotHoldings().stream()
                    .filter(h -> base.equals(h.ccy))
                    .map(h -> h.availBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
            BigDecimal effectiveAvail = availBal.add(dbQty); // dbQty will be released by cancelOco below
            log.info("[OcoManagement] modifyOco availBal for {}: raw={} effectiveAfterCancel={} (dbQty={})",
                    base, availBal, effectiveAvail, dbQty);
            if (effectiveAvail.compareTo(BigDecimal.ZERO) <= 0)
                throw new IllegalArgumentException("OKX availBal+dbQty for " + base + " is 0 — position may already be closed.");
            if (effectiveAvail.compareTo(dbQty) < 0) {
                // Pathological — even after the cancel-released dbQty, total avail
                // is still below dbQty. Means someone manually sold spot BTC mid-flow.
                ocoQty = effectiveAvail;
                qtyNote = String.format("Adjusted to effectiveAvail %s (raw availBal=%s, dbQty=%s — manual sell mid-flow?)",
                        effectiveAvail.toPlainString(), availBal.toPlainString(), dbQty.toPlainString());
                log.warn("[OcoManagement] modifyOco: effectiveAvail < dbQty — capping new OCO at {}", ocoQty);
            } else {
                qtyNote = "dbQty=" + dbQty.toPlainString();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[OcoManagement] modifyOco: Could not query OKX availBal, using dbQty: {}", e.getMessage());
            qtyNote = "OKX balance query failed, using dbQty=" + dbQty.toPlainString();
        }

        // 3. Cancel existing OCO (best-effort)
        String cancelNote = "N/A";
        Long oldOcoId = pos.getOcoOrderListId();
        if (oldOcoId != null) {
            try {
                tradingService.cancelOco(symbol, oldOcoId);
                cancelNote = "Cancelled algoId=" + oldOcoId;
                log.info("[OcoManagement] modifyOco: Cancelled old OCO algoId={}", oldOcoId);
            } catch (Exception e) {
                cancelNote = "Cancel failed (ignored): " + e.getMessage();
                log.warn("[OcoManagement] modifyOco: Cancel OCO failed, proceeding: {}", e.getMessage());
            }
        }

        // 4. Place new OCO
        Long newOcoId;
        try {
            newOcoId = tradingService.placeOco(symbol, ocoQty, tp, sl);
        } catch (Exception e) {
            log.error("[OcoManagement] modifyOco placeOco failed: positionId={} error={}", positionId, e.getMessage());
            notifyTg(String.format(
                    "❌ <b>OCO 止損調整失敗</b>\n倉位 #%d %s\n新 SL: %s | 新 TP: %s\n錯誤: <pre>%s</pre>\n⚠️ 舊 OCO 已取消，請立即補掛！",
                    positionId, escHtml(symbol), sl.toPlainString(), tp.toPlainString(), escHtml(e.getMessage())));
            throw new RuntimeException("placeOco failed: " + e.getMessage(), e);
        }

        // 5. Persist — 同步更新 suggestedSl/Tp，讓後續 retryOco 也用新值
        pos.setOcoOrderListId(newOcoId);
        pos.setSuggestedSl(sl);
        pos.setSuggestedTp(tp);
        clearSoftExitMarker(pos);
        liveSignalRepository.save(pos);
        ocoAdjustmentAuditWriter.log(pos, "MODIFY_OCO", oldOcoId, newOcoId,
                oldTp, tp, oldSl, sl, dbQty, ocoQty, "OcoManagementService.modifyOco", cancelNote);
        log.info("[OcoManagement] modifyOco OK: positionId={} sl: {} → {} tp: {} → {} newAlgoId={}",
                positionId, oldSl, sl, oldTp, tp, newOcoId);

        // 6. Telegram
        notifyTg(String.format(
                "✅ <b>OCO 止損調整成功</b>\n倉位 #%d %s\n" +
                "SL: %s → <b>%s</b>\nTP: %s → <b>%s</b>\n" +
                "數量: %s | 新 AlgoId: %d",
                positionId, escHtml(symbol),
                oldSl != null ? oldSl.toPlainString() : "N/A", sl.toPlainString(),
                oldTp != null ? oldTp.toPlainString() : "N/A", tp.toPlainString(),
                ocoQty.toPlainString(), newOcoId));

        return String.format(
                "✅ OCO 止損調整成功\n倉位: #%d %s\nSL: %s → %s\nTP: %s → %s\n數量: %s (%s)\n新 AlgoId: %d\n取消舊 OCO: %s",
                positionId, symbol,
                oldSl != null ? oldSl.toPlainString() : "N/A", sl.toPlainString(),
                oldTp != null ? oldTp.toPlainString() : "N/A", tp.toPlainString(),
                ocoQty.toPlainString(), qtyNote, newOcoId, cancelNote);
    }

    /**
     * Cancel the exchange-side hard OCO but keep the spot position open.
     *
     * <p>This explicitly opts the position into soft-exit management so the OCO
     * poller will not auto-retry a hard SL. Use only when the operator accepts
     * that no exchange-side TP/SL order remains active.</p>
     */
    public String cancelHardOcoKeepPosition(Long positionId, String reason) {
        if (retryInProgress.putIfAbsent(positionId, Boolean.TRUE) != null) {
            throw new IllegalStateException("OCO operation already in progress for position " + positionId);
        }
        try {
            return doCancelHardOcoKeepPosition(positionId, reason);
        } finally {
            retryInProgress.remove(positionId);
        }
    }

    private String doCancelHardOcoKeepPosition(Long positionId, String reason) {
        if (positionId == null) {
            throw new IllegalArgumentException("positionId is required.");
        }
        BtLiveSignal pos = liveSignalRepository.findById(positionId).orElse(null);
        if (pos == null)
            throw new IllegalArgumentException("Position not found: id=" + positionId);
        if (!Boolean.TRUE.equals(pos.getAutoTraded()))
            throw new IllegalArgumentException("Not an auto-traded position.");
        if (pos.getExitTime() != null)
            throw new IllegalArgumentException("Position already closed at " + pos.getExitTime().format(FMT));
        if ("SHORT".equals(pos.getSide()))
            throw new IllegalArgumentException("Soft no-hard-SL mode is only supported for spot LONG positions.");

        Long oldOcoId = pos.getOcoOrderListId();
        String cancelNote = "N/A";
        if (oldOcoId != null) {
            try {
                tradingService.cancelOco(pos.getSymbol(), oldOcoId);
                cancelNote = "Cancelled algoId=" + oldOcoId;
                log.info("[OcoManagement] Soft-exit cancelled hard OCO: positionId={} algoId={}", positionId, oldOcoId);
            } catch (Exception e) {
                log.warn("[OcoManagement] Soft-exit cancel OCO failed: positionId={} err={}", positionId, e.getMessage());
                throw new RuntimeException("cancelOco failed: " + e.getMessage(), e);
            }
        }

        String note = reason == null || reason.isBlank()
                ? "operator requested no exchange-side hard SL to avoid wick stop-outs"
                : reason.trim();
        pos.setOcoOrderListId(null);
        pos.setFilterReason(SOFT_EXIT_NO_HARD_SL_MARKER + ": " + note);
        liveSignalRepository.save(pos);
        ocoAdjustmentAuditWriter.log(pos, "CANCEL_HARD_OCO", oldOcoId, null,
                pos.getSuggestedTp(), pos.getSuggestedTp(), pos.getSuggestedSl(), pos.getSuggestedSl(),
                pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty(), null,
                "OcoManagementService.cancelHardOcoKeepPosition", cancelNote);

        notifyTg(String.format(
                "⚠️ <b>Hard OCO 已取消，改為 Soft Exit 管理</b>\n倉位 #%d %s\n"
                        + "狀態: 無交易所 SL/TP 掛單，不會被插針 SL 自動賣出\n"
                        + "TP(ref): %s | SL(ref): %s\n原因: %s",
                positionId, escHtml(pos.getSymbol()),
                pos.getSuggestedTp() != null ? pos.getSuggestedTp().toPlainString() : "N/A",
                pos.getSuggestedSl() != null ? pos.getSuggestedSl().toPlainString() : "N/A",
                escHtml(note)));

        return String.format(
                "✅ Hard OCO cancelled; spot position remains open under SOFT_EXIT_NO_HARD_SL\n"
                        + "positionId=%d symbol=%s\n"
                        + "oldAlgoId=%s\n"
                        + "cancel=%s\n"
                        + "tpReference=%s slReference=%s\n"
                        + "autoOcoRetrySuppressed=true\n"
                        + "warning=no exchange-side TP/SL is active; exits require soft manager/manual action",
                positionId, pos.getSymbol(), oldOcoId != null ? oldOcoId.toString() : "N/A", cancelNote,
                pos.getSuggestedTp() != null ? pos.getSuggestedTp().toPlainString() : "N/A",
                pos.getSuggestedSl() != null ? pos.getSuggestedSl().toPlainString() : "N/A");
    }

    private String modifySwapOco(BtLiveSignal pos, Long positionId, String symbol,
                                  BigDecimal contractQty, BigDecimal tp, BigDecimal sl,
                                  BigDecimal oldSl, BigDecimal oldTp) {
        String cancelNote = "N/A";
        Long oldOcoId = pos.getOcoOrderListId();
        if (oldOcoId != null) {
            try {
                okxTradingService.cancelSwapOco(symbol, oldOcoId);
                cancelNote = "Cancelled algoId=" + oldOcoId;
            } catch (Exception e) {
                cancelNote = "Cancel failed (ignored): " + e.getMessage();
                log.warn("[OcoManagement] modifyOco: Cancel SWAP OCO failed: {}", e.getMessage());
            }
        }

        Long newOcoId;
        try {
            newOcoId = okxTradingService.placeSwapOco(symbol, contractQty, tp, sl);
        } catch (Exception e) {
            log.error("[OcoManagement] modifySwapOco failed: positionId={} error={}", positionId, e.getMessage());
            notifyTg(String.format(
                    "❌ <b>SWAP OCO 止損調整失敗</b>\n倉位 #%d %s (SHORT)\n錯誤: <pre>%s</pre>",
                    positionId, escHtml(symbol), escHtml(e.getMessage())));
            throw new RuntimeException("placeSwapOco failed: " + e.getMessage(), e);
        }

        pos.setOcoOrderListId(newOcoId);
        pos.setSuggestedSl(sl);
        pos.setSuggestedTp(tp);
        clearSoftExitMarker(pos);
        liveSignalRepository.save(pos);
        ocoAdjustmentAuditWriter.log(pos, "MODIFY_OCO", oldOcoId, newOcoId,
                oldTp, tp, oldSl, sl, contractQty, contractQty, "OcoManagementService.modifySwapOco", cancelNote);
        log.info("[OcoManagement] modifySwapOco OK: positionId={} sl: {} → {} tp: {} → {} newAlgoId={}",
                positionId, oldSl, sl, oldTp, tp, newOcoId);

        notifyTg(String.format(
                "✅ <b>SWAP OCO 止損調整成功</b>\n倉位 #%d %s (SHORT)\n" +
                "SL: %s → <b>%s</b>\nTP: %s → <b>%s</b>\n新 AlgoId: %d",
                positionId, escHtml(symbol),
                oldSl != null ? oldSl.toPlainString() : "N/A", sl.toPlainString(),
                oldTp != null ? oldTp.toPlainString() : "N/A", tp.toPlainString(), newOcoId));

        return String.format(
                "✅ SWAP OCO 止損調整成功\n倉位: #%d %s (SHORT)\nSL: %s → %s\nTP: %s → %s\n合約: %s 張\n新 AlgoId: %d\n取消舊 OCO: %s",
                positionId, symbol,
                oldSl != null ? oldSl.toPlainString() : "N/A", sl.toPlainString(),
                oldTp != null ? oldTp.toPlainString() : "N/A", tp.toPlainString(),
                contractQty.toPlainString(), newOcoId, cancelNote);
    }

    private void notifyTg(String msg) {
        try {
            notificationPort.broadcast(msg, true);
        } catch (Exception e) {
            log.warn("[OcoManagement] TG notify failed: {}", e.getMessage());
        }
    }

    private void clearSoftExitMarker(BtLiveSignal pos) {
        if (pos.getFilterReason() != null
                && pos.getFilterReason().startsWith(SOFT_EXIT_NO_HARD_SL_MARKER)) {
            pos.setFilterReason(null);
        }
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
