-- ============================================================================
-- interesting_queries.sql
--
-- A grab-bag of exploratory / analytical queries over the `events` table,
-- meant to be run by hand (clickhouse-client, DBeaver, etc.) against the
-- playground database once it has some data in it (see EventLoadDataIT /
-- TUTORIAL.md for how to generate volume).
--
-- Schema reminder (see SchemaInitializer):
--   events (
--     event_id   UUID,
--     event_type String,
--     user_id    String,
--     properties String,   -- JSON-encoded, e.g. {"path":"/home"}
--     created_at DateTime64(3),
--     updated_at DateTime64(3)
--   ) ENGINE = ReplacingMergeTree(updated_at) ORDER BY (event_id)
--
-- Because the table is a ReplacingMergeTree, every query below reads with
-- `FINAL` so duplicate/"updated" versions of the same event_id are
-- reconciled at query time (see TUTORIAL.md §2). Drop FINAL if you
-- specifically want to see raw, unmerged parts.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Basic counts and shape of the data
-- ----------------------------------------------------------------------------

-- Row count and distinct entity counts.
SELECT
    count()                    AS total_events,
    uniqExact(event_id)        AS distinct_events,
    uniqExact(user_id)         AS distinct_users,
    uniqExact(event_type)      AS distinct_event_types,
    min(created_at)            AS earliest_event,
    max(created_at)            AS latest_event
FROM events FINAL;

-- Table storage stats (parts, compressed/uncompressed size, row count) --
-- useful for seeing how ReplacingMergeTree parts merge over time.
SELECT
    table,
    count()                                    AS part_count,
    sum(rows)                                  AS total_rows,
    formatReadableSize(sum(data_compressed_bytes))   AS compressed_size,
    formatReadableSize(sum(data_uncompressed_bytes)) AS uncompressed_size
FROM system.parts
WHERE table = 'events' AND active
GROUP BY table;


-- ----------------------------------------------------------------------------
-- 2. Group-by / aggregation queries
-- ----------------------------------------------------------------------------

-- Event counts by type, most frequent first (same shape as
-- EventRepository.countByType, but runnable ad hoc).
SELECT
    event_type,
    count()                       AS event_count,
    uniqExact(user_id)            AS distinct_users
FROM events FINAL
GROUP BY event_type
ORDER BY event_count DESC;

-- Top users by event volume.
SELECT
    user_id,
    count()                       AS event_count,
    uniqExact(event_type)         AS distinct_event_types,
    min(created_at)               AS first_seen,
    max(created_at)               AS last_seen
FROM events FINAL
GROUP BY user_id
ORDER BY event_count DESC
LIMIT 20;

-- Event type breakdown per user (pivot-ish): which users perform which
-- event types how often.
SELECT
    user_id,
    event_type,
    count() AS event_count
FROM events FINAL
GROUP BY user_id, event_type
ORDER BY user_id, event_count DESC;

-- Daily active users + daily event volume.
SELECT
    toDate(created_at)            AS day,
    count()                       AS events,
    uniqExact(user_id)            AS active_users
FROM events FINAL
GROUP BY day
ORDER BY day;

-- Hourly event volume (good for spotting traffic patterns).
SELECT
    toStartOfHour(created_at)     AS hour,
    event_type,
    count()                       AS events
FROM events FINAL
GROUP BY hour, event_type
ORDER BY hour, events DESC;


-- ----------------------------------------------------------------------------
-- 3. Histograms / distributions
-- ----------------------------------------------------------------------------

-- Histogram of events-per-user, via ClickHouse's histogram() aggregate
-- (adaptive bucketing into ~10 buckets). Returns array of
-- (lower, upper, height) tuples.
SELECT histogram(10)(event_count) AS events_per_user_histogram
FROM (
    SELECT user_id, count() AS event_count
    FROM events FINAL
    GROUP BY user_id
);

