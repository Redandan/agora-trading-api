package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JdbcExactTradeFillAppendRepositoryTest {
    @Test
    void identicalRunIsIdempotentAndContentConflictRollsBackWithoutOverwrite() throws Exception {
        DriverManagerDataSource ds = dataSource();
        try (var c = ds.getConnection(); InputStream in = Objects.requireNonNull(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V3__immutable_trade_fill_evidence.sql"))) {
            ScriptUtils.executeSqlScript(c, new ByteArrayResource(in.readAllBytes()));
        }
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var repo = new JdbcExactTradeFillAppendRepository(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        CollectionAppend first = collection("1".repeat(64), "c".repeat(64));

        ExactTradeFillAppendRepository.AppendResult appended = tx.execute(s -> repo.append(first));
        ExactTradeFillAppendRepository.AppendResult duplicate = tx.execute(s -> repo.append(first));
        assertThat(appended).isEqualTo(ExactTradeFillAppendRepository.AppendResult.APPENDED);
        assertThat(duplicate)
                .isEqualTo(ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);

        CollectionAppend conflict = collection("2".repeat(64), "d".repeat(64));
        assertThatThrownBy(() -> tx.execute(s -> repo.append(conflict)))
                .isInstanceOf(JdbcExactTradeFillAppendRepository.ExactFillConflictException.class)
                .hasMessageContaining("different immutable content");
        assertThat(jdbc.queryForObject("SELECT immutable_content_sha256 FROM immutable_trade_fill", String.class))
                .isEqualTo("c".repeat(64));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exact_trade_fill_collection_run WHERE run_id=?",
                Integer.class, "2".repeat(64))).isZero();
    }

    private static CollectionAppend collection(String runId, String contentHash) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        String page = "b".repeat(64);
        RawFill fill = new RawFill("okx", "a".repeat(64), "BTC-USDT", "SPOT", "o1", "t1", "bill1",
                now, "BUY", new BigDecimal("100"), new BigDecimal("1"), new BigDecimal("-0.001"), "BTC",
                "T", "e".repeat(64), page, now, "cohort", 1L, 2L, null, null,
                "f".repeat(64), contentHash);
        CollectionRun run = new CollectionRun(runId, "okx", "a".repeat(64), "BTC-USDT", "SPOT", "5".repeat(64),
                RunStatus.COMPLETE_CANDIDATE, now, now, 2, 1, "bill1", "9".repeat(64), null);
        PageManifest data = new PageManifest(runId, 0, null, "bill1", page, "8".repeat(64), 1, false, now);
        PageManifest terminal = new PageManifest(runId, 1, "bill1", null, "7".repeat(64),
                "6".repeat(64), 0, true, now);
        return new CollectionAppend(run, List.of(data, terminal), List.of(fill));
    }
    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:exactrepo" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
