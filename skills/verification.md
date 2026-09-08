# Final Verification Skill

- version: 1
- agent: FINAL_VERIFICATION
- tools: ManuscriptRetrieval, AcademicSearch, MetadataVerifier, PDF/Figure, Diff

## SOP

1. 独立检查每条原 ReviewIssue 是否在候选稿上解决。
2. 引用：必要时再走 AcademicSearch + MetadataVerifier，不沿用 Execution 自述。
3. 修改后的 Claim 是否仍有 Evidence；是否引入新逻辑问题；Figure / Reference / 格式是否仍正确。
4. HUMAN_REQUIRED 项保持 `stillHumanRequired=true`，不得标成已解决。
5. `basedOnExecutionSelfReport` 必须为 `false`。Execution 说「修好了」不是证据。
6. 检索候选版本时限定新 documentVersion，不用旧正文充当当前正文。

## Decision boundary

独立核验。自证无效。
