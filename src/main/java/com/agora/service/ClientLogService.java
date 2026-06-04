package com.agora.service;

import com.agora.dto.ClientLogDto;
import com.agora.model.ClientLog;
import com.agora.repository.system.ClientLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClientLogService {

    private final ClientLogRepository clientLogRepository;

    @Async
    @Transactional
    public void saveLogs(List<ClientLogDto> logDtos) {
        try {
            List<ClientLog> clientLogs = logDtos.stream().map(this::convertToEntity).collect(Collectors.toList());
            clientLogRepository.saveAll(clientLogs);
            log.debug("Saved {} client logs asynchronously.", clientLogs.size());
        } catch (Exception e) {
            log.error("Failed to save client logs", e);
        }
    }

    private ClientLog convertToEntity(ClientLogDto dto) {
        ClientLog entity = new ClientLog();
        entity.setLevel(dto.getLevel());
        entity.setMessage(dto.getMessage());
        entity.setUrl(dto.getUrl());
        entity.setUserAgent(dto.getUserAgent());
        entity.setDevice(dto.getDevice());
        entity.setUserId(dto.getUserId());
        entity.setUserIp(dto.getUserIp());
        entity.setTimestamp(dto.getTimestamp());
        
        // 將 details 轉換為 JSON 字符串
        if (dto.getDetails() != null) {
            try {
                entity.setDetails(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(dto.getDetails()));
            } catch (Exception e) {
                log.error("Error converting log details to JSON", e);
                entity.setDetails(null);
            }
        }
        
        return entity;
    }

    /**
     * 查詢用戶的所有日誌
     */
    public Page<ClientLog> getUserLogs(Long userId, Pageable pageable) {
        try {
            return clientLogRepository.findByUserIdOrderByTimestampDesc(userId, pageable);
        } catch (Exception e) {
            log.error("Error getting user logs for user: {}", userId, e);
            return Page.empty();
        }
    }
}
