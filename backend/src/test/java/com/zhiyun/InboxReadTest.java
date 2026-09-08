package com.zhiyun;

import com.zhiyun.auth.AuthService;
import com.zhiyun.domain.AppUser;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.notify.InboxService;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InboxReadTest {
    @Autowired
    InboxService inboxService;
    @Autowired
    AuthService authService;
    @Autowired
    UserRepo userRepo;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void markAllAndTaskReadClearUnreadWithoutRevivingOnRefresh() {
        AuthUser user = login("inbox-" + System.nanoTime() + "@zhiyun.dev");
        TenantContext.set(user);
        ReviewTask task = new ReviewTask();
        task.setId(88001L);
        task.setTenantId(user.tenantId());
        task.setUserId(user.userId());
        task.setStatus(Codes.WAITING_ACCEPT);
        inboxService.notifyReview(task);
        assertThat(inboxService.unreadCount()).isEqualTo(1);

        inboxService.markRead(null, null, true);
        assertThat(inboxService.unreadCount()).isEqualTo(0);
        assertThat(inboxService.unreadCount()).isEqualTo(0);

        task.setStatus(Codes.DONE);
        inboxService.notifyReview(task);
        assertThat(inboxService.unreadCount()).isEqualTo(1);
        inboxService.markTaskRead(task);
        assertThat(inboxService.unreadCount()).isEqualTo(0);
    }

    private AuthUser login(String email) {
        authService.register(email, "demo123456", "Inbox", null);
        AppUser row = userRepo.findByEmail(email).orElseThrow();
        return new AuthUser(row.getId(), row.getTenantId(), row.getEmail(), row.getDisplayName());
    }
}
