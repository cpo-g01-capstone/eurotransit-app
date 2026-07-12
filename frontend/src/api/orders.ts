import { ApiError, apiFetch } from './client'
import type { CreateOrderRequest, OrderResponse } from './types'

/**
 * Orders API. Contract details that matter (verified against
 * orders-service/OrderController.kt and tests/k6/checkout-e2e.js):
 *
 * - POST /orders requires a non-blank Idempotency-Key header.
 *   202 = accepted (new order, status DRAFT), 200 = idempotent replay of a
 *   previously seen key, 400 = missing key, 429 + Retry-After = load shedding.
 * - A 429 is NOT a checkout failure: retry after the advertised delay with
 *   the SAME key so a success that slipped through is replayed, not duplicated.
 * - GET /orders/{id} is not rate-limited; 404 = unknown order.
 */

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export interface PlaceOrderResult {
  order: OrderResponse
  /** true when the backend replayed a cached response (HTTP 200) instead of creating (202). */
  replayed: boolean
}

export interface PlaceOrderOptions {
  idempotencyKey: string
  /** Total attempts including the first (default 5). */
  maxAttempts?: number
  /** Called before each backoff wait, for UI feedback. */
  onRetry?: (attempt: number, waitMs: number) => void
  signal?: AbortSignal
  /** Injectable for tests. */
  sleep?: (ms: number, signal?: AbortSignal) => Promise<void>
}

export async function placeOrder(
  request: CreateOrderRequest,
  options: PlaceOrderOptions,
): Promise<PlaceOrderResult> {
  const { idempotencyKey, maxAttempts = 5, onRetry, signal, sleep = defaultSleep } = options

  for (let attempt = 1; ; attempt++) {
    try {
      const { status, body } = await apiFetch<OrderResponse>('/orders', {
        method: 'POST',
        headers: { 'Idempotency-Key': idempotencyKey },
        body: JSON.stringify(request),
        signal,
      })
      return { order: body, replayed: status === 200 }
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 429 || attempt >= maxAttempts) {
        throw error
      }
      const waitMs = 1000 // backend always sheds with Retry-After: 1
      onRetry?.(attempt, waitMs)
      await sleep(waitMs, signal)
    }
  }
}

/** GET /orders/{id} — throws ApiError(404) for unknown orders. */
export async function getOrder(id: string, signal?: AbortSignal): Promise<OrderResponse> {
  const { body } = await apiFetch<OrderResponse>(`/orders/${encodeURIComponent(id)}`, { signal })
  return body
}

function defaultSleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true },
    )
  })
}
