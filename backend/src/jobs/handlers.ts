import { parseJob } from '../archive/publish';
import type { JobHandler } from './runner';

/** Replay-safe job kinds run by the queue consumer. Inference and external effects are not registered here. */
export const JOB_HANDLERS: Readonly<Record<string, JobHandler>> = { parse: parseJob };
