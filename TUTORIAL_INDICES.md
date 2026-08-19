# ClickHouse Indices & Other Features Worth Knowing About

`TUTORIAL.md` covers the fundamentals (column storage, `MergeTree` parts, `ORDER BY`, the two "update" patterns). This file goes one level deeper into indexing and a few other MergeTree features that don't show up in this project's schema yet, but that you'll run into as soon as you go past toy data volumes. Where relevant, each section says what it would look like applied to the `events` table (`SchemaInitializer`).

## 1. The primary index is sparse, not a B-tree

`ORDER BY (event_id)` (see `TUTORIAL.md` §2) doesn't build a per-row index the way a Postgres B-tree does. Instead, ClickHouse:

1. Splits each part into blocks of `index_granularity` rows (default **8192**).
2. Records only the *first* row's sort-key value of each block in an in-memory index (`primary.idx`).

So a lookup like `WHERE event_id = 'xyz'` binary-searches this sparse index to find which ~8192-row block could contain the value, then scans that whole block. That's why `ORDER BY` column choice matters so much: a `WHERE` clause that filters on (a prefix of) the `ORDER BY` columns lets ClickHouse skip almost all blocks; a filter on an unrelated column forces a full-part scan.

**Applied here:** `events` is ordered by `event_id` alone, so `EventRepository.findById` is cheap (one block scanned), but `EventRepository.find`, which also filters by `event_type`/`user_id`/`created_at`, has to scan every block in every part — there's no index help for those predicates today. A compound key like `ORDER BY (event_type, created_at, event_id)` would make event-type + time-range queries cheap instead, at the cost of making single-event_id lookups scan more (since event_id is no longer the leading column).

## 2. Data skipping indices (secondary indices)

Since you can only have one physical sort order, ClickHouse offers **skip indices** to speed up filters on *other* columns without a second full copy of the table (unlike a traditional secondary index). A skip index stores a small summary (min/max, a set of values, a bloom filter, ...) *per granule*, and a query can skip whole granules whose summary proves they can't match.

```sql
ALTER TABLE events ADD INDEX idx_event_type event_type TYPE set(100) GRANULARITY 4;
ALTER TABLE events ADD INDEX idx_user_id user_id TYPE bloom_filter GRANULARITY 4;
```

Common types:
- `minmax` — cheapest, best for numeric/date columns with local correlation (e.g. `created_at` is naturally increasing per part).
- `set(N)` — stores up to N distinct values per granule; good for low-cardinality columns like `event_type` (5 values in `EventLoadDataIT.EVENT_TYPES`).
- `bloom_filter` — probabilistic membership test; good for high-cardinality equality lookups like `user_id`.
- `ngrambf_v1` / `tokenbf_v1` — bloom filters over n-grams/tokens, for `LIKE '%text%'` or full-text-ish searches inside a `String` column.

Skip indices only help if the underlying data happens to cluster favorably within granules (e.g. `minmax` on `created_at` is nearly free because inserts are roughly chronological). They're a probabilistic optimization, not a guarantee — measure before adding one, since every index also costs write time and disk.

## 3. Partitioning (`PARTITION BY`)

Partitioning splits a table into separate physical part-groups by an expression, most commonly by time:

```sql
ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(created_at)
ORDER BY (event_id)
```

Partitions are not an index for point lookups — they're a coarse-grained unit for:
- **Partition pruning**: a query with `WHERE created_at >= ...` can skip entire partitions outright, before the primary index is even consulted.
- **Cheap bulk deletes/retention**: `ALTER TABLE events DROP PARTITION '202601'` drops a whole month instantly (metadata-only op), vastly cheaper than `DELETE ... WHERE created_at < ...` (which is a mutation that rewrites parts).
- Merges never combine parts across partitions, so picking a partition key that's too fine-grained (e.g. per-day on a low-volume table) hurts merge efficiency by capping how large parts can get.

**Applied here:** `events` currently has no `PARTITION BY`, so it's one big pool of parts. For an events/log table like this, `PARTITION BY toYYYYMM(created_at)` is the textbook choice — it enables both time-range pruning for `EventRepository.find`'s `from`/`to` filters and trivial retention via `DROP PARTITION`.

## 4. TTL (automatic expiration)

Log/event tables rarely need to keep data forever. A `TTL` clause lets ClickHouse delete (or move to cheaper storage) rows automatically in the background, driven by a date/datetime expression:

```sql
ALTER TABLE events MODIFY TTL created_at + INTERVAL 90 DAY;
```

TTL expiry happens as part of the normal background merge process — no cron job, no application code, no `DELETE` mutation. Combined with monthly partitioning, TTL commonly degenerates into "drop partitions once they age out," which is why the two features are usually adopted together.

## 5. Projections

A **projection** is a materialized alternate physical layout of the same table — effectively "pre-store this data sorted/aggregated a different way" — that ClickHouse's query planner picks automatically when it would answer the query more cheaply than the base table's layout.

```sql
ALTER TABLE events ADD PROJECTION events_by_type (
    SELECT event_type, count(), min(created_at), max(created_at)
    GROUP BY event_type
);
ALTER TABLE events MATERIALIZE PROJECTION events_by_type;
```

This is directly relevant to `EventRepository.countByEventType`: right now that query does a full `GROUP BY` scan every call. A projection keyed by `event_type` would let ClickHouse answer it from a much smaller pre-aggregated structure instead — at the cost of extra background merge work and disk to keep the projection in sync.

Projections vs. skip indices: a skip index helps the planner *avoid reading* granules from the existing layout; a projection gives the planner an *entirely different, additional* layout to read from instead. Projections cost more (they duplicate/re-aggregate data) but can help far more for aggregation-heavy queries.

