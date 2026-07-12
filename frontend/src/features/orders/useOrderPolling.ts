import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { getOrder } from '@/api/orders'
import { isTerminal, type OrderResponse } from '@/api/types'

/**
 * The checkout saga is asynchronous (Kafka) and the backend offers no push
 * channel, so the reference contract is a poll loop on GET /orders/{id}
 * (see tests/k6/checkout-e2e.js: 0.5s interval, 45s worst case — the payment
 * redelivery ladder alone can take ~32s).
 */
export const POLL_INTERVAL_MS = 800
export const STALL_AFTER_MS = 45_000

export interface OrderPollState {
  order: OrderResponse | null
  /** GET returned 404 — unknown order ID. Polling stops. */
  notFound: boolean
  /** Last poll failed transiently (network/5xx). Polling continues. */
  degraded: boolean
  /** No terminal state after STALL_AFTER_MS. Polling continues anyway. */
  stalled: boolean
}

export function useOrderPolling(orderId: string): OrderPollState {
  const [state, setState] = useState<OrderPollState>({
    order: null,
    notFound: false,
    degraded: false,
    stalled: false,
  })

  useEffect(() => {
    setState({ order: null, notFound: false, degraded: false, stalled: false })
    let cancelled = false
    let timer: number | undefined
    const controller = new AbortController()
    const startedAt = Date.now()

    async function tick(): Promise<void> {
      let terminal = false
      const stalled = Date.now() - startedAt > STALL_AFTER_MS
      try {
        const order = await getOrder(orderId, controller.signal)
        if (cancelled) return
        terminal = isTerminal(order.status)
        setState({ order, notFound: false, degraded: false, stalled: stalled && !terminal })
      } catch (error) {
        if (cancelled) return
        if (error instanceof ApiError && error.status === 404) {
          setState((s) => ({ ...s, notFound: true }))
          return
        }
        // Transient failure: keep the last known order state and keep polling.
        setState((s) => ({ ...s, degraded: true, stalled }))
      }
      if (!terminal) {
        timer = window.setTimeout(() => void tick(), POLL_INTERVAL_MS)
      }
    }

    void tick()
    return () => {
      cancelled = true
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [orderId])

  return state
}
