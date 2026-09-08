# Superiority Claims Without Ablation

## Abstract
Our encoder outperforms BERT, RoBERTa and GPT-class models on every GLUE subset. We do not provide error bars.

## Introduction
The method outperforms all prior work by a large margin. We attribute gains to a new attention bias.

## Method
We add a scalar bias to self-attention. Implementation details occupy one paragraph. No hyperparameter search is described.

## Experiments
On a private 80-example split, accuracy is 99.2%. We did not run an ablation that removes the bias, did not compare compute, and did not release seeds. The claim that experiments support the conclusion is therefore an Evidence Gap.

## Conclusion
Results prove the architecture is universally better. No Limitations section is included (ACL papers usually need one before references).

## References
- Devlin et al. BERT. DOI 10.18653/v1/N19-1423
