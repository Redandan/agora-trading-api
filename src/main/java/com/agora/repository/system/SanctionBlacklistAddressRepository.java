package com.agora.repository.system;

import com.agora.model.SanctionBlacklistAddress;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SanctionBlacklistAddressRepository extends JpaRepository<SanctionBlacklistAddress, Long> {
    Optional<SanctionBlacklistAddress> findByAddressAndChain(String address, String chain);
    Page<SanctionBlacklistAddress> findAllByOrderByAddedAtDesc(Pageable pageable);
    boolean existsByAddressAndChain(String address, String chain);
}
