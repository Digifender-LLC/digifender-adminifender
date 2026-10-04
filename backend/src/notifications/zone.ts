/**
 * Wall-clock helpers for one IANA zone (lld.md#notifications). The runtime's Intl time-zone data does the
 * offset and DST work; nothing here assumes a fixed offset or a 24-hour day.
 */

const formatters = new Map<string, Intl.DateTimeFormat>();

function formatter(zone: string): Intl.DateTimeFormat {
  let f = formatters.get(zone);
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', { timeZone: zone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
    formatters.set(zone, f);
  }
  return f;
}

/** True for a zone name the runtime's IANA database knows. Offsets like `+02:00` and abbreviations are rejected. */
export function isZone(zone: string): boolean {
  if (!/^[A-Za-z][A-Za-z0-9_+-]*(\/[A-Za-z0-9_+-]+){0,2}$/.test(zone)) return false;
  try {
    formatter(zone);
    return true;
  } catch {
    return false;
  }
}

export interface LocalTime {
  date: string;
  minutes: number;
}

/** Local calendar date (YYYY-MM-DD) and minute of day at an instant. */
export function localTime(at: number, zone: string): LocalTime {
  const parts = Object.fromEntries(formatter(zone).formatToParts(new Date(at)).map((p) => [p.type, p.value]));
  return { date: `${parts.year}-${parts.month}-${parts.day}`, minutes: Number(parts.hour) * 60 + Number(parts.minute) };
}

export const parseClock = (clock: string): number => Number(clock.slice(0, 2)) * 60 + Number(clock.slice(3, 5));

/** Minutes covered by a local interval that may wrap past midnight. */
export const intervalMinutes = (start: string, end: string): number => (parseClock(end) - parseClock(start) + 1440) % 1440;

export interface QuietHours {
  start: string;
  end: string;
}

/** Whether local wall time at `at` falls inside [start, end), wrapping past midnight. */
export function inQuietHours(at: number, zone: string, quiet: QuietHours | null): boolean {
  if (!quiet || intervalMinutes(quiet.start, quiet.end) === 0) return false;
  const m = localTime(at, zone).minutes;
  const s = parseClock(quiet.start);
  const e = parseClock(quiet.end);
  return s < e ? m >= s && m < e : m >= s || m < e;
}

const STEP_MS = 15 * 60 * 1000;
const MINUTE_MS = 60 * 1000;

/**
 * First instant at or after `at` outside quiet hours. Walks forward in 15-minute steps (every zone offset is a
 * multiple of 15 minutes), then narrows to the minute, so a DST jump that skips or repeats the end time still
 * resolves to the first real local minute after quiet hours.
 */
export function quietEnd(at: number, zone: string, quiet: QuietHours | null): number {
  if (!inQuietHours(at, zone, quiet)) return at;
  let t = at;
  // A quiet interval is at most 16 hours; 26 hours covers a DST repeat as well.
  for (let i = 0; i < 26 * 4 && inQuietHours(t, zone, quiet); i++) t += STEP_MS;
  let low = t - STEP_MS;
  let high = t;
  while (high - low > MINUTE_MS) {
    const mid = low + Math.floor((high - low) / 2 / MINUTE_MS) * MINUTE_MS;
    if (inQuietHours(mid, zone, quiet)) low = mid;
    else high = mid;
  }
  // Quiet hours end on a whole minute B with low < B <= high and high - low <= 1 minute, so flooring gives B.
  return Math.floor(high / MINUTE_MS) * MINUTE_MS;
}
