import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './client'
import { getOrder, placeOrder } from './orders'

const DRAFT_BODY = { orderId: 'a0000000-0000-4000-8000-000000000001', status: 'DRAFT', message: 'Order accepted for processing' }

function jsonResponse(status: number, body?: unknown, headers?: Record<string, string>) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('placeOrder', () => {
  it('returns replayed=false on 202 (new order)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(202, DRAFT_BODY))
    vi.stubGlobal('fetch', fetchMock)

    const result = await placeOrder(
      { routeId: 'r', seats: 2 },
      { idempotencyKey: 'key-1' },
    )

    expect(result.replayed).toBe(false)
    expect(result.order.status).toBe('DRAFT')
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/orders')
    expect(init.method).toBe('POST')
    expect(init.headers['Idempotency-Key']).toBe('key-1')
    expect(init.headers['Content-Type']).toBe('application/json')
    expect(JSON.parse(init.body)).toEqual({ routeId: 'r', seats: 2 })
  })

  it('returns replayed=true on 200 (idempotent replay)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, DRAFT_BODY)))
    const result = await placeOrder({ routeId: 'r', seats: 1 }, { idempotencyKey: 'key-1' })
    expect(result.replayed).toBe(true)
  })

  it('retries 429 load-shedding with the SAME idempotency key', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(429, undefined, { 'Retry-After': '1' }))
      .mockResolvedValueOnce(jsonResponse(429, undefined, { 'Retry-After': '1' }))
      .mockResolvedValueOnce(jsonResponse(202, DRAFT_BODY))
    vi.stubGlobal('fetch', fetchMock)
    const retries: number[] = []

    const result = await placeOrder(
      { routeId: 'r', seats: 1 },
      {
        idempotencyKey: 'stable-key',
        onRetry: (attempt) => retries.push(attempt),
        sleep: () => Promise.resolve(),
      },
    )

    expect(result.order.status).toBe('DRAFT')
    expect(retries).toEqual([1, 2])
    const keys = fetchMock.mock.calls.map(([, init]) => init.headers['Idempotency-Key'])
    expect(keys).toEqual(['stable-key', 'stable-key', 'stable-key'])
  })

  it('gives up with ApiError(429) after maxAttempts', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(429)))
    await expect(
      placeOrder(
        { routeId: 'r', seats: 1 },
        { idempotencyKey: 'k', maxAttempts: 3, sleep: () => Promise.resolve() },
      ),
    ).rejects.toMatchObject({ status: 429 })
  })

  it('does not retry a 400 (missing/blank key contract)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(400))
    vi.stubGlobal('fetch', fetchMock)
    await expect(
      placeOrder({ routeId: 'r', seats: 1 }, { idempotencyKey: 'k' }),
    ).rejects.toMatchObject({ status: 400 })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('getOrder', () => {
  it('maps 404 to ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(404)))
    await expect(getOrder('unknown')).rejects.toBeInstanceOf(ApiError)
  })

  it('returns the order on 200', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(200, { ...DRAFT_BODY, status: 'CONFIRMED', message: '' })),
    )
    const order = await getOrder(DRAFT_BODY.orderId)
    expect(order.status).toBe('CONFIRMED')
  })
})
