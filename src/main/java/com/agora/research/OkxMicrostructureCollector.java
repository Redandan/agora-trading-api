package com.agora.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Shared deterministic parser and minute aggregator for bounded V1 and continuous V2/V3 capture. */
final class OkxMicrostructureCollector {

    static final String ENDPOINT = "wss://ws.okx.com:8443/ws/v5/public";
    static final String INSTRUMENT = "BTC-USDT";
    static final String AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE";
    static final String SUBSCRIBE_MESSAGE = """
            {"op":"subscribe","args":[{"channel":"trades","instId":"BTC-USDT"},{"channel":"books5","instId":"BTC-USDT"}]}
            """.trim();

    private static final byte[] INITIAL_CHAIN = new byte[32];
    private static final Set<String> EXPECTED_CHANNELS = Set.of("trades", "books5");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
    static final int MAX_UNRESOLVED_TRADES = 10_000;

    private final ObjectMapper mapper;
    private final TreeMap<Instant, MinuteAccumulator> minutes = new TreeMap<>();
    private final TreeMap<Long, BigDecimal> v3BookMidByTimestamp = new TreeMap<>();
    private final TreeMap<Long, List<TradeRecord>> unresolvedV3Trades = new TreeMap<>();
    private final Map<String, Integer> sourceCounts = new TreeMap<>();
    private final Set<String> acknowledgedChannels = new HashSet<>();
    private byte[] arrivalChain = INITIAL_CHAIN.clone();
    private long rawMessageCount;
    private long tradesPayloadCount;
    private long booksPayloadCount;
    private long tradeTimestampRegressionCount;
    private long bookTimestampRegressionCount;
    private long tradeSequenceRegressionCount;
    private long bookSequenceRegressionCount;
    private long tradeIdNonIncreasingCount;
    private long malformedRecordCount;
    private long crossedBookCount;
    private long exchangeErrorCount;
    private Long lastTradeTimestamp;
    private Long lastBookTimestamp;
    private Long lastTradeSequence;
    private Long lastBookSequence;
    private Long lastTradeId;
    private BigDecimal lastBidDepthQuote;
    private BigDecimal lastBookMidPrice;
    private Long v3BookWatermarkTimestamp;
    private int unresolvedV3TradeCount;
    private long midlineUnreferencedTradeCount;
    private boolean unresolvedV3TradeOverflow;

    OkxMicrostructureCollector(ObjectMapper mapper) {
        this(mapper, Continuity.empty());
    }

    OkxMicrostructureCollector(ObjectMapper mapper, Continuity continuity) {
        this.mapper = mapper;
        this.lastTradeTimestamp = continuity.lastTradeTimestamp();
        this.lastBookTimestamp = continuity.lastBookTimestamp();
        this.lastTradeSequence = continuity.lastTradeSequence();
        this.lastBookSequence = continuity.lastBookSequence();
        this.lastTradeId = continuity.lastTradeId();
        this.lastBidDepthQuote = continuity.lastBidDepthQuote();
        this.lastBookMidPrice = continuity.lastBookMidPrice();
        if (lastBookTimestamp != null && lastBookMidPrice != null) {
            v3BookMidByTimestamp.put(lastBookTimestamp, lastBookMidPrice);
            v3BookWatermarkTimestamp = lastBookTimestamp;
        }
    }

    synchronized void acceptRaw(String raw) {
        rawMessageCount++;
        arrivalChain = chain(arrivalChain, raw.getBytes(StandardCharsets.UTF_8));
        try {
            ParsedMessage message = parse(mapper, raw, false);
            malformedRecordCount += message.malformedRecords();
            crossedBookCount += message.crossedBooks();
            switch (message.kind()) {
                case ACKNOWLEDGEMENT -> acknowledgedChannels.add(message.channel());
                case EXCHANGE_ERROR -> exchangeErrorCount++;
                case TRADES -> {
                    tradesPayloadCount++;
                    for (TradeRecord trade : message.trades()) {
                        acceptTrade(trade);
                    }
                }
                case BOOKS5 -> {
                    booksPayloadCount++;
                    for (BookRecord book : message.books()) {
                        acceptBook(book);
                    }
                }
                case OTHER_EVENT -> {
                    // Preserve bounded V1 behavior for unrelated OKX event messages.
                }
            }
        } catch (CrossedBookException ignored) {
            crossedBookCount++;
        } catch (Exception ignored) {
            malformedRecordCount++;
        }
    }

    synchronized void carryAcknowledgements(Set<String> channels) {
        for (String channel : channels) {
            if (!EXPECTED_CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("unexpected channel acknowledgement");
            }
            acknowledgedChannels.add(channel);
        }
    }

    synchronized Set<String> acknowledgedChannels() {
        return Set.copyOf(acknowledgedChannels);
    }

    synchronized void clearAcknowledgements() {
        acknowledgedChannels.clear();
    }

    synchronized Continuity continuity() {
        return new Continuity(
                lastTradeTimestamp,
                lastBookTimestamp,
                lastTradeSequence,
                lastBookSequence,
                lastTradeId,
                lastBidDepthQuote,
                lastBookMidPrice);
    }

