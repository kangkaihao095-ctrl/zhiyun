package com.zhiyun.repo;

import com.zhiyun.domain.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlanRepo extends JpaRepository<Plan, Long> {
    Optional<Plan> findByCode(String code);
}
