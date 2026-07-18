package com.agora.repository.trading.evidence;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.*;
import com.agora.service.trading.evidence.okx.ExactTradeFillHashing;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        CollectionAppend first = collection("1".repeat(64), "100");

        ExactTradeFillAppendRepository.AppendResult appended = tx.execute(s -> repo.append(first));
        ExactTradeFillAppendRepository.AppendResult duplicate = tx.execute(s -> repo.append(first));
        assertThat(appended).isEqualTo(ExactTradeFillAppendRepository.AppendResult.APPENDED);
        assertThat(duplicate)
                .isEqualTo(ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);

        CollectionAppend stableDraft = collection("3".repeat(64), "100");
        CollectionRun stableRun = new CollectionRun(stableDraft.run().runId(), stableDraft.run().provider(),
                stableDraft.run().accountRefHash(), stableDraft.run().instrumentId(), stableDraft.run().instrumentType(),
                stableDraft.run().bindingScopeSha256(), RunStatus.COMPLETE_STABLE, stableDraft.run().startedAt(),
                stableDraft.run().completedAt(), stableDraft.run().pageCount(), stableDraft.run().fillCount(),
                stableDraft.run().terminalCursor(), stableDraft.run().canonicalFillSetSha256(), first.run().runId());
        CollectionAppend stable = new CollectionAppend(stableRun, stableDraft.pages(), stableDraft.fills());
        ExactTradeFillAppendRepository.AppendResult stableAppended = tx.execute(s -> repo.append(stable));
        ExactTradeFillAppendRepository.AppendResult stableDuplicate = tx.execute(s -> repo.append(stable));
        assertThat(stableAppended).isEqualTo(ExactTradeFillAppendRepository.AppendResult.APPENDED);
        assertThat(stableDuplicate)
                .isEqualTo(ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);
        assertThat(repo.findRun(stableRun.runId())).get().extracting(CollectionRun::status)
                .isEqualTo(RunStatus.COMPLETE_STABLE);

        CollectionAppend concurrent = collection("4".repeat(64), "100");
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstAppend = executor.submit(() -> concurrentAppend(ds, repo, concurrent, ready, start));
            var secondAppend = executor.submit(() -> concurrentAppend(ds, repo, concurrent, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(firstAppend.get(10, TimeUnit.SECONDS), secondAppend.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ExactTradeFillAppendRepository.AppendResult.APPENDED,
                            ExactTradeFillAppendRepository.AppendResult.DUPLICATE_IDENTICAL);
        }

        CollectionAppend conflict = collection("2".repeat(64), "101");
        assertThatThrownBy(() -> tx.execute(s -> repo.append(conflict)))
                .isInstanceOf(JdbcExactTradeFillAppendRepository.ExactFillConflictException.class)
                .hasMessageContaining("different immutable content");
        assertThat(jdbc.queryForObject("SELECT immutable_content_sha256 FROM immutable_trade_fill", String.class))
                .isEqualTo(first.fills().getFirst().contentSha256());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exact_trade_fill_collection_run WHERE run_id=?",
                Integer.class, "2".repeat(64))).isZero();

        String firstRun = first.run().runId();
        jdbc.update("UPDATE exact_trade_fill_page_manifest SET request_cursor='altered' WHERE run_id=? AND page_index=1",
                firstRun);
        assertImmutableConflict(tx, repo, first, "immutable exact-fill identity conflict");
        jdbc.update("UPDATE exact_trade_fill_page_manifest SET request_cursor='900' WHERE run_id=? AND page_index=1",
                firstRun);

        jdbc.update("UPDATE exact_trade_fill_page_manifest SET page_sha256=? WHERE run_id=? AND page_index=0",
                "9".repeat(64), firstRun);
        assertImmutableConflict(tx, repo, first, "immutable exact-fill identity conflict");
        jdbc.update("UPDATE exact_trade_fill_page_manifest SET page_sha256=? WHERE run_id=? AND page_index=0",
                first.pages().getFirst().pageSha256(), firstRun);

        jdbc.update("UPDATE exact_trade_fill_run_item SET page_key=? WHERE run_id=?",
                first.pages().getLast().pageKey(), firstRun);
        assertImmutableConflict(tx, repo, first, "immutable exact-fill identity conflict");
        jdbc.update("UPDATE exact_trade_fill_run_item SET page_key=? WHERE run_id=?",
                first.pages().getFirst().pageKey(), firstRun);

        String alternatePageKey = "c".repeat(64);
        RawFill originalFill = first.fills().getFirst();
        RawFill alternateFill = new RawFill(originalFill.provider(), originalFill.accountRefHash(),
                originalFill.instrumentId(), originalFill.instrumentType(), originalFill.orderId(),
                originalFill.tradeId(), originalFill.billId(), originalFill.fillAt(), originalFill.side(),
                originalFill.fillPrice(), originalFill.fillQuantity(), originalFill.signedFeeAmount(),
                originalFill.feeCurrency(), originalFill.liquidityRole(), originalFill.rawPayloadSha256(),
                alternatePageKey, originalFill.collectedAt(), originalFill.cohortId(),
                originalFill.runtimeDecisionId(), originalFill.liveSignalId(), originalFill.intendedChildOrderId(),
                originalFill.actualChildOrderId(), originalFill.identitySha256(), originalFill.contentSha256());
        PageManifest alternateData = new PageManifest(firstRun, 0, null, "900", alternatePageKey,
                first.pages().getFirst().pageSha256(), 1, false, first.pages().getFirst().collectedAt());
        CollectionAppend sameFillSetDifferentProvenance = new CollectionAppend(first.run(),
                List.of(alternateData, first.pages().getLast()), List.of(alternateFill));
        assertImmutableConflict(tx, repo, sameFillSetDifferentProvenance, "immutable exact-fill identity conflict");

        jdbc.update("DELETE FROM exact_trade_fill_run_item WHERE run_id=?", first.run().runId());
        assertThatThrownBy(() -> tx.execute(s -> repo.append(first)))
                .isInstanceOf(JdbcExactTradeFillAppendRepository.ExactFillConflictException.class)
                .hasMessageContaining("committed run-item collection incomplete");
    }

    private static void assertImmutableConflict(TransactionTemplate tx, JdbcExactTradeFillAppendRepository repo,
                                                CollectionAppend collection, String message) {
        assertThatThrownBy(() -> tx.execute(s -> repo.append(collection)))
                .isInstanceOf(JdbcExactTradeFillAppendRepository.ExactFillConflictException.class)
                .hasMessageContaining(message);
    }

    private static ExactTradeFillAppendRepository.AppendResult concurrentAppend(
            DriverManagerDataSource ds, JdbcExactTradeFillAppendRepository repo, CollectionAppend collection,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent append start timeout");
        return new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(s -> repo.append(collection));
    }

    private static CollectionAppend collection(String runId, String price) {
        Instant now = Instant.parse("2026-07-18T00:00:00Z");
        String page = "b".repeat(64);
        RawFill draft = new RawFill("okx", "a".repeat(64), "BTC-USDT", "SPOT", "o1", "t1", "900",
                now, "BUY", new BigDecimal(price), new BigDecimal("1"), new BigDecimal("-0.001"), "BTC",
                "T", "e".repeat(64), page, now, "cohort", 1L, 2L, null, null, null, null);
        RawFill fill = new RawFill(draft.provider(), draft.accountRefHash(), draft.instrumentId(),
                draft.instrumentType(), draft.orderId(), draft.tradeId(), draft.billId(), draft.fillAt(),
                draft.side(), draft.fillPrice(), draft.fillQuantity(), draft.signedFeeAmount(), draft.feeCurrency(),
                draft.liquidityRole(), draft.rawPayloadSha256(), draft.sourcePageKey(), draft.collectedAt(),
                draft.cohortId(), draft.runtimeDecisionId(), draft.liveSignalId(), draft.intendedChildOrderId(),
                draft.actualChildOrderId(), ExactTradeFillHashing.identity(draft), ExactTradeFillHashing.content(draft));
        CollectionRun run = new CollectionRun(runId, "okx", "a".repeat(64), "BTC-USDT", "SPOT", "5".repeat(64),
                RunStatus.COMPLETE_CANDIDATE, now, now, 2, 1, "900",
                ExactTradeFillHashing.fillSet(List.of(fill)), null);
        PageManifest data = new PageManifest(runId, 0, null, "900", page, "8".repeat(64), 1, false, now);
        PageManifest terminal = new PageManifest(runId, 1, "900", null, "7".repeat(64),
                "6".repeat(64), 0, true, now);
        return new CollectionAppend(run, List.of(data, terminal), List.of(fill));
    }
    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:exactrepo" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