    synchronized long anomalyCount() {
        return malformedRecordCount
                + crossedBookCount
                + exchangeErrorCount
                + tradeTimestampRegressionCount
                + bookTimestampRegressionCount
                + tradeSequenceRegressionCount
                + bookSequenceRegressionCount
                + tradeIdNonIncreasingCount;
    }

    synchronized int minuteCount() {
        return minutes.size();
    }

    synchronized int completedMinuteCountBefore(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("cutoff instant is required");
        }
        return minutes.headMap(instant.truncatedTo(ChronoUnit.MINUTES), false).size();
    }

    synchronized int unresolvedV3TradeCount() {
        return unresolvedV3TradeCount;
    }

    synchronized long midlineUnreferencedTradeCount() {
        return midlineUnreferencedTradeCount;
    }

    synchronized boolean unresolvedV3TradeOverflowed() {
        return unresolvedV3TradeOverflow;
    }

    synchronized String v3IntegrityFailureReason() {
        if (unresolvedV3TradeOverflow) {
            return "UNRESOLVED_TRADE_BUFFER_OVERFLOW";
        }
        if (midlineUnreferencedTradeCount != 0) {
            return "MIDLINE_UNREFERENCED_TRADE";
        }
        return null;
    }

    synchronized Map<String, Object> v3MinuteOutput(Instant minute) {
        MinuteAccumulator accumulator = minutes.get(minute.truncatedTo(ChronoUnit.MINUTES));
        if (accumulator == null) {
            throw new IllegalArgumentException("minute is not present");
        }
        return accumulator.v3Output(minute.truncatedTo(ChronoUnit.MINUTES));
    }

    synchronized Map<String, Object> buildV1Bundle(
            Instant captureStartedAt,
            Instant captureEndedAt,
            int requestedDurationSeconds,
            String listenerError) {
        boolean bothSubscriptions = acknowledgedChannels.containsAll(EXPECTED_CHANNELS);
        boolean bothStreamsObserved = tradesPayloadCount > 0 && booksPayloadCount > 0;
        boolean listenerHealthy = listenerError == null;
        String status = bothSubscriptions && bothStreamsObserved && listenerHealthy
                ? "CAPTURE_COMPLETE_RESEARCH_ONLY"
                : "DATA_REJECT_INCOMPLETE_CAPTURE";
        boolean integrityClean = anomalyCount() == 0;
        boolean fullUtcDay = isExactFullUtcDay(null);
        boolean canonicalEligible = "CAPTURE_COMPLETE_RESEARCH_ONLY".equals(status)
                && integrityClean
                && fullUtcDay;

        List<Map<String, Object>> minuteOutput = new ArrayList<>(minutes.size());
        minutes.forEach((minute, accumulator) -> minuteOutput.add(accumulator.v1Output(minute)));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("venue", "OKX");
        source.put("endpoint", ENDPOINT);
        source.put("instrument", INSTRUMENT);
        source.put("channels", List.of("trades", "books5"));
        source.put("mode", "FORWARD_ONLY_BOUNDED_CAPTURE");
        source.put("historical_backfill", false);
        source.put("raw_messages_persisted", false);
        source.put("minute_aggregation_timezone", "UTC");

        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("requested_duration_seconds", requestedDurationSeconds);
        capture.put("started_at", captureStartedAt.toString());
        capture.put("ended_at", captureEndedAt.toString());
        capture.put("acknowledged_channels", acknowledgedChannels.stream().sorted().toList());
        capture.put("trade_payloads", tradesPayloadCount);
        capture.put("books5_payloads", booksPayloadCount);
        capture.put("listener_error", listenerError);

        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("status", integrityClean ? "CLEAN" : "ANOMALIES_PRESENT");
        integrity.put("raw_message_count", rawMessageCount);
        integrity.put("arrival_chain_algorithm", "SHA-256(previous_digest || raw_utf8_message)");
        integrity.put("arrival_chain_sha256", HexFormat.of().formatHex(arrivalChain));
        integrity.put("malformed_record_count", malformedRecordCount);
        integrity.put("exchange_error_count", exchangeErrorCount);
        integrity.put("crossed_book_count", crossedBookCount);
        integrity.put("trade_timestamp_regression_count", tradeTimestampRegressionCount);
        integrity.put("book_timestamp_regression_count", bookTimestampRegressionCount);
        integrity.put("trade_sequence_regression_count", tradeSequenceRegressionCount);
        integrity.put("book_sequence_regression_count", bookSequenceRegressionCount);
        integrity.put("trade_id_non_increasing_count", tradeIdNonIncreasingCount);
        integrity.put("trade_source_record_counts", Map.copyOf(sourceCounts));

        Map<String, Object> eligibility = new LinkedHashMap<>();
        eligibility.put("full_utc_day_1440_contiguous_minutes", fullUtcDay);
        eligibility.put("integrity_clean", integrityClean);
        eligibility.put("both_channels_acknowledged", bothSubscriptions);
        eligibility.put("both_streams_observed", bothStreamsObserved);
        eligibility.put("note", "A short smoke capture is diagnostic only and cannot enter canonical evidence.");

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "OKX_MICROSTRUCTURE_FORWARD_BUNDLE_V1");
        output.put("status", status);
        output.put("authorization", AUTHORIZATION);
        output.put("canonical_evidence_eligible", canonicalEligible);
        output.put("source", source);
        output.put("capture", capture);
        output.put("integrity", integrity);
        output.put("eligibility", eligibility);
        output.put("metric_semantics", Map.of(
                "net_taker_quote_notional", "buy_quote_notional minus sell_quote_notional; side is taker side",
                "book_imbalance", "(top5_bid_quote_depth - top5_ask_quote_depth) / total_top5_quote_depth",
                "bid_replenishment_quote_proxy", "sum of positive changes in total top5 bid quote depth; price-level shifts can confound it"));
        output.put("minutes", minuteOutput);
        return output;
    }

    synchronized Map<String, Object> buildV2Payload(LocalDate day) {
        if (!acknowledgedChannels.containsAll(EXPECTED_CHANNELS)) {
            throw new IllegalStateException("STREAM_GAP: both channel acknowledgements are required");
        }
        if (anomalyCount() != 0) {
            throw new IllegalStateException("INTEGRITY_NOT_CLEAN: anomaly count is nonzero");
        }
        if (!isExactFullUtcDay(day)) {
            throw new IllegalStateException("INCOMPLETE_DAY: exactly 1440 complete UTC minutes are required");
        }

        List<Map<String, Object>> minuteOutput = new ArrayList<>(minutes.size());
        minutes.forEach((minute, accumulator) -> minuteOutput.add(accumulator.v2Output(minute)));

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("venue", "OKX");
        source.put("instrument", INSTRUMENT);
        source.put("channels", List.of("trades", "books5"));
        source.put("mode", "FORWARD_ONLY");
        source.put("historical_backfill", false);
        source.put("raw_messages_persisted", false);
        source.put("aggregation_timezone", "UTC");

        Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("started_at", dayStart.toString());
        capture.put("ended_at", dayStart.plus(1, ChronoUnit.DAYS).toString());
        capture.put("acknowledged_channels", List.of("books5", "trades"));

        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("status", "CLEAN");
        integrity.put("anomaly_count", 0);
        integrity.put("raw_message_count", rawMessageCount);
        integrity.put("arrival_chain_sha256", HexFormat.of().formatHex(arrivalChain));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "OKX_MICROSTRUCTURE_FORWARD_DAY_V2");
        output.put("bundle_type", "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY");
        output.put("authorization", AUTHORIZATION);
        output.put("source", source);
        output.put("day", day.toString());
        output.put("capture", capture);
        output.put("integrity", integrity);
        output.put("minutes", minuteOutput);
        return output;
    }

    synchronized Map<String, Object> buildV3Payload(LocalDate day) {
        if (unresolvedV3TradeOverflow) {
            throw new IllegalStateException(
                    "UNRESOLVED_TRADE_BUFFER_OVERFLOW: more than 10000 unresolved trades");
        }
        resolveAllV3Trades();
        if (!acknowledgedChannels.containsAll(EXPECTED_CHANNELS)) {
            throw new IllegalStateException("STREAM_GAP: both channel acknowledgements are required");
        }
        if (anomalyCount() != 0) {
            throw new IllegalStateException("INTEGRITY_NOT_CLEAN: anomaly count is nonzero");
        }
        if (midlineUnreferencedTradeCount != 0) {
            throw new IllegalStateException(
                    "MIDLINE_UNREFERENCED_TRADE: every trade requires an eligible books5 reference");
        }
        if (!isExactFullUtcDay(day)) {
            throw new IllegalStateException("INCOMPLETE_DAY: exactly 1440 complete UTC minutes are required");
        }

        List<Map<String, Object>> minuteOutput = new ArrayList<>(minutes.size());
        minutes.forEach((minute, accumulator) -> {
            if (!accumulator.v3BucketsComplete()) {
                throw new IllegalStateException(
                        "MIDLINE_BUCKET_MISMATCH: references and quote buckets must reconcile");
            }
            minuteOutput.add(accumulator.v3Output(minute));
        });

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("venue", "OKX");
        source.put("instrument", INSTRUMENT);
        source.put("channels", List.of("trades", "books5"));
        source.put("mode", "FORWARD_ONLY");
        source.put("historical_backfill", false);
        source.put("raw_messages_persisted", false);
        source.put("aggregation_timezone", "UTC");
        source.put("midline_formula", "BEST_BID_1_PLUS_BEST_ASK_1_DIVIDED_BY_2");
        source.put("midline_reference", "LATEST_BOOKS5_AT_OR_BEFORE_TRADE");
        source.put("unreferenced_trade_disposition", "INTEGRITY_ANOMALY");

        Instant dayStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("started_at", dayStart.toString());
        capture.put("ended_at", dayStart.plus(1, ChronoUnit.DAYS).toString());
        capture.put("acknowledged_channels", List.of("books5", "trades"));

        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("status", "CLEAN");
        integrity.put("anomaly_count", 0);
        integrity.put("raw_message_count", rawMessageCount);
        integrity.put("arrival_chain_sha256", HexFormat.of().formatHex(arrivalChain));
        integrity.put("midline_unreferenced_trade_count", 0);
        integrity.put("crossed_book_count", 0);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schema_version", "OKX_MICROSTRUCTURE_FORWARD_DAY_V3");
        output.put("bundle_type", "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY");
        output.put("authorization", AUTHORIZATION);
        output.put("source", source);
        output.put("day", day.toString());
        output.put("capture", capture);
        output.put("integrity", integrity);
        output.put("minutes", minuteOutput);
        return output;
    }

    static ParsedMessage inspect(ObjectMapper mapper, String raw) {
        return parse(mapper, raw, true);
    }

    private static ParsedMessage parse(ObjectMapper mapper, String raw, boolean strict) {
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("message must be a JSON object");
            }
            String event = text(root, "event");
            if (event != null) {
                if ("subscribe".equals(event)) {
                    String channel = text(root.path("arg"), "channel");
                    String instrument = text(root.path("arg"), "instId");
                    if (!INSTRUMENT.equals(instrument) || !EXPECTED_CHANNELS.contains(channel)) {
                        throw new IllegalArgumentException("invalid subscription acknowledgement");
                    }
                    return ParsedMessage.acknowledgement(channel);
                }
                if ("error".equals(event)) {
                    return ParsedMessage.exchangeError();
                }
                return ParsedMessage.otherEvent();
            }

            JsonNode argument = root.path("arg");
            String channel = text(argument, "channel");
            String instrument = text(argument, "instId");
            if (!INSTRUMENT.equals(instrument) || !EXPECTED_CHANNELS.contains(channel)) {
                throw new IllegalArgumentException("unexpected source binding");
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new IllegalArgumentException("data must be an array");
            }

            if ("trades".equals(channel)) {
                List<TradeRecord> trades = new ArrayList<>();
                int malformed = 0;
                for (JsonNode record : data) {
                    try {
                        trades.add(parseTrade(record));
                    } catch (RuntimeException error) {
                        if (strict) {
                            throw error;
                        }
                        malformed++;
                    }
                }
                return ParsedMessage.trades(
                        trades,
                        oneDay(trades.stream().map(TradeRecord::timestamp).toList()),
                        malformed);
            }

            List<BookRecord> books = new ArrayList<>();
            int malformed = 0;
            int crossed = 0;
            for (JsonNode record : data) {
                try {
                    books.add(parseBook(record));
                } catch (CrossedBookException error) {
                    if (strict) {
                        throw error;
                    }
                    crossed++;
                } catch (RuntimeException error) {
                    if (strict) {
                        throw error;
                    }
                    malformed++;
                }
            }
            return ParsedMessage.books(
                    books,
                    oneDay(books.stream().map(BookRecord::timestamp).toList()),
                    malformed,
                    crossed);
        } catch (CrossedBookException error) {
            throw error;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid OKX message", error);
        }
    }

    private void acceptTrade(TradeRecord trade) {
        if (lastTradeTimestamp != null && trade.timestamp() < lastTradeTimestamp) {
            tradeTimestampRegressionCount++;
        }
        if (trade.sequence() != null && lastTradeSequence != null && trade.sequence() < lastTradeSequence) {
            tradeSequenceRegressionCount++;
        }
        if (trade.tradeId() != null && lastTradeId != null && trade.tradeId() <= lastTradeId) {
            tradeIdNonIncreasingCount++;
        }
        lastTradeTimestamp = trade.timestamp();
        if (trade.sequence() != null) {
            lastTradeSequence = trade.sequence();
        }
        if (trade.tradeId() != null) {
            lastTradeId = trade.tradeId();
        }
        sourceCounts.merge(trade.source(), 1, Integer::sum);

        BigDecimal quoteNotional = trade.price().multiply(trade.size());
        MinuteAccumulator minute = minute(trade.timestamp());
        minute.tradeRecordCount++;
        minute.matchCount += trade.matchCount();
        minute.totalBaseQuantity = minute.totalBaseQuantity.add(trade.size());
        minute.totalQuoteNotional = minute.totalQuoteNotional.add(quoteNotional);
        if ("buy".equals(trade.side())) {
            minute.buyBaseQuantity = minute.buyBaseQuantity.add(trade.size());
            minute.buyQuoteNotional = minute.buyQuoteNotional.add(quoteNotional);
        } else {
            minute.sellBaseQuantity = minute.sellBaseQuantity.add(trade.size());
            minute.sellQuoteNotional = minute.sellQuoteNotional.add(quoteNotional);
        }
        if (minute.tradeOpenPrice == null) {
            minute.tradeOpenPrice = trade.price();
            minute.firstTradeAt = trade.timestamp();
        }
        minute.tradeHighPrice = max(minute.tradeHighPrice, trade.price());
        minute.tradeLowPrice = min(minute.tradeLowPrice, trade.price());
        minute.tradeClosePrice = trade.price();
        minute.lastTradeAt = trade.timestamp();
        retainOrResolveV3Trade(trade);
    }

    private void acceptBook(BookRecord book) {
        if (lastBookTimestamp != null && book.timestamp() < lastBookTimestamp) {
            bookTimestampRegressionCount++;
        }
        if (book.sequence() != null && lastBookSequence != null && book.sequence() < lastBookSequence) {
            bookSequenceRegressionCount++;
        }
        lastBookTimestamp = book.timestamp();
        if (book.sequence() != null) {
            lastBookSequence = book.sequence();
        }

        BigDecimal totalDepth = book.bidDepth().add(book.askDepth());
        BigDecimal imbalance = totalDepth.signum() == 0
                ? BigDecimal.ZERO
                : book.bidDepth().subtract(book.askDepth()).divide(totalDepth, 16, RoundingMode.HALF_UP);
        BigDecimal mid = book.bestBid().add(book.bestAsk()).divide(TWO, 16, RoundingMode.HALF_UP);
        BigDecimal spreadBps = book.bestAsk().subtract(book.bestBid())
                .divide(mid, 16, RoundingMode.HALF_UP)
                .multiply(TEN_THOUSAND);
        BigDecimal replenishment = lastBidDepthQuote == null
                ? BigDecimal.ZERO
                : book.bidDepth().subtract(lastBidDepthQuote).max(BigDecimal.ZERO);
        lastBidDepthQuote = book.bidDepth();

        MinuteAccumulator minute = minute(book.timestamp());
        minute.bookSampleCount++;
        minute.bidDepthQuoteSum = minute.bidDepthQuoteSum.add(book.bidDepth());
        minute.askDepthQuoteSum = minute.askDepthQuoteSum.add(book.askDepth());
        minute.imbalanceSum = minute.imbalanceSum.add(imbalance);
        minute.spreadBpsSum = minute.spreadBpsSum.add(spreadBps);
        minute.bidReplenishmentQuote = minute.bidReplenishmentQuote.add(replenishment);
        if (minute.midPriceStart == null) {
            minute.midPriceStart = mid;
            minute.firstBookAt = book.timestamp();
        }
        minute.midPriceHigh = max(minute.midPriceHigh, mid);
        minute.midPriceLow = min(minute.midPriceLow, mid);
        minute.midPriceEnd = mid;
        minute.lastBookAt = book.timestamp();

        lastBookMidPrice = mid;
        v3BookMidByTimestamp.put(book.timestamp(), mid);
        v3BookWatermarkTimestamp = v3BookWatermarkTimestamp == null
                ? book.timestamp()
                : Math.max(v3BookWatermarkTimestamp, book.timestamp());
        if (!unresolvedV3TradeOverflow) {
            resolveV3TradesBefore(book.timestamp());
            pruneV3BookReferences();
        }
    }

    private void retainOrResolveV3Trade(TradeRecord trade) {
        if (unresolvedV3TradeOverflow) {
            return;
        }
        if (v3BookWatermarkTimestamp != null
                && v3BookWatermarkTimestamp > trade.timestamp()) {
            classifyV3Trade(trade);
            pruneV3BookReferences();
            return;
        }
        if (unresolvedV3TradeCount == MAX_UNRESOLVED_TRADES) {
            unresolvedV3TradeOverflow = true;
            return;
        }
        unresolvedV3Trades
                .computeIfAbsent(trade.timestamp(), ignored -> new ArrayList<>())
                .add(trade);
        unresolvedV3TradeCount++;
        pruneV3BookReferences();
    }

    private void resolveV3TradesBefore(long exclusiveBookTimestamp) {
        List<Long> readyTimestamps = new ArrayList<>(
                unresolvedV3Trades.headMap(exclusiveBookTimestamp, false).keySet());
        for (Long timestamp : readyTimestamps) {
            List<TradeRecord> ready = unresolvedV3Trades.remove(timestamp);
            unresolvedV3TradeCount -= ready.size();
            ready.forEach(this::classifyV3Trade);
        }
    }

    private void resolveAllV3Trades() {
        if (unresolvedV3TradeOverflow) {
            return;
        }
        List<List<TradeRecord>> pending = new ArrayList<>(unresolvedV3Trades.values());
        unresolvedV3Trades.clear();
        unresolvedV3TradeCount = 0;
        pending.forEach(trades -> trades.forEach(this::classifyV3Trade));
        pruneV3BookReferences();
    }

    private void classifyV3Trade(TradeRecord trade) {
        Map.Entry<Long, BigDecimal> reference = v3BookMidByTimestamp.floorEntry(trade.timestamp());
        if (reference == null) {
            midlineUnreferencedTradeCount++;
            return;
        }
        BigDecimal quoteNotional = trade.price().multiply(trade.size());
        MinuteAccumulator minute = minute(trade.timestamp());
        minute.midlineReferenceCount++;
        int priceToMid = trade.price().compareTo(reference.getValue());
        if ("buy".equals(trade.side()) && priceToMid > 0) {
            minute.aboveMidBuyQuoteNotional =
                    minute.aboveMidBuyQuoteNotional.add(quoteNotional);
        } else if ("sell".equals(trade.side()) && priceToMid < 0) {
            minute.belowMidSellQuoteNotional =
                    minute.belowMidSellQuoteNotional.add(quoteNotional);
        } else {
            minute.midlineOtherQuoteNotional =
                    minute.midlineOtherQuoteNotional.add(quoteNotional);
        }
    }

    private void pruneV3BookReferences() {
        if (lastTradeTimestamp == null || v3BookMidByTimestamp.size() < 2) {
            return;
        }
        long earliestNeeded = lastTradeTimestamp;
        if (!unresolvedV3Trades.isEmpty()) {
            earliestNeeded = Math.min(earliestNeeded, unresolvedV3Trades.firstKey());
        }
        Map.Entry<Long, BigDecimal> anchor = v3BookMidByTimestamp.floorEntry(earliestNeeded);
        if (anchor != null) {
            v3BookMidByTimestamp.headMap(anchor.getKey(), false).clear();
        }
    }

    private MinuteAccumulator minute(long timestamp) {
        Instant minute = Instant.ofEpochMilli(timestamp).truncatedTo(ChronoUnit.MINUTES);
        return minutes.computeIfAbsent(minute, ignored -> new MinuteAccumulator());
    }

    private boolean isExactFullUtcDay(LocalDate requiredDay) {
        if (minutes.size() != 1_440) {
            return false;
        }
        Instant first = minutes.firstKey();
        Instant expectedFirst = first.truncatedTo(ChronoUnit.DAYS);
        if (requiredDay != null) {
            expectedFirst = requiredDay.atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (!first.equals(expectedFirst)
                || !minutes.lastKey().equals(expectedFirst.plus(1_439, ChronoUnit.MINUTES))) {
            return false;
        }
        Instant expected = expectedFirst;
        for (Map.Entry<Instant, MinuteAccumulator> entry : minutes.entrySet()) {
            if (!entry.getKey().equals(expected)
                    || entry.getValue().tradeRecordCount == 0
                    || entry.getValue().bookSampleCount == 0) {
                return false;
            }
            expected = expected.plus(1, ChronoUnit.MINUTES);
        }
        return true;
    }

    private static TradeRecord parseTrade(JsonNode record) {
        BigDecimal price = positiveDecimal(record, "px");
        BigDecimal size = positiveDecimal(record, "sz");
        long timestamp = positiveLong(record, "ts");
        String side = requiredText(record, "side");
        if (!"buy".equals(side) && !"sell".equals(side)) {
            throw new IllegalArgumentException("invalid trade side");
        }
        int matchCount = optionalPositiveInt(record, "count", 1);
        Long sequence = optionalLong(record, "seqId");
        Long tradeId = optionalLong(record, "tradeId");
        String source = text(record, "source");
        if (source == null || source.isBlank()) {
            source = "unknown";
        }
        return new TradeRecord(price, size, timestamp, side, matchCount, sequence, tradeId, source);
    }

    private static BookRecord parseBook(JsonNode record) {
        long timestamp = positiveLong(record, "ts");
        Long sequence = optionalLong(record, "seqId");
        BigDecimal bidDepth = depthQuote(record.path("bids"));
        BigDecimal askDepth = depthQuote(record.path("asks"));
        BigDecimal bestBid = bestPrice(record.path("bids"));
        BigDecimal bestAsk = bestPrice(record.path("asks"));
        if (bestAsk.compareTo(bestBid) <= 0) {
            throw new CrossedBookException();
        }
        return new BookRecord(timestamp, sequence, bidDepth, askDepth, bestBid, bestAsk);
    }

    private static LocalDate oneDay(List<Long> timestamps) {
        if (timestamps.isEmpty()) {
            return null;
        }
        LocalDate day = Instant.ofEpochMilli(timestamps.getFirst()).atZone(ZoneOffset.UTC).toLocalDate();
        for (long timestamp : timestamps) {
            LocalDate candidate = Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate();
            if (!candidate.equals(day)) {
                throw new IllegalArgumentException("one message spans UTC days");
            }
        }
        return day;
    }

    private static BigDecimal depthQuote(JsonNode levels) {
        if (!levels.isArray() || levels.isEmpty() || levels.size() > 5) {
            throw new IllegalArgumentException("invalid books5 levels");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode level : levels) {
            if (!level.isArray() || level.size() < 2) {
                throw new IllegalArgumentException("invalid book level");
            }
            BigDecimal price = new BigDecimal(level.get(0).asText());
            BigDecimal size = new BigDecimal(level.get(1).asText());
            if (price.signum() <= 0 || size.signum() < 0) {
                throw new IllegalArgumentException("invalid book price/size");
            }
            total = total.add(price.multiply(size));
        }
        return total;
    }

    private static BigDecimal bestPrice(JsonNode levels) {
        if (!levels.isArray() || levels.isEmpty() || !levels.get(0).isArray()) {
            throw new IllegalArgumentException("missing best price");
        }
        BigDecimal value = new BigDecimal(levels.get(0).get(0).asText());
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("invalid best price");
        }
        return value;
    }

    private static BigDecimal positiveDecimal(JsonNode node, String field) {
        BigDecimal value = new BigDecimal(requiredText(node, field));
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static long positiveLong(JsonNode node, String field) {
        long value = Long.parseLong(requiredText(node, field));
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static Long optionalLong(JsonNode node, String field) {
        String value = text(node, field);
        return value == null || value.isBlank() ? null : Long.parseLong(value);
    }

    private static int optionalPositiveInt(JsonNode node, String field, int defaultValue) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return parsed;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static byte[] chain(byte[] previous, byte[] raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(previous);
            digest.update(raw);
            return digest.digest();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static BigDecimal max(BigDecimal current, BigDecimal candidate) {
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }

    private static BigDecimal min(BigDecimal current, BigDecimal candidate) {
        return current == null || candidate.compareTo(current) < 0 ? candidate : current;
    }

    private static String decimal(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    private static String timestamp(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis).toString();
    }

    private static final class MinuteAccumulator {
        private long tradeRecordCount;
        private long matchCount;
        private BigDecimal buyBaseQuantity = BigDecimal.ZERO;
        private BigDecimal sellBaseQuantity = BigDecimal.ZERO;
        private BigDecimal buyQuoteNotional = BigDecimal.ZERO;
        private BigDecimal sellQuoteNotional = BigDecimal.ZERO;
        private BigDecimal totalBaseQuantity = BigDecimal.ZERO;
        private BigDecimal totalQuoteNotional = BigDecimal.ZERO;
        private long midlineReferenceCount;
        private BigDecimal aboveMidBuyQuoteNotional = BigDecimal.ZERO;
        private BigDecimal belowMidSellQuoteNotional = BigDecimal.ZERO;
        private BigDecimal midlineOtherQuoteNotional = BigDecimal.ZERO;
        private BigDecimal tradeOpenPrice;
        private BigDecimal tradeHighPrice;
        private BigDecimal tradeLowPrice;
        private BigDecimal tradeClosePrice;
        private Long firstTradeAt;
        private Long lastTradeAt;
        private long bookSampleCount;
        private BigDecimal bidDepthQuoteSum = BigDecimal.ZERO;
        private BigDecimal askDepthQuoteSum = BigDecimal.ZERO;
        private BigDecimal imbalanceSum = BigDecimal.ZERO;
        private BigDecimal spreadBpsSum = BigDecimal.ZERO;
        private BigDecimal bidReplenishmentQuote = BigDecimal.ZERO;
        private BigDecimal midPriceStart;
        private BigDecimal midPriceHigh;
        private BigDecimal midPriceLow;
        private BigDecimal midPriceEnd;
        private Long firstBookAt;
        private Long lastBookAt;

        private Map<String, Object> v1Output(Instant minute) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("minute", minute.toString());
            value.put("trade_record_count", tradeRecordCount);
            value.put("match_count", matchCount);
            value.put("buy_base_quantity", decimal(buyBaseQuantity));
            value.put("sell_base_quantity", decimal(sellBaseQuantity));
            value.put("buy_quote_notional", decimal(buyQuoteNotional));
            value.put("sell_quote_notional", decimal(sellQuoteNotional));
            value.put("net_taker_quote_notional", decimal(buyQuoteNotional.subtract(sellQuoteNotional)));
            value.put("book_sample_count", bookSampleCount);
            value.put("average_top5_bid_quote_depth", average(bidDepthQuoteSum, bookSampleCount));
            value.put("average_top5_ask_quote_depth", average(askDepthQuoteSum, bookSampleCount));
            value.put("average_book_imbalance", average(imbalanceSum, bookSampleCount));
            value.put("average_spread_bps", average(spreadBpsSum, bookSampleCount));
            value.put("bid_replenishment_quote_proxy", decimal(bidReplenishmentQuote));
            value.put("mid_price_start", decimal(midPriceStart));
            value.put("mid_price_end", decimal(midPriceEnd));
            return value;
        }

        private Map<String, Object> v2Output(Instant minute) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("minute", minute.toString());
            value.put("trade_record_count", tradeRecordCount);
            value.put("match_count", matchCount);
            value.put("buy_quote_notional", decimal(buyQuoteNotional));
            value.put("sell_quote_notional", decimal(sellQuoteNotional));
            value.put("total_quote_notional", decimal(totalQuoteNotional));
            value.put("net_taker_quote_notional", decimal(buyQuoteNotional.subtract(sellQuoteNotional)));
            value.put("trade_open_price", decimal(tradeOpenPrice));
            value.put("trade_high_price", decimal(tradeHighPrice));
            value.put("trade_low_price", decimal(tradeLowPrice));
            value.put("trade_close_price", decimal(tradeClosePrice));
            value.put("trade_vwap_price", totalBaseQuantity.signum() == 0
                    ? null
                    : decimal(totalQuoteNotional.divide(totalBaseQuantity, 12, RoundingMode.HALF_UP)));
            value.put("first_trade_at", timestamp(firstTradeAt));
            value.put("last_trade_at", timestamp(lastTradeAt));
            value.put("book_sample_count", bookSampleCount);
            value.put("average_top5_bid_quote_depth", average(bidDepthQuoteSum, bookSampleCount));
            value.put("average_top5_ask_quote_depth", average(askDepthQuoteSum, bookSampleCount));
            value.put("average_book_imbalance", average(imbalanceSum, bookSampleCount));
            value.put("average_spread_bps", average(spreadBpsSum, bookSampleCount));
            value.put("bid_replenishment_quote_proxy", decimal(bidReplenishmentQuote));
            value.put("mid_price_start", decimal(midPriceStart));
            value.put("mid_price_high", decimal(midPriceHigh));
            value.put("mid_price_low", decimal(midPriceLow));
            value.put("mid_price_end", decimal(midPriceEnd));
            value.put("first_book_at", timestamp(firstBookAt));
            value.put("last_book_at", timestamp(lastBookAt));
            return value;
        }

        private Map<String, Object> v3Output(Instant minute) {
            Map<String, Object> value = v2Output(minute);
            value.put("midline_reference_count", midlineReferenceCount);
            value.put("above_mid_buy_quote_notional", decimal(aboveMidBuyQuoteNotional));
            value.put("below_mid_sell_quote_notional", decimal(belowMidSellQuoteNotional));
            value.put("midline_other_quote_notional", decimal(midlineOtherQuoteNotional));
            return value;
        }

        private boolean v3BucketsComplete() {
            return midlineReferenceCount == tradeRecordCount
                    && totalQuoteNotional.compareTo(
                            aboveMidBuyQuoteNotional
                                    .add(belowMidSellQuoteNotional)
                                    .add(midlineOtherQuoteNotional)) == 0;
        }

        private static String average(BigDecimal sum, long count) {
            return count == 0
                    ? null
                    : decimal(sum.divide(BigDecimal.valueOf(count), 12, RoundingMode.HALF_UP));
        }
    }

    enum MessageKind {
        ACKNOWLEDGEMENT,
        EXCHANGE_ERROR,
        OTHER_EVENT,
        TRADES,
        BOOKS5
    }

    record ParsedMessage(
            MessageKind kind,
            String channel,
            LocalDate day,
            List<TradeRecord> trades,
            List<BookRecord> books,
            int malformedRecords,
            int crossedBooks) {

        Instant latestDataInstant() {
            long latest = Long.MIN_VALUE;
            for (TradeRecord trade : trades) {
                latest = Math.max(latest, trade.timestamp());
            }
            for (BookRecord book : books) {
                latest = Math.max(latest, book.timestamp());
            }
            return latest == Long.MIN_VALUE ? null : Instant.ofEpochMilli(latest);
        }

        static ParsedMessage acknowledgement(String channel) {
            return new ParsedMessage(
                    MessageKind.ACKNOWLEDGEMENT, channel, null, List.of(), List.of(), 0, 0);
        }

        static ParsedMessage exchangeError() {
            return new ParsedMessage(MessageKind.EXCHANGE_ERROR, null, null, List.of(), List.of(), 0, 0);
        }

        static ParsedMessage otherEvent() {
            return new ParsedMessage(MessageKind.OTHER_EVENT, null, null, List.of(), List.of(), 0, 0);
        }

        static ParsedMessage trades(List<TradeRecord> trades, LocalDate day, int malformedRecords) {
            return new ParsedMessage(
                    MessageKind.TRADES,
                    "trades",
                    day,
                    List.copyOf(trades),
                    List.of(),
                    malformedRecords,
                    0);
        }

        static ParsedMessage books(
                List<BookRecord> books,
                LocalDate day,
                int malformedRecords,
                int crossedBooks) {
            return new ParsedMessage(
                    MessageKind.BOOKS5,
                    "books5",
                    day,
                    List.of(),
                    List.copyOf(books),
                    malformedRecords,
                    crossedBooks);
        }
    }

    record Continuity(
            Long lastTradeTimestamp,
            Long lastBookTimestamp,
            Long lastTradeSequence,
            Long lastBookSequence,
            Long lastTradeId,
            BigDecimal lastBidDepthQuote,
            BigDecimal lastBookMidPrice) {

        static Continuity empty() {
            return new Continuity(null, null, null, null, null, null, null);
        }
    }

    private record TradeRecord(
            BigDecimal price,
            BigDecimal size,
            long timestamp,
            String side,
            int matchCount,
            Long sequence,
            Long tradeId,
            String source) {
    }

    private record BookRecord(
            long timestamp,
            Long sequence,
            BigDecimal bidDepth,
            BigDecimal askDepth,
            BigDecimal bestBid,
            BigDecimal bestAsk) {
    }

    static final class CrossedBookException extends IllegalArgumentException {
        private CrossedBookException() {
            super("crossed books5 snapshot");
        }
    }
}
