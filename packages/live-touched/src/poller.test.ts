import { expect, it } from 'vitest'
import { nextPollDelay, POLL_MS } from './poller.js'

it('adapts the poll interval', () => {
  expect(nextPollDelay({ othersNearby: false, emptyStreak: 0, errorStreak: 0 })).toBe(POLL_MS.start)
  expect(nextPollDelay({ othersNearby: true, emptyStreak: 0, errorStreak: 0 })).toBe(
    POLL_MS.othersNearby,
  )
  expect(nextPollDelay({ othersNearby: false, emptyStreak: 3, errorStreak: 0 })).toBe(POLL_MS.idle)
  expect(nextPollDelay({ othersNearby: false, emptyStreak: 0, errorStreak: 1 })).toBe(16_000)
  expect(nextPollDelay({ othersNearby: false, emptyStreak: 0, errorStreak: 5 })).toBe(
    POLL_MS.maxBackoff,
  )
})
