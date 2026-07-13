/**
 * Client-side card validation for the checkout payment step.
 *
 * The backend has no card endpoint: payment authorisation is performed
 * server-side by the payments service during the order saga (ADR 0018), and
 * no card data is part of that contract. Card details here are validated
 * locally for UX only (07b: client validates, server is truth), live in
 * component memory only, and are NEVER sent, stored, or logged.
 */

/** Luhn checksum over a 13-19 digit PAN. */
export function luhnValid(digits: string): boolean {
  if (!/^\d{13,19}$/.test(digits)) return false
  let sum = 0
  let double = false
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48
    if (double) {
      d *= 2
      if (d > 9) d -= 9
    }
    sum += d
    double = !double
  }
  return sum % 10 === 0
}

export function digitsOf(value: string): string {
  return value.replace(/\D/g, '')
}

/** "4242424242424242" → "4242 4242 4242 4242" (as-you-type formatting). */
export function formatCardNumber(raw: string): string {
  return digitsOf(raw)
    .slice(0, 19)
    .replace(/(\d{4})(?=\d)/g, '$1 ')
}

/** "1229" / "12/29" → "12/29" (as-you-type formatting). */
export function formatExpiry(raw: string): string {
  const d = digitsOf(raw).slice(0, 4)
  return d.length > 2 ? `${d.slice(0, 2)}/${d.slice(2)}` : d
}

/** MM/YY, valid through the last day of the expiry month. */
export function expiryValid(value: string, now: Date = new Date()): boolean {
  const match = /^(\d{2})\/(\d{2})$/.exec(value.trim())
  if (!match) return false
  const month = Number(match[1])
  const year = 2000 + Number(match[2])
  if (month < 1 || month > 12) return false
  return new Date(year, month, 1) > now
}

export function cvcValid(value: string): boolean {
  return /^\d{3,4}$/.test(value)
}

export interface CardDetails {
  holder: string
  number: string
  expiry: string
  cvc: string
}

export function cardValid(card: CardDetails): boolean {
  return (
    card.holder.trim().length >= 2 &&
    luhnValid(digitsOf(card.number)) &&
    expiryValid(card.expiry) &&
    cvcValid(card.cvc)
  )
}
