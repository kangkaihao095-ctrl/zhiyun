import { describe, expect, it } from 'vitest'
import {
  artifactDownloadName,
  buildReportMarkdown,
  collect,
  doiOf,
  evidenceView,
  isDoiToken,
  isHighPriority,
  locateNeedle,
  looksLikeSectionLabel,
  meaningfulPatch,
  fenceLabel,
  mergeTraceNodes,
  paintRanges,
  paragraphHunks,
  pickFindingNeedle,
  pickText,
  pipelineNodes,
  formatDurationMs,
  readAnchor,
  revisionKindGroups,
  sevKey,
  showAgentTrace,
  showCheckpointPipeline,
  suggestFixOf,
  toPatch,
  toExportArtifacts,
  unwrap,
  whyHighOf
} from '../src/review.js'

describe('unwrap / collect', () => {
  it('reads the envelope body and de-duplicates issues', () => {
    const { issues, patches, tasks } = collect([
      {
        agent: 'CITATION_INTEGRITY',
        artifactType: 'ReviewIssue',
        payload: JSON.stringify({
          schemaVersion: 1,
          body: [{
            issueId: 'iss-1',
            severity: 'HIGH',
            category: 'CITATION',
            summary: 'DOI 10.1000/xyz not found',
            originalText: 'see 10.1000/xyz for details'
          }]
        })
      },
      {
        agent: 'REVISION_PLANNING',
        artifactType: 'RevisionTask',
        payload: JSON.stringify({
          body: [{
            taskId: 'rt-1',
            issueId: 'iss-1',
            kind: 'HUMAN_REQUIRED',
            instruction: 'Verify or replace unverifiable citations'
          }, {
            taskId: 'rt-2',
            issueId: 'iss-2',
            kind: 'AI_AUTOMATABLE',
            instruction: 'Polish AI-like sentences'
          }, {
            taskId: 'rt-3',
            issueId: 'iss-3',
            kind: 'HYBRID',
            instruction: 'Rewrite the claim using existing results'
          }, {
            taskId: 'rt-x',
            issueId: 'iss-x',
            kind: 'MADE_UP_KIND',
            instruction: 'should not invent a fourth type'
          }]
        })
      },
      {
        agent: 'REVISION_EXECUTION',
        artifactType: 'RevisionPatch',
        payload: JSON.stringify({
          body: [{
            patchId: 'rp-1',
            issueId: 'iss-1',
            originalText: 'this result shows',
            proposedText: 'these results suggest',
            reason: 'avoid overclaim'
          }]
        })
      }
    ])
    expect(issues).toHaveLength(1)
    expect(issues[0].id).toBe('iss-1')
    expect(issues[0].agent).toBe('引用核验')
    expect(patches).toHaveLength(1)
    expect(patches[0].key).toBe('rp-1')
    expect(patches[0].whyHigh).toContain('会改引用结论与作者责任')
    expect(tasks.map((t) => t.kind)).toEqual(['HUMAN_REQUIRED', 'AI_AUTOMATABLE', 'HYBRID', ''])
    const groups = revisionKindGroups(tasks)
    expect(groups.map((g) => g.key)).toEqual(['HUMAN_REQUIRED', 'HYBRID', 'AI_AUTOMATABLE'])
    expect(groups[0].items).toHaveLength(1)
    expect(groups[0].label).toBe('需要人工处理')
    expect(groups.every((g) => g.key !== 'MADE_UP_KIND')).toBe(true)
    expect(issues[0].high).toBe(true)
    expect(issues[0].whyHigh).toContain('会改引用结论与作者责任')
    expect(issues[0].suggestFix).toContain('Verify or replace unverifiable citations')
    expect(tasks[0].whyHigh).toContain('会改引用结论与作者责任')
    expect(tasks[0].suggestFix).toContain('Verify or replace unverifiable citations')
  })

  it('promotes unverified evidence when no ReviewIssue exists', () => {
    const { issues } = collect([
      {
        agent: 'CITATION_INTEGRITY',
        artifactType: 'Evidence',
        payload: JSON.stringify({
          body: [{
            evidenceId: 'ev-1',
            status: 'NOT_VERIFIED',
            claim: 'Paper 10.1234/abcd supports the claim',
            excerpt: 'missing in Crossref'
          }]
        })
      }
    ])
    expect(issues[0].severity).toBe('NOT_VERIFIED')
    expect(issues[0].doi).toBe('10.1234/abcd')
    expect(issues[0].whyHigh).toContain('会改引用结论与作者责任')
  })
})

