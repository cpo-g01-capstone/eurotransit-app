import { describe, expect, it } from 'vitest'
import {
  cardValid,
  cvcValid,
  expiryValid,
  formatCardNumber,
  formatExpiry,
  isEmailValid,
  panValid,
} from './payment'

describe('panValid', () => {
  it('accepts known-good 16-digit test PANs', () => {
    expect(panValid('4242424242424242')).toBe(true)
    expect(panValid('5555555555554444')).toBe(true)
  })
  it('rejects anything that is not exactly 16 digits', () => {
    expect(panValid('424242424242424')).toBe(false) // 15
    expect(panValid('42424242424242424')).toBe(false) // 17
    expect(panValid('0000000000000000000')).toBe(false) // 19 zeros (reported bug)
    expect(panValid('1234')).toBe(false)
    expect(panValid('')).toBe(false)
  })
  it('rejects checksum failures and degenerate repeated digits', () => {
    expect(panValid('4242424242424241')).toBe(false) // luhn fail
    expect(panValid('0000000000000000')).toBe(false) // luhn-valid but not a real PAN
  })
})

describe('formatting', () => {
  it('groups the PAN in blocks of four and caps input at 16 digits', () => {
    expect(formatCardNumber('4242424242424242')).toBe('4242 4242 4242 4242')
    expect(formatCardNumber('4242 42x42')).toBe('4242 4242')
    expect(formatCardNumber('0000000000000000000')).toBe('0000 0000 0000 0000')
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

describe('isEmailValid (optional checkout email)', () => {
  it('treats empty / whitespace as valid — the field is optional', () => {
    expect(isEmailValid('')).toBe(true)
    expect(isEmailValid('   ')).toBe(true)
  })
  it('accepts a well-formed address', () => {
    expect(isEmailValid('rider@example.com')).toBe(true)
    expect(isEmailValid('  rider@example.com  ')).toBe(true)
  })
  it('rejects a malformed address when one is provided', () => {
    expect(isEmailValid('nope@')).toBe(false)
    expect(isEmailValid('nope')).toBe(false)
    expect(isEmailValid('a@b')).toBe(false)
    expect(isEmailValid('a b@c.com')).toBe(false)
  })
})
