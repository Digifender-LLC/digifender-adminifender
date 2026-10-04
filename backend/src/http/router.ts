import * as admission from '../admission/routes';
import * as auth from '../auth/routes';
import * as briefings from '../briefings/routes';
import * as grouping from '../concerns/grouping';
import * as concerns from '../concerns/routes';
import { type AuthContext, authenticate, hasRecentAuth } from '../auth/sessions';
import { RELEASE_CONTRACT } from '../contracts';
import type { Env } from '../env';
import * as searchRoutes from '../search/routes';
import * as sources from '../sources/routes';
import * as sync from '../sync/routes';
import * as reviews from '../reviews/routes';
import * as rules from '../triage/routes';
import { fail, ok } from './envelope';
import { HttpError } from './errors';
import { withIdempotency } from './idempotency';
import { type Validator, readJson } from './validate';

const PREFIX = '/api/v1';
const ID = '([0-9a-f-]{36})';

type Handler = (request: Request, env: Env, now: number, params: string[]) => Promise<unknown>;
type MutationHandler<B> = (env: Env, now: number, body: B, ...params: string[]) => Promise<unknown>;

interface Route {
  method: 'GET' | 'POST' | 'PATCH';
  pattern: RegExp;
  handler: Handler;
}

/** Authenticated, idempotent JSON mutation (lld.md#contract-conventions). */
function mutation<B>(validator: Validator<B>, handler: MutationHandler<B>): Handler {
  return async (request, env, now, params) => {
    await authenticate(request, env, now);
    const body = await readJson(request, validator);
    const path = new URL(request.url).pathname;
    const stored = await withIdempotency(env.CONTROL_DB, request, path, body, now, async () => ({ status: 200, body: { data: await handler(env, now, body, ...params) } }));
    return new StoredResponse(stored.status, stored.body as Record<string, unknown>);
  };
}

/** Like `mutation`, passing the session to the handler and optionally requiring recent passkey authentication. */
function ownerMutation<B>(validator: Validator<B>, handler: (env: Env, now: number, body: B, auth: AuthContext, ...params: string[]) => Promise<unknown>, options: { recentAuth: boolean }): Handler {
  return async (request, env, now, params) => {
    const auth = await authenticate(request, env, now);
    if (options.recentAuth && !hasRecentAuth(auth, now)) throw new HttpError(403, 'recent_auth_required', 'Confirm with your passkey first.');
    const body = await readJson(request, validator);
    const path = new URL(request.url).pathname;
    const stored = await withIdempotency(env.CONTROL_DB, request, path, body, now, async () => ({ status: 200, body: { data: await handler(env, now, body, auth, ...params) } }));
    return new StoredResponse(stored.status, stored.body as Record<string, unknown>);
  };
}

class StoredResponse {
  constructor(
    readonly status: number,
    readonly body: Record<string, unknown>,
  ) {}
}

