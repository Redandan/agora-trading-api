package com.agora.service.trading.evidence.okx;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

/**
 * Transport boundary for OKX evidence reads. Implementations may only issue GET requests to this
 * allowlist. Authentication material is intentionally absent from requests, responses and logs.
 * This package does not provide a network implementation.
 */
public interface OkxEvidenceReadClient {

    ReadPage get(ReadRequest request);

    enum Access {
        PUBLIC,
        AUTHENTICATED_READ_ONLY
    }

    enum Endpoint {
        EXECUTABLE_BOOKS("/api/v5/market/books", Access.PUBLIC),
        FILL_HISTORY("/api/v5/trade/fills-history", Access.AUTHENTICATED_READ_ONLY),
        FUNDING_BILLS("/api/v5/account/bills", Access.AUTHENTICATED_READ_ONLY),
        ACCOUNT_BALANCE("/api/v5/account/balance", Access.AUTHENTICATED_READ_ONLY);

        private final String path;
        private final Access access;

        Endpoint(String path, Access access) {
            this.path = path;
            this.access = access;
        }

        public String path() {
            return path;
        }

        public Access access() {
            return access;
        }
    }

    record ReadRequest(Endpoint endpoint, Map<String, String> query, String cursor) {
        public ReadRequest {
            query = query == null ? Map.of() : Map.copyOf(query);
        }
    }

    record ReadPage(JsonNode body,
                    Instant availableAt,
                    Instant observedAt,
                    String nextCursor,
                    String pageKey,
                    boolean pageComplete) {
    }
}
