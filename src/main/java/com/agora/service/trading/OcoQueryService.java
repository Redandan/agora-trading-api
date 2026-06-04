package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * #370 — Read-only query service for OCO / open auto-traded positions.
 *
 * <p>Extracted from {@code AdminOcoController} which previously injected
 * {@link BtLiveSignalRepository} directly (arch boundary violation —
 * controllers should not import {@code com.agora.repository.*}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OcoQueryService {

    private final BtLiveSignalRepository liveSignalRepository;

    public List<BtLiveSignal> findOpenAutoTradedPositions() {
        return liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
    }
}