const ROUTES: Route[] = [
  { method: 'GET', pattern: /^\/meta$/, handler: async () => ({ contract: RELEASE_CONTRACT }) },
  { method: 'POST', pattern: /^\/auth\/bootstrap\/options$/, handler: auth.bootstrapOptions },
  { method: 'POST', pattern: /^\/auth\/bootstrap\/verify$/, handler: auth.bootstrapVerify },
  { method: 'POST', pattern: /^\/auth\/passkey\/options$/, handler: auth.passkeyOptions },
  { method: 'POST', pattern: /^\/auth\/passkey\/verify$/, handler: auth.passkeyVerify },
  { method: 'POST', pattern: /^\/auth\/refresh$/, handler: auth.refresh },
  { method: 'POST', pattern: /^\/auth\/recovery\/options$/, handler: auth.recoveryOptions },
  { method: 'POST', pattern: /^\/auth\/recovery\/verify$/, handler: auth.recoveryVerify },
  { method: 'POST', pattern: /^\/auth\/logout$/, handler: auth.logout },
  { method: 'GET', pattern: /^\/devices$/, handler: auth.listDevices },
  { method: 'GET', pattern: /^\/sources$/, handler: sources.listSources },
  { method: 'GET', pattern: new RegExp(`^/sources/${ID}$`), handler: sources.getSource },
  { method: 'GET', pattern: new RegExp(`^/sources/${ID}/chunks$`), handler: sources.getChunks },
  { method: 'POST', pattern: new RegExp(`^/sources/${ID}/quarantine$`), handler: mutation(admission.quarantineBody, admission.quarantineSource) },
  { method: 'POST', pattern: new RegExp(`^/sources/${ID}/release$`), handler: mutation(admission.releaseBody, admission.releaseSource) },
  { method: 'GET', pattern: new RegExp(`^/attachments/${ID}/content$`), handler: sources.getAttachmentContent },
  { method: 'GET', pattern: /^\/search$/, handler: searchRoutes.search },
  { method: 'GET', pattern: /^\/block-rules$/, handler: admission.listBlockRules },
  { method: 'POST', pattern: /^\/block-rules$/, handler: mutation(admission.blockRuleBody, admission.createBlockRule) },
  { method: 'PATCH', pattern: new RegExp(`^/block-rules/${ID}$`), handler: mutation(admission.blockRulePatch, admission.updateBlockRule) },
  { method: 'GET', pattern: /^\/spam-overrides$/, handler: admission.listSpamOverrides },
  { method: 'POST', pattern: /^\/spam-overrides$/, handler: mutation(admission.spamOverrideBody, admission.createSpamOverride) },
  { method: 'PATCH', pattern: new RegExp(`^/spam-overrides/${ID}$`), handler: mutation(admission.spamOverridePatch, admission.updateSpamOverride) },
  { method: 'POST', pattern: /^\/reviews$/, handler: ownerMutation(reviews.reviewBody, reviews.createReview, { recentAuth: false }) },
  { method: 'POST', pattern: new RegExp(`^/reviews/${ID}/confirm$`), handler: ownerMutation(reviews.confirmBody, reviews.confirmReview, { recentAuth: true }) },
  { method: 'GET', pattern: new RegExp(`^/operations/${ID}$`), handler: reviews.getOperation },
  { method: 'GET', pattern: /^\/sync\/bootstrap$/, handler: sync.bootstrap },
  { method: 'GET', pattern: /^\/sync\/changes$/, handler: sync.changes },
  { method: 'GET', pattern: /^\/rules$/, handler: rules.listRules },
  { method: 'POST', pattern: /^\/rules$/, handler: mutation(rules.ruleBody, rules.createRule) },
  { method: 'PATCH', pattern: new RegExp(`^/rules/${ID}$`), handler: mutation(rules.rulePatch, rules.updateRule) },
  { method: 'GET', pattern: /^\/concerns$/, handler: concerns.listConcerns },
  { method: 'GET', pattern: new RegExp(`^/concerns/${ID}$`), handler: concerns.getConcern },
  { method: 'POST', pattern: new RegExp(`^/concerns/${ID}/actions$`), handler: mutation(concerns.actionBody, concerns.concernAction) },
  { method: 'POST', pattern: new RegExp(`^/concerns/${ID}/undo$`), handler: mutation(concerns.undoBody, concerns.undoConcernEvent) },
  { method: 'POST', pattern: new RegExp(`^/concerns/${ID}/corrections$`), handler: mutation(grouping.correctionBody, grouping.correctConcern) },
  { method: 'POST', pattern: new RegExp(`^/concerns/${ID}/split$`), handler: mutation(grouping.splitBody, grouping.splitConcern) },
  { method: 'POST', pattern: /^\/concerns\/merge$/, handler: mutation(grouping.mergeBody, grouping.mergeConcerns) },
  { method: 'GET', pattern: /^\/briefings\/current$/, handler: briefings.currentBriefing },
  { method: 'POST', pattern: new RegExp(`^/briefings/${ID}/review$`), handler: mutation(briefings.reviewBody, briefings.reviewBriefing) },
];

export async function handleFetch(request: Request, env: Env, now = Date.now()): Promise<Response> {
  const requestId = crypto.randomUUID();
  const { pathname } = new URL(request.url);
  if (!pathname.startsWith(`${PREFIX}/`)) return fail(404, 'not_found', 'Not found.', requestId);
  const path = pathname.slice(PREFIX.length);

  try {
    for (const route of ROUTES) {
      const match = route.pattern.exec(path);
      if (!match || route.method !== request.method) continue;
      const result = await route.handler(request, env, now, match.slice(1));
      if (result instanceof Response) return result;
      if (result instanceof StoredResponse) {
        return new Response(JSON.stringify({ ...result.body, requestId }), {
          status: result.status,
          headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' },
        });
      }
      return ok(result, requestId);
    }
    return fail(404, 'not_found', 'Not found.', requestId);
  } catch (error) {
    if (error instanceof HttpError) {
      const response = fail(error.status, error.code, error.message, requestId, { retryable: error.retryable, details: error.details });
      const retryAfter = error.details.retryAfterSeconds;
      if (error.status === 429 && typeof retryAfter === 'number') response.headers.set('retry-after', String(retryAfter));
      return response;
    }
    // Content-free: the cause is not echoed to the caller.
    console.error(JSON.stringify({ event: 'http_unhandled' }));
    return fail(503, 'unavailable', 'The service is temporarily unavailable.', requestId, { retryable: true });
  }
}
