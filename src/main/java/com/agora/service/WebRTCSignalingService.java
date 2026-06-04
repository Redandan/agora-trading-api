package com.agora.service;

import com.agora.dto.*;
import com.agora.enums.system.NotifyEventTypeEnum;
import com.agora.exception.BusinessException;
import com.agora.model.User;
import com.agora.util.BusinessIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebRTCSignalingService {

    private final SSEService sseService;

    /**
     * 發送 WebRTC Offer
     */
    public WebRTCOfferResponseDto sendOffer(WebRTCOfferDto offerDto, User currentUser) {
        try {
            // 驗證用戶權限
            validateUserPermission(offerDto.getFromUserId(), offerDto.getToUserId());
            
            
            // 檢查目標用戶是否在線
            if (!sseService.isUserConnected(offerDto.getToUserId().toString())) {
                throw new BusinessException("目標用戶不在線上。");
            }
            
            // 檢查用戶是否已有進行中的通話
            String existingCallId = sseService.getUserCallId(offerDto.getFromUserId().toString());
            if (existingCallId != null && !existingCallId.trim().isEmpty()) {
                throw new BusinessException("用戶正在通話中，無法發起新通話。當前通話 ID: " + existingCallId);
            }
            
            // 生成新的 call ID
            String callId = generateCallId();
            
            // 存儲用戶的 callId 到 SSE 服務（發起方）
            sseService.setUserCallId(offerDto.getFromUserId().toString(), callId);
            
            // 存儲用戶的 callId 到 SSE 服務（接收方）
            sseService.setUserCallId(offerDto.getToUserId().toString(), callId);
            
            log.info("Generated call ID: {} for offer from user {} to user {}", 
                    callId, offerDto.getFromUserId(), offerDto.getToUserId());
            
            // 準備 Offer 事件資料
            WebRTCOfferEventDto offerEvent = WebRTCOfferEventDto.builder()
                    .type(NotifyEventTypeEnum.WEBRTC_OFFER.name())
                    .callId(callId)
                    .fromUserId(offerDto.getFromUserId())
                    .toUserId(offerDto.getToUserId())
                    .sdp(offerDto.getSdp())
                    .callType(offerDto.getCallType())
                    .audioEnabled(offerDto.getAudioEnabled())
                    .videoEnabled(offerDto.getVideoEnabled())
                    .timestamp(offerDto.getTimestamp())
                    .build();
            
            // 透過 SSE 發送給接收方
            sseService.sendEventToUser(offerDto.getToUserId().toString(), offerEvent);
            
            // 發送通話發起事件給發起方
            WebRTCCallInitiatedDto initiatedEvent = WebRTCCallInitiatedDto.builder()
                    .callId(callId)
                    .fromUserId(offerDto.getFromUserId())
                    .toUserId(offerDto.getToUserId())
                    .type(NotifyEventTypeEnum.WEBRTC_CALL_INITIATED.name())
                    .timestamp(LocalDateTime.now())
                    .build();
            
            sseService.sendEventToUser(offerDto.getFromUserId().toString(), initiatedEvent);
            
            log.info("WebRTC offer sent from user {} to user {} for call {}", 
                    offerDto.getFromUserId(), offerDto.getToUserId(), callId);
            
            // 生成成功響應
            return WebRTCOfferResponseDto.builder()
                    .success(true)
                    .message("Offer sent successfully")
                    .callId(callId)
                    .toUserId(offerDto.getToUserId())
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();
            
        } catch (Exception e) {
            log.error("Error sending WebRTC offer: {}", e.getMessage(), e);
            
            // 生成錯誤響應
            return WebRTCOfferResponseDto.builder()
                    .success(false)
                    .message("Failed to send offer: " + e.getMessage())
                    .errorCode("OFFER_SEND_FAILED")
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();
        }
    }

    /**
     * 發送 WebRTC Answer
     */
    public void sendAnswer(WebRTCAnswerDto answerDto) {
        try {
            // 驗證用戶權限
            validateUserPermission(answerDto.getFromUserId(), answerDto.getToUserId());
            
            // 準備 Answer 事件資料
            WebRTCAnswerEventDto answerEvent = WebRTCAnswerEventDto.builder()
                    .type(NotifyEventTypeEnum.WEBRTC_ANSWER.name())
                    .callId(answerDto.getCallId())
                    .fromUserId(answerDto.getFromUserId())
                    .toUserId(answerDto.getToUserId())
                    .sdp(answerDto.getSdp())
                    .accepted(answerDto.getAccepted())
                    .timestamp(answerDto.getTimestamp())
                    .build();
            
            // 透過 SSE 發送給發起方
            sseService.sendEventToUser(answerDto.getToUserId().toString(), answerEvent);
            
            // 根據是否接受發送相應事件
            NotifyEventTypeEnum statusEvent = answerDto.getAccepted() ? 
                NotifyEventTypeEnum.WEBRTC_CALL_ACCEPTED : 
                NotifyEventTypeEnum.WEBRTC_CALL_REJECTED;
            
            WebRTCCallStatusEventDto statusEventData = WebRTCCallStatusEventDto.builder()
                    .callId(answerDto.getCallId())
                    .userId(answerDto.getFromUserId())
                    .status(statusEvent.name())
                    .timestamp(LocalDateTime.now())
                    .build();
            
            sseService.sendEventToUser(answerDto.getToUserId().toString(), statusEventData);
            
            log.info("WebRTC answer sent from user {} to user {} for call {}, accepted: {}", 
                    answerDto.getFromUserId(), answerDto.getToUserId(), 
                    answerDto.getCallId(), answerDto.getAccepted());
            
        } catch (Exception e) {
            log.error("Error sending WebRTC answer: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send WebRTC answer: " + e.getMessage(), e);
        }
    }

    /**
     * 發送 WebRTC ICE Candidate
     */
    public void sendIceCandidate(WebRTCIceCandidateDto candidateDto) {
        try {
            // 驗證用戶權限
            validateUserPermission(candidateDto.getFromUserId(), candidateDto.getToUserId());
            
            // 準備 ICE Candidate 事件資料
            WebRTCIceCandidateEventDto candidateEvent = WebRTCIceCandidateEventDto.builder()
                    .type(NotifyEventTypeEnum.WEBRTC_ICE_CANDIDATE.name())
                    .callId(candidateDto.getCallId())
                    .fromUserId(candidateDto.getFromUserId())
                    .toUserId(candidateDto.getToUserId())
                    .candidate(candidateDto.getCandidate())
                    .sdpMid(candidateDto.getSdpMid())
                    .sdpMLineIndex(candidateDto.getSdpMLineIndex())
                    .timestamp(candidateDto.getTimestamp())
                    .build();
            
            // 透過 SSE 發送給對方
            sseService.sendEventToUser(candidateDto.getToUserId().toString(), candidateEvent);
            
            log.debug("WebRTC ICE candidate sent from user {} to user {} for call {}", 
                    candidateDto.getFromUserId(), candidateDto.getToUserId(), candidateDto.getCallId());
            
        } catch (Exception e) {
            log.error("Error sending WebRTC ICE candidate: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to send WebRTC ICE candidate: " + e.getMessage(), e);
        }
    }


    /**
     * 掛斷通話
     */
    public WebRTCHangupResponseDto hangupCall(WebRTCHangupDto hangupDto, Long fromUserId) {
        try {
            // 驗證用戶權限
            validateUserPermission(fromUserId, hangupDto.getToUserId());
            
            // 獲取用戶的 callId（在清除之前）
            String callId = sseService.getUserCallId(fromUserId.toString());
            
            // 清除用戶的 callId（發起方）
            sseService.clearUserCallId(fromUserId.toString());
            
            // 清除用戶的 callId（接收方）
            sseService.clearUserCallId(hangupDto.getToUserId().toString());
            
            log.info("Cleared callId for users: {} and {}", fromUserId, hangupDto.getToUserId());
            
            // 創建事件資料
            WebRTCHangupEventDto eventData = WebRTCHangupEventDto.builder()
                    .callId(callId)
                    .fromUserId(fromUserId)
                    .toUserId(hangupDto.getToUserId())
                    .reason(hangupDto.getReason())
                    .duration(hangupDto.getDuration())
                    .timestamp(LocalDateTime.now())
                    .build();

            // 發送 SSE 事件通知對方
            sseService.sendEventToUser(hangupDto.getToUserId().toString(), eventData);

            log.info("通話掛斷: callId={}, fromUserId={}, toUserId={}, reason={}", 
                    callId, fromUserId, hangupDto.getToUserId(), hangupDto.getReason());

            return WebRTCHangupResponseDto.builder()
                    .success(true)
                    .message("通話掛斷成功")
                    .callId(callId)
                    .reason(hangupDto.getReason())
                    .duration(hangupDto.getDuration())
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();

        } catch (Exception e) {
            log.error("掛斷通話失敗: {}", e.getMessage(), e);
            return WebRTCHangupResponseDto.builder()
                    .success(false)
                    .message("掛斷通話失敗: " + e.getMessage())
                    .reason(hangupDto.getReason())
                    .duration(hangupDto.getDuration())
                    .timestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                    .build();
        }
    }


    /**
     * 生成唯一的 call ID
     */
    private String generateCallId() {
        // 使用 BusinessIdGenerator 生成格式：CALL + 時間戳 + 序列號 + 隨機字符串
        return BusinessIdGenerator.generateId("CALL");
    }

    /**
     * 驗證用戶權限
     */
    private void validateUserPermission(Long fromUserId, Long toUserId) {
        if (fromUserId == null || toUserId == null) {
            throw new BusinessException("用戶ID不能為空");
        }
        
        if (fromUserId.equals(toUserId)) {
            throw new BusinessException("不能向自己發送信令");
        }
        
        // 這裡可以添加更多權限驗證邏輯
        // 例如：檢查用戶是否存在、是否被屏蔽等
    }
}
