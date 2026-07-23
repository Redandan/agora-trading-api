package com.agora.repository.trading;

import com.agora.model.BtGrid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BtGridRepository extends JpaRepository<BtGrid, Long> {

    /** Historical query retained for archive and migration evidence only. */
    List<BtGrid> findByEnabledTrueAndClosedAtIsNull();

    List<BtGrid> findBySymbolAndClosedAtIsNull(String symbol);
}
