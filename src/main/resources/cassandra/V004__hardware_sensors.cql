-- ============================================================================
-- Xatu Observer — hardware sensors (Cassandra)
-- Migration V004 — temperature of every cpu core (coretemp) and, on servers with
-- ipmi, fans (RPM) and ambient temperature, per minute, 7 days TTL.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ${keyspace}.sensors_by_server (
  server        text,       -- partition: SERVER env var
  day           date,       -- partition: YYYY-MM-DD (UTC)
  collected_at  timestamp,  -- clustering
  sensor        text,       -- clustering: "cpu0 core 3", "cpu1 package", "FAN MOD 1A", "Ambient Temp"

  kind          text,       -- cpu_temp | fan | ambient_temp | board_temp
  value         double,     -- °C or RPM
  high          double,     -- limit reported by the hardware (coretemp max), null when unknown
  crit          double,     -- critical limit (coretemp crit), null when unknown

  PRIMARY KEY ((server, day), collected_at, sensor)
) WITH CLUSTERING ORDER BY (collected_at DESC, sensor ASC)
  AND default_time_to_live = 604800
  AND compaction = {'class': 'TimeWindowCompactionStrategy', 'compaction_window_unit': 'DAYS', 'compaction_window_size': 1}
  AND comment = 'Hardware sensors by server, 7 days TTL';
