package com.zhiyun.repo;

import com.zhiyun.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepo extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByEmailAndIdNot(String email, Long id);
}
