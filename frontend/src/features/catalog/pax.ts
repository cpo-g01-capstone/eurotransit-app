export const MAX_PASSENGERS = 8

/** Untrusted input (URL param / form) → safe integer in [1, MAX_PASSENGERS]. */
export function clampPax(n: number): number {
  if (!Number.isFinite(n)) return 1
  return Math.min(MAX_PASSENGERS, Math.max(1, Math.trunc(n)))
}
