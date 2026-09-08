# 各刊实验 vs 仿真（Academic Reviewer 检索）

来源综合：智云刊规范专章；MIT https://github.com/zechenzhangAGI/AI-research-SKILLs 的 ml-paper-writing / systems-paper-writing。供 Reviewer 按 TargetVenue 判断 Evidence Gap，不是统一门槛。

## 必须实验或真实测量的倾向

- Nature / Scientific Reports：生命与物化方向仅仿真几乎不能当主证据。
- OSDI / SOSP / NSDI / ASPLOS：要可运行实现与真实或生产级负载；Levin & Redell：「是实现经验还是思想实验？」
- ACM 系统会议、部分 IEEE 系统/硬件刊：同左。
- ACL 人类语言评测：合成对话不能代替标准测试集或人工评估。

## 仿真或数值实验通常可接受

- IEEE 通信/信号处理/控制会议：仿真可，但要对照与误差。
- NeurIPS / ICML / ICLR / COLM 方法稿：公开基准 + 消融可；环境可以是仿真，须写清局限。
- 理论稿（各刊）：证明优先，实验是验证不是替身。

## Reviewer 口径

没有 Evidence 时不要写「数据造假」。声明了 SOTA / 生产可用 / 临床有效，却只有 toy 仿真 → Evidence Gap。
文风问题交给 Style。TargetVenue 为空时按正文抽到的刊名，再空则只谈通用证据标准。
