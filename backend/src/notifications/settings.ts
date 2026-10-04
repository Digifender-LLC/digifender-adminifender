import { HttpError } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { type QuietHours, intervalMinutes, isZone } from './zone';

/** Notification policy (baseline.md defaults, lld.md#settings). */
export interface NotificationSettings {
  channels: { critical: boolean; due: boolean; inactivity: boolean };
  /** IANA zone for quiet hours and the inactivity date; onboarding proposes the phone's zone. */
  zone: string;
  /** Local interval, at most 16 hours; null disables quiet hours. Critical notifications always bypass it. */
  quietHours: QuietHours | null;
  /** Meaningful-review inactivity before a reminder, and the minimum gap between reminders. */
  inactivityHours: number;
  cooldownHours: number;
}

export const DEFAULT_NOTIFICATIONS: NotificationSettings = {
  channels: { critical: true, due: true, inactivity: true },
  zone: 'UTC',
  quietHours: { start: '22:00', end: '08:00' },
  inactivityHours: 24,
  cooldownHours: 24,
};

export const MAX_QUIET_MINUTES = 16 * 60;

const clock = v.string({ max: 5, pattern: /^([01]\d|2[0-3]):[0-5]\d$/ });
const hours = v.integer({ min: 1, max: 168 });

const quietShape: Validator<QuietHours> = (value, path) => {
  const quiet = v.object({ start: clock, end: clock })(value, path);
  const span = intervalMinutes(quiet.start, quiet.end);
  if (span === 0 || span > MAX_QUIET_MINUTES) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path: `${path}.end`, reason: 'quiet_interval' });
  return quiet;
};

const zoneShape: Validator<string> = (value, path) => {
  const zone = v.string({ max: 64 })(value, path);
  if (!isZone(zone)) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'unknown_zone' });
  return zone;
};

export const notificationsShape: Validator<NotificationSettings> = v.object({
  channels: v.object({ critical: v.boolean(), due: v.boolean(), inactivity: v.boolean() }),
  zone: zoneShape,
  quietHours: v.nullable(quietShape),
  inactivityHours: hours,
  cooldownHours: hours,
});