-- Same idea but as a manual bucketed distribution (easier to read than
-- histogram()'s tuple array): how many users had 1 event, 2-5, 6-10, etc.
SELECT
    multiIf(
        event_count = 1,               '1',
        event_count BETWEEN 2 AND 5,   '2-5',
        event_count BETWEEN 6 AND 10,  '6-10',
        event_count BETWEEN 11 AND 50, '11-50',
        '50+'
    )                                   AS bucket,
    count()                             AS users_in_bucket
FROM (
    SELECT user_id, count() AS event_count
    FROM events FINAL
    GROUP BY user_id
)
GROUP BY bucket
ORDER BY min(event_count);

-- ASCII bar chart of events per event_type using bar().
SELECT
    event_type,
    count()                                     AS event_count,
    bar(count(), 0, (SELECT max(c) FROM (SELECT count() AS c FROM events FINAL GROUP BY event_type)), 40) AS chart
FROM events FINAL
GROUP BY event_type
ORDER BY event_count DESC;

-- Time-of-day histogram: which hour-of-day (0-23) sees the most events,
-- across all days.
SELECT
    toHour(created_at)  AS hour_of_day,
    count()             AS events,
    bar(count(), 0, (SELECT max(c) FROM (SELECT count() AS c FROM events FINAL GROUP BY toHour(created_at))), 40) AS chart
FROM events FINAL
GROUP BY hour_of_day
ORDER BY hour_of_day;


-- ----------------------------------------------------------------------------
-- 4. Window functions
-- ----------------------------------------------------------------------------

-- Running total of events per day (cumulative growth curve).
SELECT
    day,
    daily_events,
    sum(daily_events) OVER (ORDER BY day) AS cumulative_events
FROM (
    SELECT toDate(created_at) AS day, count() AS daily_events
    FROM events FINAL
    GROUP BY day
)
ORDER BY day;

-- Rank event types by volume within each day.
SELECT
    day,
    event_type,
    events,
    rank() OVER (PARTITION BY day ORDER BY events DESC) AS rank_in_day
FROM (
    SELECT toDate(created_at) AS day, event_type, count() AS events
    FROM events FINAL
    GROUP BY day, event_type
)
ORDER BY day, rank_in_day;

-- Per-user event sequencing: time since each user's previous event
-- (gap analysis / session-boundary detection).
-- Note: `minus()` doesn't accept two DateTime64 operands directly in
-- ClickHouse - use dateDiff() to get a plain numeric gap instead.
SELECT
    user_id,
    event_id,
    event_type,
    created_at,
    dateDiff(
        'second',
        lagInFrame(created_at) OVER (PARTITION BY user_id ORDER BY created_at),
        created_at
    ) AS seconds_since_prev_event
FROM events FINAL
ORDER BY user_id, created_at;


-- ----------------------------------------------------------------------------
-- 5. JSON `properties` column queries
-- ----------------------------------------------------------------------------

-- Extract a specific key from the JSON properties column (adjust the key
-- to match data you've inserted, e.g. "path", "sku", "plan").
SELECT
    event_id,
    event_type,
    JSONExtractString(properties, 'path') AS path
FROM events FINAL
WHERE JSONHas(properties, 'path')
ORDER BY created_at DESC
LIMIT 20;

-- Top values for a given property key across all events.
SELECT
    JSONExtractString(properties, 'path') AS path,
    count()                               AS hits
FROM events FINAL
WHERE JSONHas(properties, 'path')
GROUP BY path
ORDER BY hits DESC;

-- Distinct property keys seen in the data (useful when you don't know
-- the schema of properties ahead of time).
SELECT
    arrayJoin(JSONExtractKeys(properties)) AS property_key,
    count()                                AS occurrences
FROM events FINAL
GROUP BY property_key
ORDER BY occurrences DESC;


-- ----------------------------------------------------------------------------
-- 6. ReplacingMergeTree internals (ties back to TUTORIAL.md §2)
-- ----------------------------------------------------------------------------

-- Compare raw row count (all versions, all parts) vs FINAL row count
-- (deduplicated) to see how much "update churn" is sitting unmerged.
SELECT
    (SELECT count() FROM events)       AS raw_row_count,
    (SELECT count() FROM events FINAL) AS final_row_count,
    raw_row_count - final_row_count    AS unmerged_duplicate_versions;

-- Events with more than one stored version (i.e. events that were
-- "updated" via PUT /api/events/{id} and haven't been merged away yet).
-- Drop FINAL here deliberately - we want to see all raw versions.
SELECT
    event_id,
    count()            AS version_count,
    groupArray(updated_at) AS updated_at_versions
FROM events
GROUP BY event_id
HAVING version_count > 1
ORDER BY version_count DESC;


-- ============================================================================
-- device_metrics: native Map(String, String) and JSON column types
--
-- Companion table to `events`, used to contrast native ClickHouse types
-- against the JSON-string-column pattern above (see MetricRepository /
-- MetricLoadDataIT). Schema reminder (see SchemaInitializer):
--   device_metrics (
--     metric_id   UUID,
--     device_id   String,
--     metric_name String,
--     tags        Map(String, String),  -- e.g. {'region':'us-east','env':'prod'}
--     attributes  JSON,                 -- native JSON type, e.g. {"status":"ok","latency_ms":42}
--     recorded_at DateTime64(3)
--   ) ENGINE = MergeTree ORDER BY (metric_name, recorded_at)
--
-- No FINAL needed here - plain MergeTree, not ReplacingMergeTree, since
-- this table isn't demonstrating the versioned-update pattern.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 7. Native Map(String, String) access
-- ----------------------------------------------------------------------------

-- Direct map key access with `tags['key']` - evaluated against the binary
-- Map column, no text parsing involved (contrast with JSONExtractString
-- on events.properties in section 5).
SELECT
    metric_id,
    device_id,
    metric_name,
    tags['env']    AS env,
    tags['region'] AS region,
    recorded_at
FROM device_metrics
WHERE tags['env'] = 'prod'
ORDER BY recorded_at DESC
LIMIT 20;

-- Distribution of metrics per env value - pure Map access, no
-- arrayJoin/JSON functions needed.
SELECT
    tags['env'] AS env,
    count()     AS metric_count
FROM device_metrics
GROUP BY env
ORDER BY metric_count DESC;

-- All distinct tag keys seen in the data, via mapKeys() (the Map-type
-- equivalent of the JSONExtractKeys query in section 5).
SELECT
    arrayJoin(mapKeys(tags)) AS tag_key,
    count()                  AS occurrences
FROM device_metrics
GROUP BY tag_key
ORDER BY occurrences DESC;

-- Metrics per (region, env) combination - unpack the whole map per row
-- with arrayJoin(tags) rather than pulling out one key at a time.
SELECT
    tags.1 AS tag_key,
    tags.2 AS tag_value,
    count() AS metric_count
FROM device_metrics
ARRAY JOIN tags
GROUP BY tag_key, tag_value
ORDER BY tag_key, metric_count DESC;


-- ----------------------------------------------------------------------------
-- 8. Native JSON column access
-- ----------------------------------------------------------------------------

-- Native JSON subcolumn access with `attributes.status` - ClickHouse
-- reads only the inferred `status` subcolumn off disk, unlike
-- JSONExtractString(properties, 'status') against a String column, which
-- has to parse the whole blob per row.
SELECT
    metric_id,
    device_id,
    metric_name,
    attributes.status              AS status,
    attributes.latency_ms          AS latency_ms,
    recorded_at
FROM device_metrics
WHERE attributes.status = 'ok'
ORDER BY recorded_at DESC
LIMIT 20;

-- Status distribution and average/max latency per metric, straight off
-- the native JSON subcolumns - no JSON parsing functions at all.
-- Note: JSON subcolumns are typed `Dynamic` (their type wasn't pinned at
-- table-creation time), and aggregate functions like avg()/max() reject
-- Dynamic directly - the `.:Type` typed-path syntax casts to a concrete
-- type for them. Plain projection/equality (see section 8's first query)
-- doesn't need this.
SELECT
    metric_name,
    attributes.status.:String                      AS status,
    count()                                         AS occurrences,
    round(avg(attributes.latency_ms.:Int64), 1)     AS avg_latency_ms,
    max(attributes.latency_ms.:Int64)               AS max_latency_ms
FROM device_metrics
GROUP BY metric_name, status
ORDER BY metric_name, occurrences DESC;

-- Combine native Map and native JSON access in one query: worst-latency
-- metrics per region.
SELECT
    tags['region']                     AS region,
    metric_name,
    max(attributes.latency_ms.:Int64)  AS worst_latency_ms
FROM device_metrics
GROUP BY region, metric_name
ORDER BY worst_latency_ms DESC
LIMIT 20;
