package com.zhiyun.workflow;

import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.Codes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WorkflowCatalog {
    public record WorkflowView(String id, String name, String summary, List<String> agents, int capPoints, String useWhen) {
    }

    private final ZhiyunProperties properties;

    public WorkflowCatalog(ZhiyunProperties properties) {
        this.properties = properties;
    }

    public List<WorkflowView> all() {
        ZhiyunProperties.Quota q = properties.getQuota();
        return List.of(
                new WorkflowView(Codes.CITATION_ONLY, "引用核验",
                        "只检查参考文献和 DOI 是否站得住，最快、最省额度。",
                        List.of("引用核验"),
                        q.getCapCitation(),
                        "交稿前先扫一遍参考文献，揪出幽灵引用。"),
                new WorkflowView(Codes.QUICK_REVIEW, "快速审读",
                        "核验引用之后，再看一遍学术语言是否像套话。不会改你的正文。",
                        List.of("引用核验", "语言润色"),
                        q.getCapQuick(),
                        "改完一节，想先看风险和空话，还不需要改稿。"),
                new WorkflowView(Codes.FULL_REVIEW, "投稿前完整审校",
                        "引用、图表、审稿意见、语言、改稿计划到执行一次做完。结束后由你确认是否采纳。",
                        List.of("引用核验", "图表检查", "学术审稿", "语言润色", "改稿计划", "修改执行", "结果复核"),
                        q.getCapFull(),
                        "投稿或大修前，需要一份候选稿，并由你决定接不接受。")
        );
    }

    public int cap(String workflow) {
        ZhiyunProperties.Quota q = properties.getQuota();
        if (Codes.QUICK_REVIEW.equals(workflow)) {
            return q.getCapQuick();
        }
        if (Codes.FULL_REVIEW.equals(workflow)) {
            return q.getCapFull();
        }
        return q.getCapCitation();
    }

    public Map<String, Object> billingHint() {
        ZhiyunProperties.Quota q = properties.getQuota();
        return Map.of(
                "tokensPerPoint", q.getTokensPerPoint(),
                "minimumPoints", 1,
                "skillFeeCitation", 1,
                "skillFeeQuick", 1,
                "skillFeeFull", 2,
                "note", "平台模型按本次 token 计费，最少 1 额度，不超过该方式上限。自备模型只收技能与提示词服务费（引用核验 1 / 快速审读 1 / 完整审校 2）。"
        );
    }

    public String nameOf(String workflow) {
        return all().stream()
                .filter(w -> w.id().equals(workflow))
                .map(WorkflowView::name)
                .findFirst()
                .orElse(workflow == null ? "审校" : workflow);
    }
}
