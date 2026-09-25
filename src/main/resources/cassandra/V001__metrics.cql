-- ============================================================================
-- Xatu Observer — server metrics (Cassandra)
-- Migration V001 — keyspace, metrics tables and schema version control.
--
-- Applied by `java -jar xatu.jar cassandraMigrate`, which replaces ${keyspace}
-- and ${replication_factor} with the values from application.conf and records
-- each applied file in schema_version.
--
-- Every server running Xatu writes one sample per minute (METRICS_INTERVAL_SECONDS).
-- Data expires after 7 days (default_time_to_live), so the tables never grow
-- beyond one week of samples.
-- ============================================================================

CREATE KEYSPACE IF NOT EXISTS ${keyspace}
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': '${replication_factor}'};

-- ---------------------------------------------------------------------------
-- Migrations already applied to this keyspace.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.schema_version (
  version     int PRIMARY KEY,
  name        text,
  applied_at  timestamp
) WITH comment = 'Applied migrations (src/main/resources/cassandra)';

-- ---------------------------------------------------------------------------
-- 1) Samples of one server, one row per minute.
-- The partition is (server, day) so it holds at most one day of samples
-- (1440 rows with the default interval). Inside it, newest first.
--
-- TimeWindowCompactionStrategy groups each day in its own sstable: when the
-- TTL expires the whole sstable is dropped, without tombstone compactions.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.metrics_by_server (
  server                 text,       -- partition: SERVER env var (or hostname)
  day                    date,       -- partition: YYYY-MM-DD (UTC)
  collected_at           timestamp,  -- clustering: sample instant

  -- cpu
  cpu_percent            double,     -- total cpu usage in the interval (0-100)
  cpu_cores              int,
  load_1m                double,
  load_5m                double,
  load_15m               double,

  -- memory (bytes)
  mem_total_bytes        bigint,
  mem_used_bytes         bigint,     -- total - available
  mem_available_bytes    bigint,
  swap_total_bytes       bigint,
  swap_used_bytes        bigint,

  -- disk (bytes), filesystem of METRICS_DISK_PATH
  disk_total_bytes       bigint,
  disk_used_bytes        bigint,

  -- network, sum of the physical interfaces (or METRICS_NET_INTERFACES)
  net_rx_bytes_per_sec   double,
  net_tx_bytes_per_sec   double,

  PRIMARY KEY ((server, day), collected_at)
) WITH CLUSTERING ORDER BY (collected_at DESC)
  AND default_time_to_live = 604800
  AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}
  AND comment = 'Server metrics, one row per interval, 7 days TTL';

-- ---------------------------------------------------------------------------
-- 2) Servers with metrics, for the server picker. Rewritten on every sample,
-- so a server that stops sending disappears after 7 days.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.metrics_servers (
  server           text PRIMARY KEY,
  last_seen        timestamp,
  cpu_cores        int,
  mem_total_bytes  bigint
) WITH default_time_to_live = 604800
  AND comment = 'Servers sending metrics';
