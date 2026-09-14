package com.zhiyun.web;

import com.zhiyun.harness.ObservabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ObservabilityController {
    private final ObservabilityService observabilityService;

    public ObservabilityController(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @GetMapping("/ops/observability")
    public Map<String, Object> opsDashboard(@RequestParam(required = false, defaultValue = "7d") String range,
                                            @RequestParam(required = false) Long tenantId) {
        return observabilityService.dashboard(range, tenantId);
    }

    @GetMapping("/observability")
    public Map<String, Object> dashboard(@RequestParam(required = false, defaultValue = "7d") String range,
                                         @RequestParam(required = false) Long tenantId) {
        return observabilityService.dashboard(range, tenantId);
    }
}