## 6. Materialized views (as incremental aggregation)

Distinct from projections: a `MATERIALIZED VIEW` in ClickHouse is really an **insert trigger** — it runs its `SELECT` against each newly inserted block and writes the result into a separate *target table*, usually one using `AggregatingMergeTree` or `SummingMergeTree`. This is the standard way to maintain rollups (e.g. "events per hour per type") cheaply over a firehose of raw inserts, without re-scanning history on every read.

```sql
CREATE TABLE events_hourly_counts
(
    hour       DateTime,
    event_type String,
    cnt        AggregateFunction(count)
)
ENGINE = AggregatingMergeTree()
ORDER BY (hour, event_type);

CREATE MATERIALIZED VIEW events_hourly_counts_mv TO events_hourly_counts AS
SELECT toStartOfHour(created_at) AS hour, event_type, countState() AS cnt
FROM events
GROUP BY hour, event_type;
```

Note it only fires on rows inserted *after* it's created — backfilling history requires a separate one-off `INSERT INTO ... SELECT`. This project doesn't need one yet at its data volumes, but it's the idiomatic answer to "the stats endpoint is getting slow" before reaching for a projection.

## 7. Column codecs and compression

Every column is compressed (default `LZ4`) independent of the others — one of the payoffs of column-oriented storage: similar values sit next to each other on disk, which compresses far better than row-interleaved data. Codecs can be tuned per column:

```sql
created_at DateTime64(3) CODEC(Delta, ZSTD),
event_type String CODEC(ZSTD(3))
```

`Delta`/`DoubleDelta` suit monotonic-ish columns like timestamps or auto-incrementing IDs (store the diff, which compresses better than the raw value); `ZSTD` trades more CPU for a better ratio than the default `LZ4`, worthwhile for columns that are written once and read often — which describes most columns in an append-mostly events table.

## 8. Native `Map` and `JSON` columns (`device_metrics`)

`events.properties` is a plain `String` column holding JSON, (de)serialized entirely in application code (`EventRepository.writeJson`/`readJson`) — simple to reason about, but ClickHouse has no idea what's inside, so any server-side filter on a property needs `JSONExtract*(...)` functions that parse the whole blob per row.

`device_metrics` (`SchemaInitializer`, `MetricRepository`) is the same table shape but modeled with ClickHouse's *native* structured column types instead, to show the contrast directly:

```sql
CREATE TABLE device_metrics
(
    metric_id   UUID,
    device_id   String,
    metric_name String,
    tags        Map(String, String),
    attributes  JSON,
    recorded_at DateTime64(3)
)
ENGINE = MergeTree
ORDER BY (metric_name, recorded_at)
```

- **`Map(String, String)`** — `tags` round-trips as a real map; client-v2 decodes it straight into a Java `Map` (`MetricRepository.toMetric`), no Jackson involved. Filtering is a native subscript, pushed down server-side: `WHERE tags['env'] = 'prod'` (`MetricRepository.findByTag`). Compare that to the equivalent on `events` — there's no way to filter inside `properties` without a `JSONExtractString(properties, 'env') = 'prod'` scan.
- **`JSON`** — `attributes` is ClickHouse's native semi-structured JSON type. It stores fields as real subcolumns under the hood, so `attributes.status = 'ok'` (`MetricRepository.findByAttribute`) reads just that subcolumn off disk instead of parsing the whole document per row — a much closer analogue to querying a normal typed column than `JSONExtractString` is. The read path still lands back as JSON text (`CAST(attributes AS String)` in `MetricRepository.selectColumns`) and gets parsed with Jackson at the boundary, so the two repositories are directly comparable on the read side even though `device_metrics` is far cheaper to filter on the write/query side.

Note the JSON-path argument to `findByAttribute` is interpolated as a raw subcolumn path, not a quoted string literal, so it's restricted to an identifier-safe character set (`requireSafeIdentifierPath`) rather than escaped like a normal value — same string-building style as the rest of this project (see `TUTORIAL.md` §4), but worth noticing since it's the one place a path, not a value, gets interpolated into SQL.

**Sort key contrast:** `device_metrics` uses plain `MergeTree` (no versioned "update" concept — metrics are immutable readings) ordered by `(metric_name, recorded_at)` rather than `events`' `(event_id)`. That means `MetricRepository.countByTagValue`-style "all readings for a given metric over time" queries get the sparse-index benefit described in §1, at the cost of `findById` now having to scan across blocks instead of landing in one — the opposite tradeoff from `events`, and a good illustration of how `ORDER BY` choice should follow your actual query shape rather than defaulting to "the ID column."

## 9. Where this leaves `events` today

The current schema (`SchemaInitializer`) is intentionally minimal — no partitioning, no skip indices, no TTL, no projections — so that `TUTORIAL.md`'s core lessons (parts, merges, `ORDER BY`, `FINAL`, mutations) aren't buried under tuning concerns. The features above are the natural next step once you're past "does this work" and into "does this hold up at real data volume":

| Symptom | Feature to reach for |
|---|---|
| `find`/`countByEventType` scan everything even with a narrow `from`/`to` | `PARTITION BY toYYYYMM(created_at)` |
| Filtering by `event_type` or `user_id` alone (not via `event_id`) scans whole parts | skip index (`set`/`bloom_filter`) or reordering `ORDER BY` |
| Old events pile up forever | `TTL created_at + INTERVAL N DAY` |
| Stats/aggregation endpoint slows down as data grows | materialized view (rollup table) or a projection |
| Storage cost matters more than write CPU | per-column `CODEC(...)` tuning |
