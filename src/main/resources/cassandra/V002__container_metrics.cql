-- ============================================================================
-- Xatu Observer — docker container metrics (Cassandra)
-- Migration V002 — usage of every running container, per minute, 7 days TTL.
--
-- The identity of a container is its NAME: in the yeshua cluster a container
-- keeps its name when it moves to another server, while the docker id changes
-- (it is a new container). The id and the server are saved in every sample.
--
-- Same data in two tables, one per query:
--   1. container_metrics_by_name   -> history of one container, wherever it ran
--   2. container_metrics_by_server -> every container of one server
-- plus container_inventory, where each container is running now (for crossing
-- with the owners database).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) History of a container. `server` is a clustering column so two containers
-- with the same name on two servers (while one is moved) do not overwrite.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.container_metrics_by_name (
  container_name           text,       -- partition: stable identity
  day                      date,       -- partition: YYYY-MM-DD (UTC)
  collected_at             timestamp,  -- clustering
  server                   text,       -- clustering: where it was running

  container_id             text,       -- docker id, changes when the container is recreated
  image                    text,
  started_at               timestamp,
  restart_count            int,
  oom_killed               boolean,
  health                   text,       -- healthy / unhealthy / starting, null without healthcheck

  -- cpu: 100 = one full core, like `docker stats`
  cpu_percent              double,
  cpu_limit_cores          double,     -- --cpus of the container, 0 = no limit

  -- memory (bytes): usage without the page cache that can be reclaimed, like `docker stats`
  mem_usage_bytes          bigint,
  mem_limit_bytes          bigint,     -- limit of the container, or the host memory without limit

  -- rates in bytes per second
  net_rx_bytes_per_sec     double,
  net_tx_bytes_per_sec     double,
  block_read_bytes_per_sec double,
  block_write_bytes_per_sec double,

  pids                     int,

  PRIMARY KEY ((container_name, day), collected_at, server)
) WITH CLUSTERING ORDER BY (collected_at DESC, server ASC)
  AND default_time_to_live = 604800
  AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}
  AND comment = 'Docker container metrics by container name, 7 days TTL';

-- ---------------------------------------------------------------------------
-- 2) Every container of a server, per minute.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.container_metrics_by_server (
  server                   text,
  day                      date,
  collected_at             timestamp,
  container_name           text,

  container_id             text,
  image                    text,
  started_at               timestamp,
  restart_count            int,
  oom_killed               boolean,
  health                   text,
  cpu_percent              double,
  cpu_limit_cores          double,
  mem_usage_bytes          bigint,
  mem_limit_bytes          bigint,
  net_rx_bytes_per_sec     double,
  net_tx_bytes_per_sec     double,
  block_read_bytes_per_sec double,
  block_write_bytes_per_sec double,
  pids                     int,

  PRIMARY KEY ((server, day), collected_at, container_name)
) WITH CLUSTERING ORDER BY (collected_at DESC, container_name ASC)
  AND default_time_to_live = 604800
  AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}
  AND comment = 'Docker container metrics by server, 7 days TTL';

-- ---------------------------------------------------------------------------
-- 3) Where each container is running now. Rewritten on every sample; a container
-- that stopped everywhere disappears after 7 days.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.container_inventory (
  container_name   text PRIMARY KEY,
  server           text,
  container_id     text,
  image            text,
  started_at       timestamp,
  last_seen        timestamp
) WITH default_time_to_live = 604800
  AND comment = 'Last server, id and image of each container';
