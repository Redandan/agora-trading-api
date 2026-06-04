package com.agora.repository.trading;

import com.agora.model.IndicatorAlertState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IndicatorAlertStateRepository
        extends JpaRepository<IndicatorAlertState, String> {
}
