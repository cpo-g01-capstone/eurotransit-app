import { useId, useState, type FormEvent, type InputHTMLAttributes } from 'react'
import { useNavigate } from 'react-router'
import { ArrowLeft, ArrowRight, CreditCard, Lock, ShieldCheck } from 'lucide-react'
import { ApiError } from '@/api/client'
import { newIdempotencyKey, placeOrder } from '@/api/orders'
import type { CatalogRoute } from '@/api/types'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Spinner } from '@/components/ui/spinner'
import { Stepper } from '@/components/ui/stepper'
import { cn } from '@/lib/cn'
import { formatEUR } from '@/lib/format'
import { saveTrip } from '@/store/trips'
import {
  cardValid,
  cvcValid,
  digitsOf,
  expiryValid,
  formatCardNumber,
  formatExpiry,
  panValid,
  type CardDetails,
} from './payment'

type Step = 'seats' | 'payment'

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
  const [step, setStep] = useState<Step>('seats')
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' })
  // Card details live in component memory only and are validated on this
  // device — they are never sent, stored, or logged (see payment.ts).
  const [card, setCard] = useState<CardDetails>({ holder: '', number: '', expiry: '', cvc: '' })
  const [showCardErrors, setShowCardErrors] = useState(false)

  const total = seats * route.price
  const submitting = phase.kind === 'submitting'

  function pay(event: FormEvent) {
    event.preventDefault()
    if (!cardValid(card)) {
      setShowCardErrors(true)
      return
    }
    void book()
  }

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
      // justPlaced lets the order page treat the first poll as a live
      // transition (fast sagas can already be CONFIRMED by then → fireworks).
      navigate(`/orders/${order.orderId}`, { state: { justPlaced: true } })
    } catch (error) {
      setPhase({ kind: 'error', message: checkoutErrorMessage(error) })
    }
  }

  if (soldOut) {
    return (
      <Card className="p-6">
        <h2 className="display-type text-xl">Book seats</h2>
        <p className="mt-4 rounded-lg bg-fail-soft p-4 text-sm text-fail">
          This train is sold out right now. Seats occasionally become available again — check back
          shortly.
        </p>
      </Card>
    )
  }

  return (
    <Card className="p-6">
      <div className="flex items-center justify-between">
        <h2 className="display-type text-xl">{step === 'seats' ? 'Book seats' : 'Payment'}</h2>
        <p className="eyebrow text-steel" aria-live="polite">
          Step {step === 'seats' ? '1' : '2'} of 2
        </p>
      </div>

      {step === 'seats' ? (
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

          <Button size="lg" className="mt-5 w-full" onClick={() => setStep('payment')}>
            Continue to payment
            <ArrowRight className="size-5" aria-hidden />
          </Button>

          <p className="mt-4 flex items-start gap-2 text-xs text-steel">
            <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-go" />
            Your seats are held while your payment is authorised — follow every step of your
            booking on the next screen.
          </p>
        </>
      ) : (
        <form onSubmit={pay} noValidate>
          <div className="mt-4 flex items-center justify-between rounded-lg bg-paper-dim px-4 py-3">
            <p className="text-sm">
              {route.origin} → {route.destination} · {seats} seat{seats === 1 ? '' : 's'}
            </p>
            <p className="font-mono text-lg font-semibold">{formatEUR(total)}</p>
          </div>

          <div className="mt-4 grid gap-3">
            <CardField
              label="Cardholder name"
              value={card.holder}
              onChange={(v) => setCard({ ...card, holder: v })}
              error={showCardErrors && card.holder.trim().length < 2 ? 'Enter the name on the card.' : ''}
              autoComplete="cc-name"
              placeholder="Name on card"
              disabled={submitting}
            />
            <CardField
              label="Card number"
              value={card.number}
              onChange={(v) => setCard({ ...card, number: formatCardNumber(v) })}
              error={showCardErrors && !panValid(digitsOf(card.number)) ? 'Enter a valid 16-digit card number.' : ''}
              autoComplete="cc-number"
              inputMode="numeric"
              placeholder="1234 5678 9012 3456"
              className="font-mono"
              disabled={submitting}
            />
            <div className="grid grid-cols-2 gap-3">
              <CardField
                label="Expiry"
                value={card.expiry}
                onChange={(v) => setCard({ ...card, expiry: formatExpiry(v) })}
                error={showCardErrors && !expiryValid(card.expiry) ? 'Use a future MM/YY date.' : ''}
                autoComplete="cc-exp"
                inputMode="numeric"
                placeholder="MM/YY"
                className="font-mono"
                disabled={submitting}
              />
              <CardField
                label="Security code"
                value={card.cvc}
                onChange={(v) => setCard({ ...card, cvc: digitsOf(v).slice(0, 4) })}
                error={showCardErrors && !cvcValid(card.cvc) ? '3 or 4 digits.' : ''}
                autoComplete="cc-csc"
                inputMode="numeric"
                placeholder="CVC"
                className="font-mono"
                disabled={submitting}
              />
            </div>
          </div>

          <Button type="submit" size="lg" className="mt-5 w-full" disabled={submitting}>
            {submitting ? <Spinner /> : <CreditCard className="size-5" aria-hidden />}
            {submitting ? 'Processing payment…' : `Pay ${formatEUR(total)}`}
          </Button>
          <button
            type="button"
            disabled={submitting}
            onClick={() => setStep('seats')}
            className="mt-3 inline-flex cursor-pointer items-center gap-1.5 text-sm font-semibold text-steel transition-colors hover:text-crimson disabled:opacity-45"
          >
            <ArrowLeft className="size-4" aria-hidden /> Back to seats
          </button>

          {phase.kind === 'submitting' && phase.retryAttempt > 0 && (
            <p aria-live="polite" className="mt-3 rounded-lg bg-wait-soft p-3 text-sm text-wait">
              High demand right now — hang tight, we're retrying for you (attempt{' '}
              {phase.retryAttempt + 1} of 5). You will never be charged twice.
            </p>
          )}
          {phase.kind === 'error' && (
            <p role="alert" className="mt-3 rounded-lg bg-fail-soft p-3 text-sm text-fail">
              {phase.message}
            </p>
          )}

          <p className="mt-4 flex items-start gap-2 text-xs text-steel">
            <Lock aria-hidden className="mt-0.5 size-4 shrink-0 text-go" />
            Demo mode: your card is checked on this device and never sent or stored — no real
            charge is made. The authorisation you'll see on the next screen is performed by the
            booking system.
          </p>
        </form>
      )}
    </Card>
  )
}

interface CardFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'onChange' | 'value'> {
  label: string
  value: string
  onChange: (value: string) => void
  error: string
}

function CardField({ label, value, onChange, error, className, ...props }: CardFieldProps) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className="grid gap-1.5">
      <label htmlFor={id} className="eyebrow text-steel">
        {label}
      </label>
      <input
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={!!error}
        aria-describedby={error ? errorId : undefined}
        spellCheck={false}
        className={cn(
          'h-11 w-full rounded-lg border bg-white px-3 text-sm',
          error ? 'border-fail' : 'border-ink/20',
          className,
        )}
        {...props}
      />
      {error && (
        <p id={errorId} role="alert" className="text-xs text-fail">
          {error}
        </p>
      )}
    </div>
  )
}

function checkoutErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429)
      return "Demand is very high right now and we couldn't get your booking through. Nothing was booked and you were not charged — please try again in a few seconds."
    if (error.status === 400)
      return 'Something went wrong with your booking. Refresh the page and try again.'
    return "We couldn't place your booking. Please try again in a moment."
  }
  return "We couldn't reach our booking system. Check your connection and try again — nothing was booked and you were not charged."
}
