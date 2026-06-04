package com.agora.service;

import com.agora.enums.system.OAuthProvider;
import com.agora.model.User;
import com.agora.model.UserOAuthBinding;
import com.agora.repository.system.UserOAuthBindingRepository;
import com.agora.repository.system.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * #370 — Service wrapper for OAuth-binding + user lookup operations used by
 * {@code WalletConnectWebViewController}.
 *
 * <p>Controller previously injected {@link UserRepository} and
 * {@link UserOAuthBindingRepository} directly (arch boundary violation —
 * controllers should not import {@code com.agora.repository.*}).
 */
@Service
@RequiredArgsConstructor
public class UserOAuthService {

    private final UserRepository userRepository;
    private final UserOAuthBindingRepository oauthBindingRepository;

    @Transactional(readOnly = true)
    public Optional<UserOAuthBinding> findBinding(OAuthProvider provider, String providerId) {
        return oauthBindingRepository.findByOauthProviderAndOauthProviderId(provider, providerId);
    }

    @Transactional(readOnly = true)
    public Optional<User> findUserById(Long userId) {
        return userRepository.findById(userId);
    }

    @Transactional(readOnly = true)
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Transactional(readOnly = true)
    public boolean hasAnyBinding(Long userId) {
        return oauthBindingRepository.existsByUserId(userId);
    }

    @Transactional
    public User saveUser(User user) {
        return userRepository.save(user);
    }

    @Transactional
    public UserOAuthBinding saveBinding(UserOAuthBinding binding) {
        return oauthBindingRepository.save(binding);
    }
}
