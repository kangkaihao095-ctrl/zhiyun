package com.zhiyun.web;

import com.zhiyun.common.ApiException;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.eval.EvalService;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自建回归接口，不是用户产品能力，也不是线上 SLA。
 * 需登录；默认关闭。仅非 prod 且 ZHIYUN_EVAL_API=true 时开启。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {
    private final EvalService evalService;
    private final ZhiyunProperties properties;
    private final Environment environment;

    public EvalController(EvalService evalService, ZhiyunProperties properties, Environment environment) {
        this.evalService = evalService;
        this.properties = properties;
        this.environment = environment;
    }

    @GetMapping("/rag")
    public Map<String, Object> rag() {
        assertEnabled();
        return withRegressionMeta(evalService.ragRecallAt5());
    }

    @GetMapping("/agents")
    public Map<String, Object> agents() {
        assertEnabled();
        return withRegressionMeta(evalService.agentCatalog());
    }

    @GetMapping
    public Map<String, Object> all() {
        assertEnabled();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rag", evalService.ragRecallAt5());
        out.put("agents", evalService.agentCatalog());
        out.put("cs", evalService.csEval());
        return withRegressionMeta(out);
    }

    @GetMapping("/cs")
    public Map<String, Object> cs() {
        assertEnabled();
        return withRegressionMeta(evalService.csEval());
    }

    private void assertEnabled() {
        if (!properties.evalApiEnabled(environment.getActiveProfiles())) {
            throw ApiException.notFound("eval 仅用于本地/测试回归，不是产品功能");
        }
    }

    private static Map<String, Object> withRegressionMeta(Map<String, Object> body) {
        body.put("purpose", "regression");
        body.put("sla", false);
        body.put("note", "自建小样本回归，不是线上 SLA");
        return body;
    }
}
