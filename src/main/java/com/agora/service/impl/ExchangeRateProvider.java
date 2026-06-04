package com.agora.service.impl;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Fetches USDT exchange rates from a single external source. */
public interface ExchangeRateProvider {
    String name();
    /** Returns currency-code → USDT rate map. Throws IOException on failure. */
    Map<String, BigDecimal> fetchRates(List<String> supportedCurrencies) throws IOException;
}
