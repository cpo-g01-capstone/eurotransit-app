import { apiFetch } from './client'
import type { CatalogRoute } from './types'

/** GET /api/catalog — full route list, server-sorted by origin. */
export async function getRoutes(signal?: AbortSignal): Promise<CatalogRoute[]> {
  const { body } = await apiFetch<CatalogRoute[]>('/catalog', { signal })
  return body
}

/** GET /api/catalog/{id} — throws ApiError(404) for unknown routes. */
export async function getRoute(id: string, signal?: AbortSignal): Promise<CatalogRoute> {
  const { body } = await apiFetch<CatalogRoute>(`/catalog/${encodeURIComponent(id)}`, { signal })
  return body
}
