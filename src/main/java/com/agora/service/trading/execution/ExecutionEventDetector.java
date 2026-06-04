package com.agora.service.trading.execution;

import java.time.LocalDateTime;
import java.util.List;

public interface ExecutionEventDetector {

    List<ExecutionEventService.Draft> detect(LocalDateTime now);
}
