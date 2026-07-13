// en-IE: English formatting with the euro symbol leading (€24.50).
const eur = new Intl.NumberFormat('en-IE', { style: 'currency', currency: 'EUR' })

/** Prices are EUR decimals from the backend (PaymentIntent.currency is fixed to EUR). */
export function formatEUR(amount: number): string {
  return eur.format(amount)
}

const timeFormat = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'Europe/Rome',
})

const dateFormat = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  timeZone: 'Europe/Rome',
})

/** Departure instants are ISO-8601 UTC; shown in Italian local time. */
export function formatTime(isoInstant: string): string {
  return timeFormat.format(new Date(isoInstant))
}

export function formatDate(isoInstant: string): string {
  return dateFormat.format(new Date(isoInstant))
}
