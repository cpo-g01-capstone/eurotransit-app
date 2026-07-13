import { useMemo } from 'react'
import { Link, useLoaderData } from 'react-router'
import { CircleAlert, PartyPopper, WifiOff } from 'lucide-react'
import type { OrderStatus } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Spinner } from '@/components/ui/spinner'
import { SagaTimeline } from '@/features/orders/SagaTimeline'
import { useOrderPolling } from '@/features/orders/useOrderPolling'
import { formatEUR } from '@/lib/format'
import { loadTrips } from '@/store/trips'

const HEADLINES: Record<OrderStatus, { title: string; tone: 'neutral' | 'wait' | 'go' | 'fail'; label: string }> = {
  DRAFT: { title: 'Order received — reserving your seats…', tone: 'wait', label: 'Processing' },
  RESERVED: { title: 'Seats held — authorising payment…', tone: 'wait', label: 'Reserved' },
  CONFIRMED: { title: "You're booked. Enjoy the ride!", tone: 'go', label: 'Confirmed' },
  FAILED: { title: 'This order could not be completed', tone: 'fail', label: 'Failed' },
}

export function OrderPage() {
  const { orderId } = useLoaderData() as { orderId: string }
  const { order, notFound, degraded, stalled } = useOrderPolling(orderId)
  const trip = useMemo(() => loadTrips().find((t) => t.orderId === orderId), [orderId])

  if (notFound) {
    return (
      <div className="mx-auto w-full max-w-3xl px-4 py-16 sm:px-6">
        <Card className="p-8 text-center">
          <p className="display-type text-3xl">Booking not found</p>
          <p className="mx-auto mt-3 max-w-md text-sm text-steel">
            We couldn't find a booking with reference <span className="font-mono">{orderId}</span>.
            Double-check it on{' '}
            <Link to="/orders" className="font-semibold text-crimson hover:underline">My trips</Link>.
          </p>
        </Card>
      </div>
    )
  }

  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-10 sm:px-6">
      <p className="eyebrow text-crimson">Booking reference</p>
      <p className="mt-1 font-mono text-xs break-all text-steel">{orderId}</p>

      {order === null ? (
        <Card className="mt-6 flex items-center gap-3 p-8">
          <Spinner className="text-crimson" />
          <p className="text-sm text-steel">Checking your booking…</p>
        </Card>
      ) : (
        <>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            <h1 className="display-type text-3xl sm:text-4xl">{HEADLINES[order.status].title}</h1>
            <Badge tone={HEADLINES[order.status].tone}>{HEADLINES[order.status].label}</Badge>
          </div>

          {trip && (
            <Card className="mt-6 flex flex-wrap items-center justify-between gap-4 p-5">
              <div>
                <p className="display-type text-xl">
                  {trip.origin} <span className="text-crimson">→</span> {trip.destination}
                </p>
                <p className="mt-1 text-sm text-steel">
                  {trip.seats} seat{trip.seats === 1 ? '' : 's'}
                </p>
              </div>
              <p className="font-mono text-2xl font-semibold">{formatEUR(trip.totalPrice)}</p>
            </Card>
          )}

          <Card className="mt-6 p-6 sm:p-8">
            <SagaTimeline status={order.status} />
          </Card>

          {degraded && (
            <p className="mt-4 flex items-center gap-2 rounded-lg bg-wait-soft p-3 text-sm text-wait" aria-live="polite">
              <WifiOff className="size-4 shrink-0" aria-hidden />
              We're having trouble reaching your booking — retrying automatically.
            </p>
          )}

          {stalled && (
            <p className="mt-4 flex items-start gap-2 rounded-lg bg-wait-soft p-3 text-sm text-wait" aria-live="polite">
              <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden />
              This is taking a little longer than usual. You can keep this page open or come back
              later from My trips — your booking is safe and this page always shows its latest
              status.
            </p>
          )}

          {order.status === 'CONFIRMED' && (
            <div className="mt-4 flex items-start gap-3 rounded-lg bg-go-soft p-4 text-sm text-go">
              <PartyPopper className="mt-0.5 size-5 shrink-0" aria-hidden />
              <p>
                Your booking is confirmed and your confirmation is on its way. Find this trip
                anytime under{' '}
                <Link to="/orders" className="font-semibold underline">
                  My trips
                </Link>
                .
              </p>
            </div>
          )}

          {order.status === 'FAILED' && (
            <div className="mt-4 rounded-lg bg-fail-soft p-4 text-sm text-fail">
              <p>
                We couldn't complete your booking this time. Any seats we were holding have been
                freed up again, and <strong>you were not charged</strong>.
              </p>
              <Link
                to={trip ? `/routes/${trip.routeId}?pax=${trip.seats}` : '/trains'}
                className="mt-2 inline-block font-semibold underline"
              >
                Try booking again
              </Link>
            </div>
          )}
        </>
      )}
    </div>
  )
}
