package com.zhiyun.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.cs.CustomerService;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api")
public class CustomerServiceController {
    private final CustomerService customerService;
    private final Executor csSseExecutor;
    private final ObjectMapper objectMapper;

    public CustomerServiceController(CustomerService customerService,
                                     @Qualifier("csSseExecutor") Executor csSseExecutor,
                                     ObjectMapper objectMapper) {
        this.customerService = customerService;
        this.csSseExecutor = csSseExecutor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/cs/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chat(@RequestBody CustomerService.ChatReq req) {
        AuthUser user = TenantContext.require();
        SseEmitter emitter = new SseEmitter(180_000L);
        csSseExecutor.execute(() -> {
            TenantContext.set(user);
            try {
                customerService.stream(req, token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token")
                                .data(objectMapper.writeValueAsString(token)));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            } finally {
                TenantContext.clear();
            }
        });
        return ResponseEntity.ok()
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .header("Connection", "keep-alive")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(emitter);
    }
}
