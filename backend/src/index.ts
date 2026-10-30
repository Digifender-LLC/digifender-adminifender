import type { Env } from './env';
import { admitEmail } from './admission/capture';
import { handleFetch } from './http/router';
import { JOB_HANDLERS } from './jobs/handlers';
import { parseQueueEnvelope } from './jobs/queue-envelope';
import { wakeSnoozed } from './concerns/lifecycle';
import { expireExports } from './exports/job';
import { maintainExtraction } from './extraction/device';
import { releaseExpiredReservations } from './inference/budget';
import { scheduleInactivity } from './notifications/events';
import { SCHEDULED_PASS, observe } from './diagnostics/health';
import { reconcile } from './jobs/reconcile';
import { pruneChanges } from './sync/retention';
import { runJob } from './jobs/runner';

import { evaluateHeartbeats, evaluateReadiness } from './reports/obligations';
import { recoveryRunId, runRecovery } from './recovery/recovery';

export default {
  fetch(request, env) {
    return handleFetch(request, env);
  },

  async email(message, env, _ctx) {
    // The live route stays disabled until intake acceptance (tdd.md#preflight-and-release-gates).
    const outcome = await admitEmail(message, env);
    console.log(JSON.stringify({ event: 'email_admission', outcome: outcome.kind }));
    // Only newly stored mail: rejected mail has already bounced, and duplicates were forwarded on first arrival.
    if (env.FORWARD_TO && outcome.kind === 'staged') {
      try {
        await message.forward(env.FORWARD_TO);
      } catch {
        // The stored copy is the record, so a failed forward is logged rather than bounced.
        console.warn(JSON.stringify({ event: 'email_forward_failed' }));
      }
    }
  },

  async queue(batch, env, _ctx) {
    // Recovery mode: D1 stays the job authority, so acknowledged messages are re-published by the reconciler later.
    if (recoveryRunId(env)) {
      for (const message of batch.messages) message.ack();
      return;
    }
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
    const runId = recoveryRunId(env);
    if (runId) {
      // Nothing else runs until the recovery pass has finished and the operator redeploys without the run ID.
      await runRecovery(env, runId, now);
      return;
    }
    // Each step runs whatever happened to the others, so a failing step cannot stop export cleanup (which carries the
    // deletion deadline) or any other upkeep. A failure is not absorbed: it is logged by step name only and the pass then
    // fails, which leaves the pass unrecorded below, so health shows a failing pass as stale and the invocation as errored.
    const steps: [string, () => Promise<unknown>][] = [
      ['reconcile', () => reconcile(env, now)],
      ['wake_snoozed', () => wakeSnoozed(env.CONTROL_DB, now)],
      ['prune_changes', () => pruneChanges(env.CONTROL_DB, now)],
      ['expire_exports', () => expireExports(env, now)],
      ['maintain_extraction', () => maintainExtraction(env, now)],
      ['release_reservations', () => releaseExpiredReservations(env.CONTROL_DB, now)],
      ['schedule_inactivity', () => scheduleInactivity(env.CONTROL_DB, now)],
      // P2 obligations; both return immediately unless reports are enabled.
      ['evaluate_heartbeats', () => evaluateHeartbeats(env, now)],
      ['evaluate_readiness', () => evaluateReadiness(env, now)],
    ];
    const failed: string[] = [];
    for (const [step, run] of steps) {
      try {
        await run();
      } catch {
        failed.push(step);
        console.warn(JSON.stringify({ event: 'scheduled_step_failed', step }));
      }
    }
    if (failed.length > 0) throw new Error(`scheduled pass incomplete: ${failed.join(', ')}`);
    // Recorded only when every step above finished, so health shows a missed or failing pass as stale.
    await observe(env.CONTROL_DB, SCHEDULED_PASS, now);
  },
} satisfies ExportedHandler<Env, unknown>;
