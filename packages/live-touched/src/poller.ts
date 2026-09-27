export const POLL_MS = {
  start: 8_000,
  othersNearby: 4_000,
  idle: 10_000,
  maxBackoff: 60_000,
}

/** Empty answers in a row before switching to the idle interval. */
export const EMPTY_STREAK_FOR_IDLE = 3

export type PollState = { othersNearby: boolean; emptyStreak: number; errorStreak: number }

/** Delay until the next poll. Errors back off exponentially (max 60 s). */
export function nextPollDelay(s: PollState): number {
  if (s.errorStreak > 0) return Math.min(POLL_MS.maxBackoff, POLL_MS.start * 2 ** s.errorStreak)
  if (s.othersNearby) return POLL_MS.othersNearby
  if (s.emptyStreak >= EMPTY_STREAK_FOR_IDLE) return POLL_MS.idle
  return POLL_MS.start
}
