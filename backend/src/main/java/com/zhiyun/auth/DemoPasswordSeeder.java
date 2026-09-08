package com.zhiyun.auth;

import com.zhiyun.repo.TenantRepo;
import com.zhiyun.repo.UserRepo;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(1)
public class DemoPasswordSeeder implements ApplicationRunner {
    private final UserRepo userRepo;
    private final TenantRepo tenantRepo;
    private final PasswordEncoder passwordEncoder;
    private final AvatarService avatarService;

    public DemoPasswordSeeder(UserRepo userRepo, TenantRepo tenantRepo, PasswordEncoder passwordEncoder,
                              AvatarService avatarService) {
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.passwordEncoder = passwordEncoder;
        this.avatarService = avatarService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        userRepo.findByEmail("demo@zhiyun.dev").ifPresent(user -> {
            boolean dirty = false;
            if (!passwordEncoder.matches("demo123456", user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode("demo123456"));
                dirty = true;
            }
            if (user.getDisplayName() == null || user.getDisplayName().isBlank()
                    || "Demo User".equals(user.getDisplayName())) {
                user.setDisplayName("一只用户");
                dirty = true;
            }
            if (dirty) {
                userRepo.save(user);
            }
            tenantRepo.findById(user.getTenantId()).ifPresent(tenant -> {
                if ("Demo Lab".equals(tenant.getName()) || tenant.getName() == null || tenant.getName().isBlank()) {
                    tenant.setName("个人实验室");
                    tenantRepo.save(tenant);
                }
            });
            avatarService.seedDemo(user);
        });
    }
}
