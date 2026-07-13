import { describe, expect, it } from 'vitest'
import { cardValid, cvcValid, expiryValid, formatCardNumber, formatExpiry, luhnValid } from './payment'

describe('luhnValid', () => {
  it('accepts known-good test PANs', () => {
    expect(luhnValid('4242424242424242')).toBe(true)
    expect(luhnValid('5555555555554444')).toBe(true)
  })
  it('rejects checksum failures and bad lengths', () => {
    expect(luhnValid('4242424242424241')).toBe(false)
    expect(luhnValid('1234')).toBe(false)
    expect(luhnValid('')).toBe(false)
    expect(luhnValid('42424242424242424242')).toBe(false)
  })
})

describe('formatting', () => {
  it('groups the PAN in blocks of four', () => {
    expect(formatCardNumber('4242424242424242')).toBe('4242 4242 4242 4242')
    expect(formatCardNumber('4242 42x42')).toBe('4242 4242')
  })
  it('inserts the expiry slash', () => {
    expect(formatExpiry('1229')).toBe('12/29')
    expect(formatExpiry('12')).toBe('12')
    expect(formatExpiry('12/29')).toBe('12/29')
  })
})

describe('expiryValid', () => {
  const now = new Date(2026, 6, 13) // 13 Jul 2026
  it('accepts future and current-month expiries', () => {
    expect(expiryValid('12/29', now)).toBe(true)
    expect(expiryValid('07/26', now)).toBe(true) // valid through end of month
  })
  it('rejects past dates and malformed input', () => {
    expect(expiryValid('06/26', now)).toBe(false)
    expect(expiryValid('13/29', now)).toBe(false)
    expect(expiryValid('00/29', now)).toBe(false)
    expect(expiryValid('129', now)).toBe(false)
  })
})

describe('cardValid', () => {
  it('requires every field to pass', () => {
    const good = { holder: 'Marco Donatucci', number: '4242 4242 4242 4242', expiry: '12/29', cvc: '123' }
    expect(cardValid(good)).toBe(true)
    expect(cardValid({ ...good, holder: ' ' })).toBe(false)
    expect(cardValid({ ...good, number: '4242' })).toBe(false)
    expect(cardValid({ ...good, expiry: '01/20' })).toBe(false)
    expect(cardValid({ ...good, cvc: '12' })).toBe(false)
  })
})

describe('cvcValid', () => {
  it('accepts 3-4 digits only', () => {
    expect(cvcValid('123')).toBe(true)
    expect(cvcValid('1234')).toBe(true)
    expect(cvcValid('12')).toBe(false)
    expect(cvcValid('12a')).toBe(false)
  })
})
