package com.zhiyun.web;

import com.zhiyun.notify.InboxService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/inbox")
public class InboxController {
    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    public record ReadReq(Long id, String refId, Boolean all) {
    }

    @GetMapping
    public Map<String, Object> list() {
        return inboxService.listMine();
    }

    @PostMapping("/read")
    public Map<String, Object> read(@RequestBody(required = false) ReadReq req) {
        Long id = req == null ? null : req.id();
        String refId = req == null ? null : req.refId();
        boolean all = req != null && Boolean.TRUE.equals(req.all());
        return inboxService.markRead(id, refId, all);
    }
}
