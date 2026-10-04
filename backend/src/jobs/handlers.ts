import { parseJob } from '../archive/publish';
import { cleanupJob } from '../deletion/cleanup';
import { exportJob } from '../exports/job';
import { extractJob } from '../extraction/job';
import { chatJob } from '../chat/job';
import { inferJob } from '../inference/job';
import { notifyJob } from '../notifications/job';
import { triageJob } from '../triage/job';
import type { JobHandler } from './runner';

/**
 * Job kinds run by the queue consumer. `infer`, `chat` and `notify` are not replay-safe once dispatched: it records dispatch before
 * I/O and turns an interrupted dispatch into an uncertain attempt instead of repeating it. External effects
 * are not registered here.
 */
export const JOB_HANDLERS: Readonly<Record<string, JobHandler>> = { parse: parseJob, triage: triageJob, extract: extractJob, cleanup: cleanupJob, export: exportJob, infer: inferJob, chat: chatJob, notify: notifyJob };
