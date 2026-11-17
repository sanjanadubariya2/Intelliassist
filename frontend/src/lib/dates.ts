/**
 * Calendar dates as the backend counts them.
 *
 * Usage, insights and the resolution trend are bucketed by UTC day, so every
 * range sent to the API is in UTC too. The old helpers mixed local and UTC
 * arithmetic: `new Date(y, m, 1).toISOString()` is the 31st of the previous
 * month anywhere east of Greenwich, which pulled a day of last month into
 * "this month's" billing.
 */

/** Today, as YYYY-MM-DD in UTC. */
export function utcToday(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The UTC date `days` before today. */
export function utcDaysAgo(days: number): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - days);
  return date.toISOString().slice(0, 10);
}

/** The first day of the current UTC month. */
export function utcMonthStart(): string {
  const now = new Date();
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1)).toISOString().slice(0, 10);
}

/**
 * "7 Nov" for a YYYY-MM-DD string.
 *
 * Formatted in UTC: `new Date("2026-11-07")` is midnight UTC, which a local
 * formatter west of Greenwich would label the 6th.
 */
export function shortDay(value: string): string {
  return new Date(value).toLocaleDateString(undefined, {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
  });
}
