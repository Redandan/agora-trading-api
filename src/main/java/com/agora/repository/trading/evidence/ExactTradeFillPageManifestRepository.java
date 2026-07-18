package com.agora.repository.trading.evidence;

import com.agora.model.evidence.ExactTradeFillPageManifest;
import org.springframework.data.repository.Repository;

import java.util.List;

public interface ExactTradeFillPageManifestRepository extends Repository<ExactTradeFillPageManifest, Long> {
    List<ExactTradeFillPageManifest> findByRunIdOrderByPageIndexAsc(String runId);
}
