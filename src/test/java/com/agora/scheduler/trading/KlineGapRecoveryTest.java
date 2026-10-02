package com.agora.scheduler.trading;

import com.agora.event.KlineClosedEvent;
import com.agora.model.MdKline;
import com.agora.service.market.MdKlineInsertHelper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KlineGapRecoveryTest {
    @Test
    void recoveryPublishesChronologicallyAndPreservesCommittedEventsBeforeLaterFailure() {
        var published = new ArrayList<LocalDateTime>();
        var helper = new MdKlineInsertHelper(null) {
            @Override public boolean insertIgnore(MdKline k) {
                if (k.getOpenTime().getHour() == 12) throw new DataAccessResourceFailureException("outage");
                return k.getOpenTime().getHour() != 11; // duplicate
            }
        };
        var detector = new KlineGapDetector(null, null, null, null,
                event -> published.add(((KlineClosedEvent) event).getKline().getOpenTime()), helper, null);
        assertThrows(DataAccessResourceFailureException.class,
                () -> detector.insertBackfillKlines(List.of(bar(12), bar(11), bar(10), bar(9))));
        assertEquals(List.of(bar(9).getOpenTime(), bar(10).getOpenTime()), published);
    }

    private MdKline bar(int hour) {
        var bar = new MdKline();
        bar.setOpenTime(LocalDateTime.of(2026, 10, 1, hour, 0));
        return bar;
    }
}
