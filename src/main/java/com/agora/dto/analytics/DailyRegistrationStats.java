package com.agora.dto.analytics;

import java.time.LocalDate;

public interface DailyRegistrationStats {
    LocalDate getRegistrationDate();
    Long getCount();
}