describe('whyHigh / evidence', () => {
  it('uses explicit rationale and humanRequiredReason from artifacts', () => {
    expect(whyHighOf({
      issue: { severity: 'HIGH', category: 'CITATION', rationale: '幽灵 DOI 会让结论失去文献支撑' }
    })).toBe('幽灵 DOI 会让结论失去文献支撑')
    expect(whyHighOf({
      issue: { severity: 'MEDIUM', category: 'REVIEW' },
      task: { kind: 'HUMAN_REQUIRED', humanRequiredReason: '改真实数据须作者重跑实验' }
    })).toBe('改真实数据须作者重跑实验')
  })

  it('derives why-high from severity and human-in-the-loop boundary', () => {
    expect(isHighPriority({ severity: 'HIGH', category: 'CITATION' }, null)).toBe(true)
    expect(isHighPriority({ severity: 'LOW' }, { kind: 'HUMAN_REQUIRED' })).toBe(true)
    expect(whyHighOf({ issue: { severity: 'HIGH', category: 'REVIEW' } })).toContain('会改实验结论或数据表述')
    expect(whyHighOf({ task: { kind: 'HUMAN_REQUIRED' } })).toContain('人机边界')
    expect(suggestFixOf({
      issue: { action: '请核对这条引用。引用核验不会自动改正文。' },
      task: { instruction: '核对幽灵引用' }
    })).toBe('核对幽灵引用')
  })

  it('puts why-high, suggest-fix and evidence sentences on HIGH cards', () => {
    const { issues, tasks } = collect([
      {
        agent: 'CITATION_INTEGRITY',
        artifactType: 'ReviewIssue',
        payload: JSON.stringify({
          body: [{
            issueId: 'iss-h',
            severity: 'HIGH',
            category: 'CITATION',
            section: 'references',
            location: { anchor: '10.0000/ghost.doi' },
            summary: 'DOI 10.0000/ghost.doi not found',
            detail: 'Java metadata verification failed; model is forbidden to invent a replacement DOI.',
            originalText: 'see 10.0000/ghost.doi',
            evidenceIds: ['ev-ghost']
          }]
        })
      },
      {
        agent: 'CITATION_INTEGRITY',
        artifactType: 'Evidence',
        payload: JSON.stringify({
          body: [{
            evidenceId: 'ev-ghost',
            source: 'CROSSREF',
            status: 'NOT_VERIFIED',
            claim: 'Citation 10.0000/ghost.doi',
            excerpt: '10.0000/ghost.doi'
          }]
        })
      },
      {
        agent: 'REVISION_PLANNING',
        artifactType: 'RevisionTask',
        payload: JSON.stringify({
          body: [{
            taskId: 'rt-h',
            issueId: 'iss-h',
            kind: 'HUMAN_REQUIRED',
            instruction: '核对幽灵引用'
          }]
        })
      }
    ])
    expect(issues[0].whyHigh).toContain('会改引用结论与作者责任')
    expect(issues[0].suggestFix).toBe('核对幽灵引用')
    expect(issues[0].evidenceView.location).toContain('references')
    expect(issues[0].evidenceView.location).toContain('10.0000/ghost.doi')
    expect(issues[0].evidenceView.excerpt).toContain('see 10.0000/ghost.doi')
    expect(issues[0].evidenceView.basis).toContain('Evidence ev-ghost')
    expect(issues[0].evidenceView.basis).toContain('CROSSREF')
    expect(issues[0].evidenceView.basis).toContain('NOT_VERIFIED')
    expect(tasks[0].whyHigh).toContain('会改引用结论与作者责任')
    expect(evidenceView(issues[0], [{
      id: 'ev-ghost', source: 'CROSSREF', status: 'NOT_VERIFIED', claim: 'Citation 10.0000/ghost.doi'
    }]).basis).toContain('ev-ghost')
  })
})

