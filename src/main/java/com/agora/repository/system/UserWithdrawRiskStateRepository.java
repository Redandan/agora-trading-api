package com.agora.repository.system;

import com.agora.model.UserWithdrawRiskState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserWithdrawRiskStateRepository extends JpaRepository<UserWithdrawRiskState, Long> {
    Optional<UserWithdrawRiskState> findByUserId(Long userId);
}
