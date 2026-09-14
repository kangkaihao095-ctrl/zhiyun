import { describe, expect, it } from 'vitest'
import {
  classifyPublicError,
  isUnsafeErrorDetail,
  PUBLIC_ERROR_COPY,
  publicErrorCode,
  publicErrorMessage,
  publicErrorTech
} from '../src/public-error'

const ARREARAGE_JSON = 'structured output failed after retry: 400 Bad Request: "{"error":{"message":"Access denied, please make sure your account is in good standing. For details, see: https://help.aliyun.com/zh/model-studio/error-code#overdue-payment","type":"Arrearage","param":null,"code":"Arrearage"},"id":"chatcmpl-18bf2a2c-3472-9a54-ab61-78935a8301ad","request_id":"18bf2a2c-3472-9a54-ab61-78935a8301ad"}"'

describe('publicErrorMessage', () => {
  it('maps Aliyun 400 Arrearage JSON to a short Chinese quota line', () => {
    expect(classifyPublicError(ARREARAGE_JSON)).toBe('arrearage')
    expect(publicErrorMessage(ARREARAGE_JSON)).toBe(PUBLIC_ERROR_COPY.arrearage)
    expect(publicErrorMessage(ARREARAGE_JSON)).not.toContain('help.aliyun')
    expect(publicErrorMessage(ARREARAGE_JSON)).not.toContain('request_id')
    expect(publicErrorMessage(ARREARAGE_JSON)).not.toContain('{')
    expect(isUnsafeErrorDetail(ARREARAGE_JSON)).toBe(true)
    expect(publicErrorTech(ARREARAGE_JSON)).toBe('内部码：arrearage')
  })

  it('classifies other 400-style upstream failures', () => {
    expect(classifyPublicError('400 Bad Request: {"error":{"type":"Arrearage"}}')).toBe('arrearage')
    expect(classifyPublicError('400 overdue-payment')).toBe('arrearage')
    expect(publicErrorMessage('400 Invalid API Key')).toBe(PUBLIC_ERROR_COPY.invalid_key)
    expect(classifyPublicError('401 unauthorized: invalid_api_key')).toBe('invalid_key')
  })

  it('classifies timeout, structured output and network', () => {
    expect(publicErrorMessage('timeout')).toBe(PUBLIC_ERROR_COPY.timeout)
    expect(publicErrorMessage('injected timeout on CITATION_INTEGRITY')).toBe(PUBLIC_ERROR_COPY.timeout)
    expect(publicErrorMessage('structured output failed after retry')).toBe(PUBLIC_ERROR_COPY.structured_output)
    expect(publicErrorMessage('structured output failed after retry: schema validation failed')).toBe(
      PUBLIC_ERROR_COPY.structured_output
    )
    expect(publicErrorMessage('ECONNREFUSED 127.0.0.1:443')).toBe(PUBLIC_ERROR_COPY.network)
    expect(publicErrorMessage('502 Bad Gateway')).toBe(PUBLIC_ERROR_COPY.network)
  })

  it('keeps cancelled and already-safe copy, and blanks empty input', () => {
    expect(publicErrorMessage('已取消')).toBe('已取消')
    expect(publicErrorCode('已取消')).toBe('cancelled')
    expect(publicErrorMessage(PUBLIC_ERROR_COPY.timeout)).toBe(PUBLIC_ERROR_COPY.timeout)
    expect(publicErrorMessage('')).toBe('')
    expect(publicErrorCode('')).toBe('')
    expect(publicErrorMessage('something else entirely')).toBe(PUBLIC_ERROR_COPY.unknown)
    expect(publicErrorMessage('stale fencing token rejected')).toBe(PUBLIC_ERROR_COPY.unknown)
    expect(publicErrorCode('stale fencing token rejected')).toBe('fencing')
    expect(publicErrorMessage('stale fencing token rejected')).not.toContain('fencing')
  })
})
