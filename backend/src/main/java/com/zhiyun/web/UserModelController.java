package com.zhiyun.web;

import com.zhiyun.llm.UserModelService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class UserModelController {
    private final UserModelService userModelService;

    public UserModelController(UserModelService userModelService) {
        this.userModelService = userModelService;
    }

    @GetMapping({"/api/models", "/api/me/models"})
    public Map<String, Object> catalog() {
        return userModelService.catalog();
    }

    @PutMapping({"/api/models/{agentId}", "/api/me/models/{agentId}"})
    public Map<String, Object> save(@PathVariable String agentId, @RequestBody(required = false) UserModelService.SaveReq req) {
        return userModelService.save(agentId, req);
    }

    @DeleteMapping({"/api/models/{agentId}", "/api/me/models/{agentId}"})
    public Map<String, Object> clear(@PathVariable String agentId) {
        return userModelService.save(agentId, new UserModelService.SaveReq("PLATFORM", null, null, null, null));
    }
}
