package com.agora.repository.trading;

import com.agora.model.BtGrid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BtGridRepository extends JpaRepository<BtGrid, Long> {

    /**
     * 取所有可執行的 grid(enabled=true 且 closed_at is null)。
     * GridManagerService 每根 bar 收盤時用此掃所有需要處理的 grid。
     */
    List<BtGrid> findByEnabledTrueAndClosedAtIsNull();

    List<BtGrid> findBySymbolAndClosedAtIsNull(String symbol);
}
