package com.agora.service.market;

import com.agora.model.MdKline;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class MdKlineInsertHelperTest {
    @Test
    void databaseFailureIsNotReportedAsDuplicate() {
        var helper = new MdKlineInsertHelper(new JdbcTemplate() {
            @Override public int update(String sql, Object... args) {
                throw new DataAccessResourceFailureException("fixture DB unavailable");
            }
        });
        assertThrows(DataAccessResourceFailureException.class, () -> helper.insertIgnore(bar()));
    }

    @Test
    void onlyAffectedRowsDistinguishInsertedFromDuplicate() {
        for (int rows : new int[]{0, 1}) {
            var helper = new MdKlineInsertHelper(new JdbcTemplate() {
                @Override public int update(String sql, Object... args) { return rows; }
            });
            assertEquals(rows == 1, helper.insertIgnore(bar()));
        }
    }

    private MdKline bar() {
        var bar = new MdKline();
        bar.setOpenTime(LocalDateTime.of(2026, 10, 1, 10, 0));
        bar.setCloseTime(bar.getOpenTime().plusHours(1));
        return bar;
    }
}
