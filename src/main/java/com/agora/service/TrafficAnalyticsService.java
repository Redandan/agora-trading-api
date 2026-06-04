package com.agora.service;

import com.agora.dto.analytics.RegistrationOverviewResponse;
import com.agora.dto.analytics.SlotOverviewResponse;

import java.time.LocalDateTime;

public interface TrafficAnalyticsService {

    /**
     * 獲取指定時間範圍內的註冊流量概覽。
     * 包括總數、今日/昨日/上週同日對比、每日趨勢、渠道分佈、推廣碼排行。
     */
    RegistrationOverviewResponse getRegistrationOverview(LocalDateTime start, LocalDateTime end, int topPromos);

    /**
     * 獲取指定時間範圍內的 Slot 遊戲流量概覽。
     * 包括局數、活躍玩家、下注/派彩/毛利、RTP、每日趨勢、每小時分佈。
     *
     * @param gameId 遊戲 ID，null 代表全部遊戲
     */
    SlotOverviewResponse getSlotOverview(LocalDateTime start, LocalDateTime end, String gameId);
}
