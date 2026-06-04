package com.agora.service;

import com.agora.exception.BusinessException;
import com.agora.model.SanctionBlacklistAddress;
import com.agora.repository.system.SanctionBlacklistAddressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SanctionBlacklistService {

    private final SanctionBlacklistAddressRepository repository;

    public void checkAddress(String address, String chain) {
        repository.findByAddressAndChain(address, chain).ifPresent(entry -> {
            if ("BLOCK".equals(entry.getSeverity())) {
                throw new BusinessException("提款地址已被列入制裁黑名單，無法提款");
            }
            log.warn("Sanctioned address WARN: address={} chain={} source={}", address, chain, entry.getSource());
        });
    }

    @Transactional
    public SanctionBlacklistAddress addAddress(String address, String chain, String source,
                                               String severity, String reason, Long adminId) {
        if (repository.existsByAddressAndChain(address, chain)) {
            throw new BusinessException("該地址已在黑名單中");
        }
        SanctionBlacklistAddress entry = new SanctionBlacklistAddress();
        entry.setAddress(address.toLowerCase());
        entry.setChain(chain.toUpperCase());
        entry.setSource(source.toUpperCase());
        entry.setSeverity(severity != null ? severity.toUpperCase() : "BLOCK");
        entry.setReason(reason);
        entry.setAddedByAdminId(adminId);
        return repository.save(entry);
    }

    @Transactional
    public void removeAddress(Long id) {
        if (!repository.existsById(id)) throw new BusinessException("黑名單記錄不存在");
        repository.deleteById(id);
    }

    public Page<SanctionBlacklistAddress> list(Pageable pageable) {
        return repository.findAllByOrderByAddedAtDesc(pageable);
    }
}
