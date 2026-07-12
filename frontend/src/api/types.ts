/**
 * DTOs mirroring the Kotlin backend contracts exactly.
 *
 * catalog-service  → RouteCache.CatalogRoute
 * orders-service   → OrderEvents.CreateOrderRequest / OrderResponse, Order.OrderStatus
 *
 * The backend sends no error bodies (no problem+json): clients key off HTTP
 * status codes alone.
 */

export interface CatalogRoute {
  id: string
  origin: string
  destination: string
  /** ISO-8601 UTC instant, e.g. "2026-07-19T05:36:14Z" */
  departureTime: string
  totalSeats: number
  /** Advisory only — eventually consistent; inventory-service is the source of truth. */
  availableSeats: number
  /** EUR, decimal JSON number (e.g. 19.90). */
  price: number
}

export const ORDER_STATUSES = ['DRAFT', 'RESERVED', 'CONFIRMED', 'FAILED'] as const
export type OrderStatus = (typeof ORDER_STATUSES)[number]

export interface CreateOrderRequest {
  routeId: string
  seats: number
}

export interface OrderResponse {
  orderId: string
  status: OrderStatus
  /** "Order accepted for processing" on POST; empty string on GET. */
  message: string
}

/** Terminal states — polling stops here. */
export function isTerminal(status: OrderStatus): boolean {
  return status === 'CONFIRMED' || status === 'FAILED'
}
