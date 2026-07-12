/**
 * 07b: never trust user input or URL params — the order/route IDs users type
 * or paste are validated as UUIDs before they reach a fetch or a router
 * navigation, so they can't smuggle paths or schemes.
 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isUuid(value: string): boolean {
  return UUID_RE.test(value)
}
