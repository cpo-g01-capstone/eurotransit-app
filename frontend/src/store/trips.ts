/**
 * Client-side memory of orders this browser has placed, so "My trips" can
 * re-fetch their live status (the backend has no list-orders endpoint and no
 * user accounts).
 *
 * 07b compliance: localStorage is readable by any script on the page, so it
 * must never hold tokens or credentials. What we store here are only order
 * UUIDs and the trip facts this client itself submitted — public-ish handles,
 * not secrets. Live status is always re-fetched from GET /api/orders/{id}.
 */

export interface SavedTrip {
  orderId: string
  routeId: string
  origin: string
  destination: string
  seats: number
  totalPrice: number
  placedAt: string
}

const KEY = 'eurotransit.trips.v1'

export function loadTrips(): SavedTrip[] {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(isSavedTrip)
  } catch {
    return []
  }
}

export function saveTrip(trip: SavedTrip): void {
  const trips = loadTrips().filter((t) => t.orderId !== trip.orderId)
  trips.unshift(trip)
  persist(trips)
}

export function removeTrip(orderId: string): void {
  persist(loadTrips().filter((t) => t.orderId !== orderId))
}

function persist(trips: SavedTrip[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(trips))
  } catch {
    // Storage full/blocked: the app still works, trips just aren't remembered.
  }
}

function isSavedTrip(value: unknown): value is SavedTrip {
  if (typeof value !== 'object' || value === null) return false
  const t = value as Record<string, unknown>
  return (
    typeof t.orderId === 'string' &&
    typeof t.routeId === 'string' &&
    typeof t.origin === 'string' &&
    typeof t.destination === 'string' &&
    typeof t.seats === 'number' &&
    typeof t.totalPrice === 'number' &&
    typeof t.placedAt === 'string'
  )
}
