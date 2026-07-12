import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ArrowRight, SearchCheck, Trash2 } from 'lucide-react'
import { getOrder } from '@/api/orders'
import type { OrderResponse, OrderStatus } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Spinner } from '@/components/ui/spinner'
import { formatEUR } from '@/lib/format'
import { isUuid } from '@/lib/uuid'
import { loadTrips, removeTrip, type SavedTrip } from '@/store/trips'

const STATUS_LABEL: Record<OrderStatus, { label: string; tone: 'neutral' | 'wait' | 'go' | 'fail' }> = {
  DRAFT: { label: 'Processing', tone: 'wait' },
  RESERVED: { label: 'Reserved', tone: 'wait' },
  CONFIRMED: { label: 'Confirmed', tone: 'go' },
  FAILED: { label: 'Failed', tone: 'fail' },
}

type LiveStatus = OrderResponse | 'loading' | 'unreachable' | 'unknown-order'

export function TripsPage() {
  const [trips, setTrips] = useState<SavedTrip[]>(() => loadTrips())
  const [live, setLive] = useState<Record<string, LiveStatus>>({})

  // Live status for every remembered trip, straight from GET /api/orders/{id}.
  useEffect(() => {
    let cancelled = false
    for (const trip of trips) {
      if (live[trip.orderId] && live[trip.orderId] !== 'unreachable') continue
      setLive((s) => ({ ...s, [trip.orderId]: 'loading' }))
      getOrder(trip.orderId)
        .then((order) => {
          if (!cancelled) setLive((s) => ({ ...s, [trip.orderId]: order }))
        })
        .catch((error: unknown) => {
          if (cancelled) return
          const status = error instanceof Object && 'status' in error && error.status === 404 ? 'unknown-order' : 'unreachable'
          setLive((s) => ({ ...s, [trip.orderId]: status }))
        })
    }
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refetch only when the trip list changes
  }, [trips])

  function forget(orderId: string) {
    removeTrip(orderId)
    setTrips(loadTrips())
  }

  return (
    <div className="mx-auto w-full max-w-4xl px-4 py-10 sm:px-6">
      <p className="eyebrow text-crimson">My trips</p>
      <h1 className="display-type mt-2 text-4xl">Your bookings</h1>
      <p className="mt-2 max-w-xl text-sm text-steel">
        Bookings made in this browser are saved on this device — no account needed. Their status
        is refreshed every time you open this page.
      </p>

      <LookupForm />

      {trips.length === 0 ? (
        <div className="mt-8 rounded-2xl border border-dashed border-line bg-white p-10 text-center">
          <p className="display-type text-2xl">No trips yet</p>
          <p className="mx-auto mt-2 max-w-md text-sm text-steel">
            Book your first seat and it will show up here with its live status.
          </p>
          <Link to="/trains" className="mt-5 inline-block font-semibold text-crimson hover:underline">
            Browse trains
          </Link>
        </div>
      ) : (
        <div className="mt-8 grid gap-4">
          {trips.map((trip) => {
            const status = live[trip.orderId] ?? 'loading'
            return (
              <Card key={trip.orderId} className="flex flex-wrap items-center justify-between gap-4 p-5">
                <div className="min-w-0">
                  <p className="display-type flex items-center gap-2 text-xl">
                    {trip.origin}
                    <ArrowRight aria-hidden className="size-4 shrink-0 text-crimson" />
                    {trip.destination}
                  </p>
                  <p className="mt-1 text-sm text-steel">
                    {trip.seats} seat{trip.seats === 1 ? '' : 's'} · {formatEUR(trip.totalPrice)} ·
                    placed {new Date(trip.placedAt).toLocaleString()}
                  </p>
                  <p className="mt-1 font-mono text-xs break-all text-steel/80">{trip.orderId}</p>
                </div>
                <div className="flex items-center gap-3">
                  {status === 'loading' ? (
                    <Spinner className="text-steel" />
                  ) : status === 'unreachable' ? (
                    <Badge tone="neutral">Status unavailable</Badge>
                  ) : status === 'unknown-order' ? (
                    <Badge tone="neutral">Not found</Badge>
                  ) : (
                    <Badge tone={STATUS_LABEL[status.status].tone}>{STATUS_LABEL[status.status].label}</Badge>
                  )}
                  <Link
                    to={`/orders/${trip.orderId}`}
                    className="text-sm font-semibold text-crimson hover:underline"
                  >
                    Track
                  </Link>
                  <Button
                    intent="ghost"
                    size="sm"
                    aria-label={`Forget order ${trip.orderId}`}
                    onClick={() => forget(trip.orderId)}
                  >
                    <Trash2 className="size-4" aria-hidden />
                  </Button>
                </div>
              </Card>
            )
          })}
        </div>
      )}
    </div>
  )
}

function LookupForm() {
  const navigate = useNavigate()
  const [value, setValue] = useState('')
  const [error, setError] = useState('')

  function submit(event: FormEvent) {
    event.preventDefault()
    const id = value.trim().toLowerCase()
    // 07b: user input used in navigation is validated before it goes anywhere.
    if (!isUuid(id)) {
      setError("That doesn't look like a booking reference — check it and try again.")
      return
    }
    setError('')
    navigate(`/orders/${id}`)
  }

  return (
    <Card className="mt-6 p-5">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3">
        <label className="grid min-w-0 flex-1 gap-1.5">
          <span className="eyebrow text-steel">Find a booking</span>
          <input
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder="Paste your booking reference"
            spellCheck={false}
            className="h-11 w-full rounded-lg border border-ink/20 bg-white px-3 font-mono text-sm"
          />
        </label>
        <Button type="submit" intent="dark">
          <SearchCheck className="size-4" aria-hidden />
          Track booking
        </Button>
        {error && (
          <p role="alert" className="w-full text-sm text-fail">
            {error}
          </p>
        )}
      </form>
    </Card>
  )
}
