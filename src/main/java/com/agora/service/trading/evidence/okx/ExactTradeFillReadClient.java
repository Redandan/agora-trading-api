package com.agora.service.trading.evidence.okx;

import com.agora.service.trading.evidence.okx.ExactTradeFillModels.RawPage;

public interface ExactTradeFillReadClient {
    RawPage getPage(String instrumentId, String instrumentType, int limit, String afterCursor,
                    String accountRefHash);
}
