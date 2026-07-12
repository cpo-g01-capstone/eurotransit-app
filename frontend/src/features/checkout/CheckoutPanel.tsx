import { useState } from 'react'
import { useNavigate } from 'react-router'
import { ShieldCheck, TicketCheck } from 'lucide-react'
import { ApiError } from '@/api/client'
import { newIdempotencyKey, placeOrder } from '@/api/orders'
import type { CatalogRoute } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Spinner } from '@/components/ui/spinner'
import { Stepper } from '@/components/ui/stepper'
import { formatEUR } from '@/lib/format'
import { saveTrip } from '@/store/trips'

type Phase =
  | { kind: 'idle' }
  | { kind: 'submitting'; retryAttempt: number }
  | { kind: 'error'; message: string }

interface CheckoutPanelProps {
  route: CatalogRoute
  initialSeats: number
}

export function CheckoutPanel({ route, initialSeats }: CheckoutPanelProps) {
  const navigate = useNavigate()
  const soldOut = route.availableSeats <= 0
  const maxSeats = Math.max(1, route.availableSeats)
  const [seats, setSeats] = useState(Math.min(Math.max(1, initialSeats), maxSeats))
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })

  const total = seats * route.price
  const submitting = phase.kind === 'submitting'

  async function book() {
    setPhase({ kind: 'submitting', retryAttempt: 0 })
    // One fresh key per checkout attempt; 429 retries reuse it so the backend
    // can replay instead of double-booking (orders-service idempotency cache).
    const idempotencyKey = newIdempotencyKey()
    try {
      const { order } = await placeOrder(
        { routeId: route.id, seats },
        {
          idempotencyKey,
          onRetry: (attempt) => setPhase({ kind: 'submitting', retryAttempt: attempt }),
        },
      )
      saveTrip({
        orderId: order.orderId,
        routeId: route.id,
        origin: route.origin,
        destination: route.destination,
        seats,
        totalPrice: total,
        placedAt: new Date().toISOString(),
      })
      navigate(`/orders/${order.orderId}`)
    } catch (error) {
      setPhase({ kind: 'error', message: checkoutErrorMessage(error) })
    }
  }

  return (
    <Card className="p-6">
      <h2 className="display-type text-xl">Book seats</h2>

      {soldOut ? (
        <p className="mt-4 rounded-lg bg-fail-soft p-4 text-sm text-fail">
          This train is sold out right now. Seats occasionally become available again — check back
          shortly.
        </p>
      ) : (
        <>
          <div className="mt-5 flex items-center justify-between gap-4">
            <div className="grid gap-1.5">
              <span className="eyebrow text-steel">Seats</span>
              <Stepper label="seats" value={seats} min={1} max={maxSeats} onChange={setSeats} />
            </div>
            <div className="text-right">
              <span className="eyebrow text-steel">Total</span>
              <p className="font-mono text-3xl font-semibold" aria-live="polite">
                {formatEUR(total)}
              </p>
            </div>
          </div>
          <p className="mt-2 text-xs text-steel">
            Up to {maxSeats} seat{maxSeats === 1 ? '' : 's'} available on this train.
          </p>

          <Button size="lg" className="mt-5 w-full" disabled={submitting} onClick={() => void book()}>
            {submitting ? <Spinner /> : <TicketCheck className="size-5" aria-hidden />}
            {submitting ? 'Placing order…' : `Book ${seats} seat${seats === 1 ? '' : 's'}`}
          </Button>

          {phase.kind === 'submitting' && phase.retryAttempt > 0 && (
            <p aria-live="polite" className="mt-3 rounded-lg bg-wait-soft p-3 text-sm text-wait">
              High demand right now — hang tight, we're retrying for you (attempt{' '}
              {phase.retryAttempt + 1} of 5). Nothing will be booked twice.
            </p>
          )}
          {phase.kind === 'error' && (
            <p role="alert" className="mt-3 rounded-lg bg-fail-soft p-3 text-sm text-fail">
              {phase.message}
            </p>
          )}

          <p className="mt-4 flex items-start gap-2 text-xs text-steel">
            <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-go" />
            Your seats are held and your payment authorised in moments — follow every step of your
            booking on the next screen.
          </p>
        </>
      )}
    </Card>
  )
}

function checkoutErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429)
      return "Demand is very high right now and we couldn't get your booking through. Nothing was booked — please try again in a few seconds."
    if (error.status === 400)
      return 'Something went wrong with your booking. Refresh the page and try again.'
    return "We couldn't place your booking. Please try again in a moment."
  }
  return "We couldn't reach our booking system. Check your connection and try again — nothing was booked."
}
