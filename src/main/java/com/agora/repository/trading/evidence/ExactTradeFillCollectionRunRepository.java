package com.agora.repository.trading.evidence;

import com.agora.model.evidence.ExactTradeFillCollectionRun;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface ExactTradeFillCollectionRunRepository extends Repository<ExactTradeFillCollectionRun, Long> {
    Optional<ExactTradeFillCollectionRun> findFirstByProviderAndAccountRefHashAndInstrumentIdAndInstrumentTypeAndBindingScopeSha256OrderByCompletedAtDescIdDesc(
            String provider, String accountRefHash, String instrumentId, String instrumentType,
            String bindingScopeSha256);
}
