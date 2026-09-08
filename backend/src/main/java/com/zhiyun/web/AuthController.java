package com.zhiyun.web;

import com.zhiyun.auth.AuthService;
import com.zhiyun.auth.AvatarService;
import com.zhiyun.llm.LlmGateway;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService authService;
    private final LlmGateway llmGateway;
    private final AvatarService avatarService;

    public AuthController(AuthService authService, LlmGateway llmGateway, AvatarService avatarService) {
        this.authService = authService;
        this.llmGateway = llmGateway;
        this.avatarService = avatarService;
    }

    public record RegisterReq(@Email String email, @NotBlank String password, @NotBlank String displayName, String tenantName) {
    }

    public record LoginReq(@Email String email, @NotBlank String password) {
    }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody RegisterReq req) {
        return authService.register(req.email(), req.password(), req.displayName(), req.tenantName());
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        return authService.login(req.email(), req.password());
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        return authService.me();
    }

    public record UpdateMeReq(String displayName, String email) {
    }

    @PutMapping("/me")
    public Map<String, Object> updateMe(@RequestBody UpdateMeReq req) {
        return authService.updateMe(req == null ? null : req.displayName(), req == null ? null : req.email());
    }

    @PutMapping("/me/avatar")
    public Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file) throws Exception {
        return avatarService.upload(file);
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<Resource> avatar() {
        Resource resource = avatarService.loadMine();
        return ResponseEntity.ok()
                .contentType(avatarService.mediaType(resource))
                .cacheControl(CacheControl.noCache())
                .body(resource);
    }

    @GetMapping("/llm/status")
    public Map<String, Object> llmStatus() {
        return llmGateway.status();
    }

    public record ImageReq(String prompt) {
    }

    @PostMapping("/llm/image")
    public Map<String, Object> generateImage(@RequestBody(required = false) ImageReq req) {
        String prompt = req == null || req.prompt() == null || req.prompt().isBlank()
                ? "一张简洁的学术论文示意图，白底，线条清晰，无文字水印"
                : req.prompt();
        String url = llmGateway.generateImage(prompt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", url != null && !url.isBlank());
        out.put("model", llmGateway.status().get("imageModel"));
        if (url != null && !url.isBlank()) {
            out.put("url", url);
        } else {
            out.put("error", llmGateway.status().get("lastError"));
        }
        return out;
    }
}
