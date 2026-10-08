-- ============================================================================
-- Xatu Observer — GPU metrics (Cassandra)
-- Migration V003 — usage of every NVIDIA GPU of a server, per minute, 7 days TTL.
--
-- Collected with `nvidia-smi` by the Xatu agent (java -jar xatu.jar agent) on
-- the miners, and by the normal Xatu on any server that has nvidia-smi.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) Samples of the GPUs of a server, one row per GPU per interval.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.gpu_metrics_by_server (
  server                   text,       -- partition: SERVER env var
  day                      date,       -- partition: YYYY-MM-DD (UTC)
  collected_at             timestamp,  -- clustering
  gpu_index                int,        -- clustering: index in nvidia-smi

  uuid                     text,       -- stable id of the board
  name                     text,       -- e.g. "Tesla K80"
  driver_version           text,

  utilization_gpu_percent  int,        -- 0-100
  utilization_mem_percent  int,        -- 0-100, memory bus activity
  mem_used_bytes           bigint,
  mem_total_bytes          bigint,
  temperature_c            int,
  power_draw_w             double,
  power_limit_w            double,
  fan_percent              int,        -- null on passive boards (K80)
  sm_clock_mhz             int,
  mem_clock_mhz            int,
  pstate                   text,       -- P0 (max performance) .. P8 (idle)
  throttle_reasons         list<text>, -- e.g. ["hw_thermal_slowdown"], empty when running free

  -- processes using the GPU, json array of {pid, name, mem_used_bytes}
  processes                text,

  PRIMARY KEY ((server, day), collected_at, gpu_index)
) WITH CLUSTERING ORDER BY (collected_at DESC, gpu_index ASC)
  AND default_time_to_live = 604800
  AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}
  AND comment = 'GPU metrics by server, 7 days TTL';

-- ---------------------------------------------------------------------------
-- 2) GPUs seen in the last 7 days, for the pickers.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ${keyspace}.gpu_inventory (
  server           text,
  gpu_index        int,
  uuid             text,
  name             text,
  driver_version   text,
  mem_total_bytes  bigint,
  last_seen        timestamp,
  PRIMARY KEY (server, gpu_index)
) WITH default_time_to_live = 604800
  AND comment = 'GPUs of each server';
