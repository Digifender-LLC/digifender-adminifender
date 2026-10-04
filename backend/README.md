# Backend Worker

TypeScript Cloudflare Worker with `fetch`, `email`, `queue` and `scheduled` handlers ([TDD](../docs/specifications/tdd.md#backend-and-contracts)). Status: scaffold only; see the [roadmap](../docs/roadmap.md).

```sh
npm ci            # Node version in .nvmrc; frozen lockfile
npm run check     # typecheck, tests, dry-run bundle
```

- `migrations/control/` and `migrations/archive/` are the LLD schema contracts as numbered D1 migrations. Tests apply them to local Miniflare D1 databases.
- `wrangler.jsonc` is local-only. `wrangler dev` also needs `CURSOR_SECRET` (32+ random characters) in an ignored `.dev.vars`; tests supply their own value. It has no staging or production resource IDs and must not be deployed as-is.
- Tests never use production bindings, real mail or paid inference.
