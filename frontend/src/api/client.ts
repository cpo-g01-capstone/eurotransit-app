/**
 * Single audited network boundary for the whole app.
 *
 * Security notes (07b-Frontend Security):
 * - Requests are always same-origin under /api → Same-Origin Policy applies,
 *   no CORS headers needed, and CSP `connect-src 'self'` stays sufficient.
 * - The backend exposes no authentication, so no tokens exist anywhere in
 *   this app: nothing to store, nothing an XSS could steal. If auth is ever
 *   added server-side, keep access tokens in memory and refresh tokens in an
 *   httpOnly cookie — never localStorage.
 * - Error responses carry no body; we surface status codes with our own
 *   user-facing messages.
 */

export const API_BASE = '/api'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

export interface ApiResult<T> {
  status: number
  body: T
  headers: Headers
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<ApiResult<T>> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })

  if (!response.ok) {
    throw new ApiError(response.status, `Request failed with status ${response.status}`)
  }

  return {
    status: response.status,
    body: (await response.json()) as T,
    headers: response.headers,
  }
}