describe('export helpers', () => {
  const arts = [{
    id: 7,
    agent: 'CITATION_INTEGRITY',
    artifactType: 'Evidence',
    payload: JSON.stringify({
      body: [{
        evidenceId: 'ev-1',
        status: 'NOT_VERIFIED',
        claim: 'Paper 10.1234/abcd supports the claim',
        excerpt: 'missing in Crossref'
      }]
    })
  }]

  it('parses payload and names a single artifact file', () => {
    const rows = toExportArtifacts(arts)
    const payload = rows[0].payload as { body: Array<{ status: string }> }
    expect(payload.body[0].status).toBe('NOT_VERIFIED')
    expect(artifactDownloadName(9, arts[0])).toBe('zhiyun-9-CITATION_INTEGRITY-Evidence-7.json')
  })

  it('builds a fallback markdown that keeps NOT_VERIFIED when /report is down', () => {
    const md = buildReportMarkdown(
      { id: 9, workflow: 'CITATION_ONLY', status: 'DONE', manuscriptId: 3, sourceVersion: 1 },
      arts,
      { workflowName: '引用核验', manuscriptTitle: '评测稿' }
    )
    expect(md).toContain('审校结果汇总')
    expect(md).toContain('NOT_VERIFIED')
    expect(md).toContain('10.1234/abcd')
    expect(md).toContain('Artifact 原始 JSON')
  })
})

describe('toPatch', () => {
  it('ignores no-op and junk patches', () => {
    expect(toPatch({ originalText: 'same', proposedText: 'same' })).toBeNull()
    expect(toPatch({ original: 'function () {}', proposed: '' })).toBeNull()
    expect(toPatch({
      originalText: 'alpha sentence here',
      proposedText: 'beta sentence here',
      patchId: 'rp-2'
    })?.key).toBe('rp-2')
  })
})

describe('locateNeedle', () => {
  it('finds every DOI occurrence', () => {
    const text = 'Cite 10.1000/xyz and again 10.1000/xyz in the list.'
    expect(locateNeedle(text, '10.1000/xyz')).toEqual([
      text.indexOf('10.1000/xyz'),
      text.lastIndexOf('10.1000/xyz')
    ])
  })

  it('matches a long sentence case-insensitively', () => {
    const text = 'The Method section describes The Pipeline.'
    expect(locateNeedle(text, 'the method section describes the pipeline.')).toEqual([0])
  })

  it('returns empty when the span is missing', () => {
    expect(locateNeedle('hello world', 'this sentence is not here at all')).toEqual([])
  })
})

describe('pickFindingNeedle', () => {
  it('prefers a DOI in the manuscript', () => {
    const found = pickFindingNeedle(
      { summary: 'DOI 10.5555/paper missing', doi: '10.5555/paper', excerpt: 'Introduction' },
      'See 10.5555/paper in references.'
    )
    expect(found).toMatchObject({ needle: '10.5555/paper', all: true })
  })

  it('skips section labels used as excerpts', () => {
    expect(looksLikeSectionLabel('Introduction')).toBe(true)
    expect(pickFindingNeedle(
      { summary: 'need more evidence', excerpt: 'Introduction' },
      'Introduction\n\nWe propose a method.'
    )).toBeNull()
  })
})

describe('paintRanges', () => {
  it('marks the active patch without dropping surrounding text', () => {
    const text = 'AAA BBB CCC'
    const parts = paintRanges(text, [{ start: 4, end: 7, kind: 'patch', key: 'p1' }], 'p1')
    expect(parts.map((p) => p.text).join('')).toBe(text)
    expect(parts.some((p) => p.patchOn && p.text === 'BBB')).toBe(true)
  })
})

