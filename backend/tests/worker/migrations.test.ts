import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';

const now = 1_791_000_000_000;

async function tableNames(db: D1Database): Promise<string[]> {
  const { results } = await db.prepare("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '\\_cf\\_%' ESCAPE '\\' AND name NOT LIKE 'd1_%' ORDER BY name").all<{ name: string }>();
  return results.map((row) => row.name);
}

describe('control migration', () => {
  it('creates every LLD control table', async () => {
    const names = await tableNames(env.CONTROL_DB);
    for (const table of ['owner', 'passkeys', 'devices', 'sessions', 'source_registry', 'block_rules', 'concerns', 'jobs', 'outbox', 'changes', 'mutations', 'operations', 'action_reviews', 'deletion_ledger', 'audit_events']) {
      expect(names).toContain(table);
    }
  });

  it('permits only the singleton owner row', async () => {
    await env.CONTROL_DB.prepare('INSERT INTO owner(id, created_at) VALUES (?, ?)').bind('owner', now).run();
    await expect(env.CONTROL_DB.prepare('INSERT INTO owner(id, created_at) VALUES (?, ?)').bind('second', now).run()).rejects.toThrow(/CHECK/);
  });

  it('seeds ARCHIVE_0001 as the only writable archive partition', async () => {
    const seeded = await env.CONTROL_DB.prepare("SELECT id, binding_name FROM archive_partitions WHERE state = 'writable'").all();
    expect(seeded.results).toEqual([{ id: 1, binding_name: 'ARCHIVE_0001' }]);
    const insert = env.CONTROL_DB.prepare('INSERT INTO archive_partitions(id, binding_name, state, created_at) VALUES (?, ?, ?, ?)');
    await insert.bind(2, 'ARCHIVE_0002', 'sealed', now).run();
    await expect(insert.bind(3, 'ARCHIVE_0003', 'writable', now).run()).rejects.toThrow(/UNIQUE/);
  });

  it('rejects unknown admission and privacy values', async () => {
    const insert = env.CONTROL_DB.prepare(
      'INSERT INTO source_registry(id, kind, external_key, content_hash, admission, privacy, state, revision, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)',
    );
    await expect(insert.bind('s1', 'email', 'k1', 'h', 'trusted', 'hybrid', 'staged', now).run()).rejects.toThrow(/CHECK/);
    await expect(insert.bind('s2', 'email', 'k2', 'h', 'normal', 'cloud_ok', 'staged', now).run()).rejects.toThrow(/CHECK/);
    await insert.bind('s3', 'email', 'k3', 'h', 'quarantine', 'hybrid', 'staged', now).run();
    await expect(insert.bind('s4', 'email', 'k3', 'h', 'normal', 'hybrid', 'staged', now).run()).rejects.toThrow(/UNIQUE/);
  });

  it('enforces foreign keys without the LLD PRAGMA line', async () => {
    await expect(
      env.CONTROL_DB.prepare('INSERT INTO concern_sources(concern_id, source_id) VALUES (?, ?)').bind('missing', 'missing').run(),
    ).rejects.toThrow(/FOREIGN KEY/);
  });

  it('adds the export scope without giving older exports one', async () => {
    const insert = env.CONTROL_DB.prepare("INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at) VALUES ('old', 0, 'ready', ?, ?)");
    await insert.bind(now, now + 1).run();
    // An export written before the scope existed stays unscoped; nothing fills in a default.
    expect(await env.CONTROL_DB.prepare("SELECT scope_json, invalidated_reason, attempt, settled_at, cleanup_next_at FROM exports WHERE id = 'old'").first()).toEqual({
      scope_json: null,
      invalidated_reason: null,
      attempt: null,
      settled_at: null,
      cleanup_next_at: null,
    });
    await expect(env.CONTROL_DB.prepare("INSERT INTO export_sources(export_id, attempt, source_id) VALUES ('missing', 'a', 'missing')").run()).rejects.toThrow(/FOREIGN KEY/);
    // Cleanup bookkeeping that nothing reads is not stored, and unfinished multipart uploads have a record of their own.
    const columns = (await env.CONTROL_DB.prepare('PRAGMA table_info(exports)').all<{ name: string }>()).results.map((c) => c.name);
    expect(columns).not.toContain('swept_at');
    expect(columns).not.toContain('swept_full');
    await expect(env.CONTROL_DB.prepare("INSERT INTO export_uploads(export_id, attempt, upload_id, object_key, created_at) VALUES ('missing', 'a', 'u', 'k', 1)").run()).rejects.toThrow(/FOREIGN KEY/);
    await env.CONTROL_DB.prepare("INSERT INTO export_uploads(export_id, attempt, upload_id, object_key, created_at) VALUES ('old', 'a', 'u', 'k', 1)").run();
  });

  it('looks up cleanup progress by operation through an index', async () => {
    const { results } = await env.CONTROL_DB.prepare("EXPLAIN QUERY PLAN SELECT source_id FROM deletion_ledger WHERE json_extract(targets_json, '$.operationId') = ?").bind('op').all<{ detail: string }>();
    expect(results.map((r) => r.detail).join(' ')).toContain('deletion_ledger_operation');
  });

  it('keeps job dedupe keys unique', async () => {
    const insert = env.CONTROL_DB.prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES (?, ?, 'parse', 'pending', ?, '{}', ?)");
    await insert.bind('j1', 'parse:h:v1', now, now).run();
    await expect(insert.bind('j2', 'parse:h:v1', now, now).run()).rejects.toThrow(/UNIQUE/);
  });
});

describe('archive migration', () => {
  it('creates source, attachment, chunk, receipt and FTS tables', async () => {
    const names = await tableNames(env.ARCHIVE_0001);
    for (const table of ['sources', 'attachments', 'chunks', 'archive_receipts', 'source_fts']) expect(names).toContain(table);
  });

  it('requires inline text or an R2 text object', async () => {
    await expect(
      env.ARCHIVE_0001.prepare(
        "INSERT INTO sources(id, kind, received_at, subject, envelope_json, headers_json, text_hash, provenance_json, parser_version) VALUES ('s1', 'email', ?, 'x', '{}', '{}', 'h', '{}', 'p1')",
      ).bind(now).run(),
    ).rejects.toThrow(/CHECK/);
  });

  it('indexes chunk text for exact search', async () => {
    await env.ARCHIVE_0001.batch([
      env.ARCHIVE_0001.prepare(
        "INSERT INTO sources(id, kind, received_at, subject, envelope_json, headers_json, normalized_text, text_hash, provenance_json, parser_version) VALUES ('s2', 'email', ?, 'Invoice', '{}', '{}', 'Synthetic invoice body', 'h', '{}', 'p1')",
      ).bind(now),
      env.ARCHIVE_0001.prepare(
        "INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) VALUES ('s2', 's2:body-v1:0', 'body-v1', 'Invoice', 'Synthetic invoice body', '')",
      ),
    ]);
    const row = await env.ARCHIVE_0001.prepare("SELECT source_id FROM source_fts WHERE source_fts MATCH '\"invoice\"'").first<{ source_id: string }>();
    expect(row?.source_id).toBe('s2');
  });
});
