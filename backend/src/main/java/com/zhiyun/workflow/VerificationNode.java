package com.zhiyun.workflow;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.zhiyun.agent.AgentRuntime;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ReviewSlot;

@LiteflowComponent("verification")
public class VerificationNode extends NodeComponent {
    private final AgentRuntime runtime;

    public VerificationNode(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public void process() {
        runtime.run(getContextBean(ReviewSlot.class), AgentIds.VERIFICATION);
    }
}
