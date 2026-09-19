# Metadata wire format

The metadata layer is the part of LumoVault that makes a second device able to
rebuild the same catalog. It is a JSON format stored in the user's own Telegram
channel, so **it is a compatibility contract**: a reader that cannot parse these
documents cannot restore a backup. This file documents it, because the Flutter
project never did (its PRD described a key:value caption format that predates
the JSON actually shipped).

Sources of truth: `features/metadata/domain/model/Manifest.kt` and
`MetadataPartition.kt`. Serialization is hand-built with `buildJsonObject`, not
reflection, so the field names below are literal.

## Manifest

One document. Uploaded as the pinned message and, for redundancy, as a document
named `metadata/manifest.json`.

```json
{
  "app": "lumovault",
  "schema_version": 2,
  "created": "2026-09-19T10:00:00.000Z",
  "device_hash": "<stable per-install id>",
  "total_media": 12345,
  "total_size_bytes": 98765432100,
  "last_sync": "2026-09-19T10:00:00.000Z",
  "chunks": [{ "id": "2026/07", "count": 412, "hash": "<sha256 hex>" }]
}
```

- `app` must equal `lumovault`; any other value is rejected, so an unrelated
  JSON document in the channel is not mistaken for a manifest.
- `schema_version` is `2` today. A **missing** version means v1 (the field was
  not always written). Parsing accepts older versions because the format is
  additive; a *newer* version is not rejected by the model itself — callers
  must gate on it (see "Gaps" below).
- `chunks` is always serialized sorted by `id`, so two manifests describing the
  same library serialize identically — that is what makes document comparison
  meaningful.
- `chunks[].hash` is the partition's item digest, so a reader can pull only the
  months that differ from its own copy.

## Partition documents

One document per `YYYY/MM`, named `metadata/<partition-id>.json` (the `id`
contains a slash). Only the changed month is re-uploaded.

```json
{
  "id": "2026/07",
  "period_start": "2026-07-01T00:00:00.000Z",
  "period_end": "2026-07-31T23:59:59.999Z",
  "last_modified": "2026-09-19T10:00:00.000Z",
  "items": [ /* PartitionItem */ ]
}
```

## Partition keys

- Format `%04d/%02d` in **UTC**, e.g. `2026/07`.
- `partitionKeyFromDate` and `dateFromPartitionKey` are exact inverses by
  design: an item's month is derived solely from the key, so the two must never
  drift apart.
- `nextPartitionKey` handles the December → January rollover.
- An unparseable key falls back to a default instead of throwing; a corrupt key
  must degrade one partition, not abort a sync pass.

## Error policy

`Manifest.fromJsonString` and `MetadataPartition.fromJsonString` return `null`
on any failure. The reasoning is deliberate: one unreadable document is
recoverable from the channel, whereas an exception would take down the sync
pass that is supposed to repair it.

## Channel layout

- A private channel named `LumoVault Backup` is created in the user's account.
- The manifest is pinned, and also written as a document so a reader that does
  not see the pinned message can still find it.
- Metadata documents use the `lumovault_metadata_` file-name prefix so a
  restore scan can skip them when collecting media.

## Gaps (known, not yet closed)

1. **No `schema_version` gate before field parsing.** A manifest written by a
   newer version parses to `null` on an older install, which is
   indistinguishable from "no backup exists". Restore should report an explicit
   "created by a newer version" error instead.
2. **Partition documents carry no version field.** Every item field has a
   default, so a future layout change would be silently mis-parsed rather than
   rejected. Adding `schema_version` to the partition document (not just the
   manifest) is the fix.
3. **The per-media caption format is still undocumented in this repo.** The
   media files themselves carry a JSON caption (version-tagged with `v`), which
   is the other half of the restore contract. It lives in the Flutter app's
   `caption_metadata.dart` and must be ported and documented before the restore
   engine is written.