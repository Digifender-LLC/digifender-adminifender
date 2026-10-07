# Dependency security maintenance

Reviewed 2026-10-07 against the committed lockfile with Node 22.22.0. Direct toolchain versions stay pinned.

The development dependency overrides in `package.json` have three purposes:

- `wrangler: "$wrangler"` makes the Workers test pool use the already pinned Wrangler 4.147.0. Its previous nested Wrangler 4.124.0 bundled Undici 7.29.0 inside its JavaScript; overriding the external Undici package alone would leave that copy unchanged. Wrangler 4.147.0 bundles the patched Undici 7.29.1.
- `undici: "7.29.1"` patches Miniflare's external HTTP client without changing either pinned Miniflare/workerd runtime.
- `sharp: "0.35.5"` patches Miniflare's image library, including prebuilt libvips dependencies. This removes duplicate older Sharp copies.

The Workers test pool uses unstable Wrangler configuration APIs. Keep the override only while the full local Workers-runtime suite and dry-run bundle pass. Reassess these overrides when updating either Wrangler or the test pool; do not replace them with the old toolchain versions suggested by `npm audit fix`.

## Advisory scope

The original lockfile contained 12 distinct npm advisories, propagated through five development dependency entries. `npm audit --omit=dev` reported zero findings. The configured local tooling uses synthetic data; the Worker does not configure an Images binding, `cf.image` transformation, Undici WebSocket client, affected interceptor or `BalancedPool`. No production exploit path was established by this static review.

| Component | Official advisory | Fixed version |
| --- | --- | --- |
| Sharp / libheif | [GHSA-rgj7-g3m4-5g8c](https://github.com/advisories/GHSA-rgj7-g3m4-5g8c) | 0.35.4 |
| Sharp / librsvg | [GHSA-wq5f-xc86-pv6w](https://github.com/advisories/GHSA-wq5f-xc86-pv6w) | 0.35.5 |
| Undici / WebSocket decompression | [GHSA-3wwx-pv8p-q78v](https://github.com/advisories/GHSA-3wwx-pv8p-q78v) | 7.29.1 |
| Undici / retry response body | [GHSA-pmjh-fq2x-6v4x](https://github.com/advisories/GHSA-pmjh-fq2x-6v4x) | 7.29.1 |
| Undici / retry response framing | [GHSA-r53p-7pc4-xj5r](https://github.com/advisories/GHSA-r53p-7pc4-xj5r) | 7.29.1 |
| Undici / WebSocket subprotocol | [GHSA-rfgv-xxqx-mfg5](https://github.com/advisories/GHSA-rfgv-xxqx-mfg5) | 7.29.1 |
| Undici / decompress interceptor | [GHSA-3xpg-4rpp-hhhm](https://github.com/advisories/GHSA-3xpg-4rpp-hhhm) | 7.29.1 |
| Undici / shared-cache cookies | [GHSA-2jfj-6hjv-fm6j](https://github.com/advisories/GHSA-2jfj-6hjv-fm6j) | 7.29.1 |
| Undici / dump interceptor | [GHSA-2gqq-gqf2-x968](https://github.com/advisories/GHSA-2gqq-gqf2-x968) | 7.29.1 |
| Undici / custom TLS validation | [GHSA-w293-vg96-wgc3](https://github.com/advisories/GHSA-w293-vg96-wgc3) | 7.29.1 |
| Undici / unsafe-method caching | [GHSA-8436-99hf-9mmv](https://github.com/advisories/GHSA-8436-99hf-9mmv) | 7.29.1 |
| Undici / WebSocketStream close | [GHSA-rx4f-c7p8-82vq](https://github.com/advisories/GHSA-rx4f-c7p8-82vq) | 7.29.1 |

## Verification

From `backend/` with the pinned Node version:

```sh
npm ci
npm ls wrangler miniflare sharp undici
npm run check
npm audit
```

The maintenance checkpoint passed all 313 tests across 27 files, TypeScript checks and a Wrangler dry-run bundle. The resolved dependency tree is valid and npm reports zero advisories after the overrides. This is a local npm result; it does not establish that GitHub's separate Dependabot alert records have closed. No deployment, real input or provider effect was exercised.
