package com.agora.repository.system;

import com.agora.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /** 取所有 admin user(role='ADMIN'),供系統廣播用 */
    List<User> findByRole(String role);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
} 
