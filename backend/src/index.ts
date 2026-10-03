import type { Env } from './env';
import { admitEmail } from './admission/capture';
import { handleFetch } from './http/router';
import { parseQueueEnvelope } from './jobs/queue-envelope';

export default {
  fetch(request, env) {
    return handleFetch(request, env);
  },

  async email(message, env, _ctx) {
    // The live route stays disabled until intake acceptance (tdd.md#preflight-and-release-gates).
    const outcome = await admitEmail(message, env);
    console.log(JSON.stringify({ event: 'email_admission', outcome: outcome.kind }));
  },

  async queue(batch, _env, _ctx) {
    for (const message of batch.messages) {
      // D1 is the job authority (lld.md#jobs); the queue only carries job IDs. Malformed envelopes are
      // acknowledged so they cannot loop, and due jobs are re-published by the scheduled reconciler.
      const parsed = parseQueueEnvelope(message.body);
      if (!parsed.ok) console.warn(JSON.stringify({ event: 'queue_envelope_rejected', reason: parsed.reason }));
      message.ack();
    }
  },

  async scheduled(_controller, _env, _ctx) {
    // One-minute reconciler; job/outbox scanning arrives with the durable work slice.
  },
} satisfies ExportedHandler<Env, unknown>;
