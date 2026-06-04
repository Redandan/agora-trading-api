package com.agora.repository.system;

import com.agora.enums.system.OAuthProvider;
import com.agora.model.UserOAuthBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserOAuthBindingRepository extends JpaRepository<UserOAuthBinding, Long> {
    /**
     * 根据OAuth提供商和提供商ID查找绑定
     */
    Optional<UserOAuthBinding> findByOauthProviderAndOauthProviderId(OAuthProvider provider, String providerId);

    /**
     * 根据用户ID查找所有绑定
     */
    List<UserOAuthBinding> findByUserId(Long userId);

    /**
     * 根据用户ID和OAuth提供商查找绑定
     */
    Optional<UserOAuthBinding> findByUserIdAndOauthProvider(Long userId, OAuthProvider provider);

    /**
     * 检查是否存在绑定
     */
    boolean existsByOauthProviderAndOauthProviderId(OAuthProvider provider, String providerId);

    /**
     * 检查用户是否已绑定该提供商
     */
    boolean existsByUserIdAndOauthProvider(Long userId, OAuthProvider provider);

    /**
     * 查找用户的主要绑定账号
     */
    Optional<UserOAuthBinding> findByUserIdAndIsPrimaryTrue(Long userId);

    /**
     * 检查用户是否存在绑定
     */
    boolean existsByUserId(Long userId);

    /**
     * 根据 Telegram 用户ID查找绑定（用于对賭功能）
     */
    Optional<UserOAuthBinding> findByTelegramUserId(String telegramUserId);
}