describe('helpers', () => {
  it('does not treat String.prototype.anchor as a location field', () => {
    expect(readAnchor('hello')).toBe('')
    expect(readAnchor({ anchor: 'Figure 2 caption' })).toBe('Figure 2 caption')
  })

  it('skips native-code junk in pickText', () => {
    expect(pickText('function foo() {}', '  real text  ')).toBe('real text')
  })

  it('extracts DOI tokens', () => {
    expect(doiOf('see 10.1000/xyz,')).toBe('10.1000/xyz')
    expect(isDoiToken('10.1000/xyz')).toBe(true)
    expect(isDoiToken('not a doi')).toBe(false)
  })

  it('maps severity and filters empty hunks', () => {
    expect(sevKey('HIGH')).toBe('HIGH')
    expect(sevKey('weird')).toBe('OTHER')
    expect(meaningfulPatch({ original: 'a', proposed: 'b' })).toBe(true)
    expect(meaningfulPatch({ original: 'a', proposed: 'a' })).toBe(false)
    expect(paragraphHunks('one\n\ntwo', 'one\n\nTWO')).toHaveLength(1)
    expect(unwrap('{"body":[1]}')).toEqual([1])
  })
})

describe('checkpoint pipeline', () => {
  it('shows RUNNING / PENDING / FAILED nodes from checkpoint_agent', () => {
    expect(showCheckpointPipeline('RUNNING')).toBe(true)
    expect(showCheckpointPipeline('PENDING')).toBe(true)
    expect(showCheckpointPipeline('FAILED')).toBe(true)
    expect(showCheckpointPipeline('DONE')).toBe(false)
    expect(showCheckpointPipeline('WAITING_ACCEPT')).toBe(false)

    const running = pipelineNodes('FULL_REVIEW', 'CITATION_INTEGRITY', 'RUNNING', '')
    expect(running.map((n: { id: string }) => n.id)).toEqual([
      'CITATION_INTEGRITY', 'FIGURE_PDF', 'ACADEMIC_REVIEWER', 'ACADEMIC_STYLE',
      'REVISION_PLANNING', 'REVISION_EXECUTION', 'FINAL_VERIFICATION'
    ])
    expect(running[0]).toMatchObject({ state: 'done', label: '完成', name: '引用核验' })
    expect(running[1]).toMatchObject({ state: 'current', label: '当前', name: '图表检查' })
    expect(running[2]).toMatchObject({ state: 'pending', label: '未到' })

    const pending = pipelineNodes('QUICK_REVIEW', '', 'PENDING', '')
    expect(pending).toHaveLength(2)
    expect(pending[0]).toMatchObject({ state: 'current', id: 'CITATION_INTEGRITY' })
    expect(pending[1].state).toBe('pending')

    const failed = pipelineNodes(
      'CITATION_ONLY',
      '',
      'FAILED',
      'structured output failed after retry'
    )
    expect(failed).toHaveLength(1)
    expect(failed[0]).toMatchObject({
      state: 'failed',
      label: '失败',
      errorMessage: '模型输出格式校验失败，请重试。'
    })
  })

  it('marks the node after checkpoint as failed and keeps error off completed nodes', () => {
    const nodes = pipelineNodes('QUICK_REVIEW', 'CITATION_INTEGRITY', 'FAILED', 'timeout')
    expect(nodes[0]).toMatchObject({ state: 'done', errorMessage: '' })
    expect(nodes[1]).toMatchObject({ state: 'failed', errorMessage: '审校超时，请从检查点继续。', name: '语言润色' })
  })

  it('merges trace duration, tokens and checkpoint onto pipeline nodes', () => {
    expect(showAgentTrace('DONE')).toBe(true)
    expect(showAgentTrace('WAITING_ACCEPT')).toBe(true)
    const nodes = pipelineNodes('CITATION_ONLY', 'CITATION_INTEGRITY', 'DONE', '')
    const merged = mergeTraceNodes(nodes, {
      nodes: [{
        agent: 'CITATION_INTEGRITY',
        durationMs: 850,
        tokens: 2000,
        fencingToken: 2,
        checkpoint: true
      }]
    })
    expect(merged[0].meta).toContain('850 ms')
    expect(merged[0].meta).toContain('2000 token')
    expect(merged[0].meta).toContain('checkpoint')
    expect(merged[0].meta).toContain('fence 2')
    expect(merged[0].checkpoint).toBe(true)
    expect(formatDurationMs(1200)).toBe('1.2 s')
    expect(fenceLabel({ fencingToken: 3 }, {
      fencingToken: 4,
      lease: { owner: 'host:1:abc', fencingToken: 4 }
    })).toBe('fencing 4 · lease 4')
  })
})
