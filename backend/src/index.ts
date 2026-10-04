import type { Env } from './env';
import { admitEmail } from './admission/capture';
import { handleFetch } from './http/router';
import { JOB_HANDLERS } from './jobs/handlers';
import { parseQueueEnvelope } from './jobs/queue-envelope';
import { wakeSnoozed } from './concerns/lifecycle';
import { expireExports } from './exports/job';
import { releaseExpiredReservations } from './inference/budget';
import { scheduleInactivity } from './notifications/events';
import { SCHEDULED_PASS, observe } from './diagnostics/health';
import { reconcile } from './jobs/reconcile';
import { pruneChanges } from './sync/retention';
import { runJob } from './jobs/runner';

export default {
  fetch(request, env) {
    return handleFetch(request, env);
  },

  async email(message, env, _ctx) {
    // The live route stays disabled until intake acceptance (tdd.md#preflight-and-release-gates).
    const outcome = await admitEmail(message, env);
    console.log(JSON.stringify({ event: 'email_admission', outcome: outcome.kind }));
  },

  async queue(batch, env, _ctx) {
    for (const message of batch.messages) {
      // D1 is the job authority (lld.md#jobs); the queue only carries job IDs. Every message is acknowledged:
      // retries are scheduled in D1 and re-published by the scheduled reconciler.
      const parsed = parseQueueEnvelope(message.body);
      if (!parsed.ok) {
        console.warn(JSON.stringify({ event: 'queue_envelope_rejected', reason: parsed.reason }));
      } else {
        try {
          const outcome = await runJob(env, JOB_HANDLERS, parsed.value.jobId);
          console.log(JSON.stringify({ event: 'job_run', status: outcome.status }));
        } catch {
          console.warn(JSON.stringify({ event: 'job_run_error' }));
        }
      }
      message.ack();
    }
  },

  async scheduled(_controller, env, _ctx) {
    const now = Date.now();
    await reconcile(env, now);
    await wakeSnoozed(env.CONTROL_DB, now);
    await pruneChanges(env.CONTROL_DB, now);
    await expireExports(env, now);
    await releaseExpiredReservations(env.CONTROL_DB, now);
    await scheduleInactivity(env.CONTROL_DB, now);
    // Recorded only when every step above finished, so health shows a missed or failing pass as stale.
    await observe(env.CONTROL_DB, SCHEDULED_PASS, now);
  },
} satisfies ExportedHandler<Env, unknown>;
