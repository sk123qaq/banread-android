# ADR-0003: Split Sync Plane (Authenticated Desktop Gateway Pull + Per-Device State Shards)

- **Status**: accepted

## Context

Existing desktop services (`8771` and `8765`) enforce loopback-only `Host` checks with no authentication, and mobile devices frequently sleep, preventing desktop push. Users also expect reading progress and notes across 1,800+ books to sync even when the desktop is turned off, whereas free WebDAV providers (such as Jianguoyun) rate-limit large bundle transfers and suffer overwrite races when multiple devices write a single shared state file.

## Decision

Split the sync plane into two channels:
1. An explicit desktop `--mobile-bind` authenticated gateway (QR-paired `Bearer Token`) for `.bcbook` batch delivery (`Outbox + Pull` with HTTP `Range` resume), read-only cross-book search (proxying `8765` `POST /api/run` with `stateless=true`), and scanned-PDF OCR submission.
2. Lightweight per-device state shards (`progress/<uid>/<device_id>.json` and `notes/<uid>/<device_id>.jsonl`, a few KB per book) written exclusively by each device to WebDAV / S3-R2 (or desktop directly when on LAN) and merged conflict-free on read via `max(watermark)` and `noteId` + tombstone union.

Large `.bcbook` bundles, raw PDFs, and `companion_cache/` directories are strictly forbidden on the cloud shard sync channel.

## Consequences

Eliminates concurrent overwrite races and WebDAV 429 throttling while keeping reading progress fluid when the desktop is offline; requires adding a lightweight mobile gateway module and block-level progress store on the desktop.
