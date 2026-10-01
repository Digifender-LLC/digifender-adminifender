# ADR-0008 — Evidence-preserving extraction and progressive retrieval

- **Status:** Accepted design, not implemented.
- **Date:** October 1, 2026.
- **Supersedes:** None.
- **Superseded by:** None.

## Context

The archive must be useful without AI. Attachments need text extraction and image/scanned-document OCR, but paid universal OCR would add cost and automatic format handling would enlarge the parser attack surface. Embeddings improve some retrieval tasks but cannot replace exact text, source authorization or deletion.

## Decision

Retain original attachment bytes independently from extraction status. Use bounded Worker parsers for the selected text/document formats and Android PDF/image processing with the baseline APIs and limits. PDF native text extraction is gated to supported API levels; older supported Android versions render pages and use OCR. Unsupported/encrypted/oversized files remain downloadable with an explicit unextracted status. No server paid-OCR fallback is selected. No-AI sources skip OCR as well as LLM/embedding work; deterministic text parsing remains allowed.

Index normalized and extracted text using archive-partition D1 FTS and synchronized Room FTS. Chunks carry source revision, immutable extraction generation, hash and character/page locators. Archive writes precede a fenced CONTROL_DB `active_generation` publication; readers exclude every unpublished/stale generation, so late device writes cannot replace current extraction. Search filters and export do not require a model. Quarantined sources are available only through explicit quarantine inspection and never enter ordinary chat.

In P3, use the baseline embedding model and Vectorize for candidate retrieval. Enforce its token/dimension limits and recheck CONTROL_DB visibility/privacy/deletion state before returning source chunks. Embeddings and summaries are rebuildable derivations, not the archive.

## Alternatives rejected

- **Embeddings-only retrieval:** misses exact names/identifiers and cannot preserve full evidence.
- **Paid cloud OCR for every attachment:** increases recurring cost and unnecessary cloud interpretation.
- **Discard failed extraction input:** violates original attachment retention and prevents later repair.
- **Trust vector metadata for access:** leaves stale/deleted content vulnerable to disclosure.

## Consequences and verification

Some extraction waits for the phone, and OCR quality/language coverage has explicit limits. Every answer citation must resolve to permitted retained evidence or report unavailability. Test file-byte fidelity, malformed/decompression-heavy documents, page/size limits, unavailable device, re-extraction, revoked source visibility and stale semantic candidates. [TDD](../tdd.md) records the selected APIs/models and dated availability evidence.
