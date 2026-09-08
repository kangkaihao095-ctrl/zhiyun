package com.zhiyun.notify;

import com.zhiyun.common.Pages;
import com.zhiyun.common.PublicError;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.InboxMessage;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.repo.InboxMessageRepo;
import com.zhiyun.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审校结束站内信。不是云笺会话，不写 localStorage。
 */
@Service
public class InboxService {
    public static final String KIND_WAITING = "REVIEW_WAITING_ACCEPT";
    public static final String KIND_DONE = "REVIEW_DONE";
    public static final String KIND_FAILED = "REVIEW_FAILED";
    public static final String REF_TASK = "task";

    private final InboxMessageRepo inboxMessageRepo;

    public InboxService(InboxMessageRepo inboxMessageRepo) {
        this.inboxMessageRepo = inboxMessageRepo;
    }

    @Transactional
    public void notifyReview(ReviewTask task) {
        if (task == null || task.getUserId() == null) {
            return;
        }
        String kind;
        String title;
        String body;
        if (Codes.WAITING_ACCEPT.equals(task.getStatus())) {
            kind = KIND_WAITING;
            title = "完整审校待你确认";
            body = "任务 " + task.publicId() + " 已产出候选稿，请打开结果页选择接受、部分接受或拒绝。";
        } else if (Codes.DONE.equals(task.getStatus())) {
            kind = KIND_DONE;
            title = "审校已完成";
            body = "任务 " + task.publicId() + " 已结束。";
        } else if (Codes.FAILED.equals(task.getStatus())) {
            kind = KIND_FAILED;
            title = "审校没能完成";
            String err = task.getErrorMessage() == null || task.getErrorMessage().isBlank()
                    ? "可从检查点继续。"
                    : PublicError.message(task.getErrorMessage());
            body = "任务 " + task.publicId() + " 失败。" + err;
        } else {
            return;
        }
        String refId = refId(task);
        if (inboxMessageRepo.findByTenantIdAndUserIdAndKindAndRefId(
                task.getTenantId(), task.getUserId(), kind, refId).isPresent()) {
            return;
        }
        InboxMessage row = new InboxMessage();
        row.setTenantId(task.getTenantId());
        row.setUserId(task.getUserId());
        row.setKind(kind);
        row.setTitle(title);
        row.setBody(clip(body, 1000));
        row.setRefType(REF_TASK);
        row.setRefId(refId);
        row.setCreatedAt(Instant.now());
        inboxMessageRepo.save(row);
    }

    public Map<String, Object> listMine() {
        long tenantId = TenantContext.tenantId();
        long userId = TenantContext.userId();
        List<InboxMessage> rows = inboxMessageRepo.findByTenantIdAndUserIdOrderByIdDesc(tenantId, userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (InboxMessage row : rows) {
            items.add(toMap(row));
        }
        Map<String, Object> out = Pages.wrap(items, items.size(), 1, Math.max(items.size(), 1));
        out.put("unreadCount", inboxMessageRepo.countByTenantIdAndUserIdAndReadAtIsNull(tenantId, userId));
        return out;
    }

    public long unreadCount() {
        return inboxMessageRepo.countByTenantIdAndUserIdAndReadAtIsNull(TenantContext.tenantId(), TenantContext.userId());
    }

    public Set<String> unreadTaskKeys(long tenantId, long userId) {
        Set<String> ids = new HashSet<>();
        for (InboxMessage row : inboxMessageRepo.findByTenantIdAndUserIdAndReadAtIsNull(tenantId, userId)) {
            String key = parseTaskKey(row.getRefId());
            if (key != null) {
                ids.add(key);
            }
        }
        return ids;
    }

    @Transactional
    public Map<String, Object> markRead(Long id, String refId) {
        return markRead(id, refId, false);
    }

    public Map<String, Object> markRead(Long id, String refId, boolean all) {
        long tenantId = TenantContext.tenantId();
        long userId = TenantContext.userId();
        Instant now = Instant.now();
        if (all) {
            inboxMessageRepo.markAllRead(tenantId, userId, now);
        } else if (id != null) {
            inboxMessageRepo.findByIdAndTenantIdAndUserId(id, tenantId, userId).ifPresent(row -> {
                if (row.getReadAt() == null) {
                    row.setReadAt(now);
                    inboxMessageRepo.save(row);
                }
            });
        } else if (refId != null && !refId.isBlank()) {
            inboxMessageRepo.markReadByRef(tenantId, userId, refId.trim(), now);
        }
        return listMine();
    }

    /** 该任务全部站内信标已读（含刚写入的 DONE）。租户取任务自身，不信请求体。 */
    public void markTaskRead(ReviewTask task) {
        if (task == null || task.getId() == null || task.getTenantId() == null || task.getUserId() == null) {
            return;
        }
        inboxMessageRepo.markReadByRef(task.getTenantId(), task.getUserId(), refId(task), Instant.now());
    }

    public static String refId(ReviewTask task) {
        return "task-" + (task == null ? "" : task.publicId());
    }

    public static String refId(long taskId) {
        return "task-" + taskId;
    }

    private static String parseTaskKey(String refId) {
        if (refId == null || !refId.startsWith("task-")) {
            return null;
        }
        String key = refId.substring(5).trim();
        return key.isEmpty() ? null : key;
    }

    private static Map<String, Object> toMap(InboxMessage row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("kind", row.getKind());
        m.put("title", row.getTitle());
        m.put("body", row.getBody());
        m.put("refType", row.getRefType());
        m.put("refId", row.getRefId());
        m.put("unread", row.getReadAt() == null);
        m.put("createdAt", row.getCreatedAt());
        return m;
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
