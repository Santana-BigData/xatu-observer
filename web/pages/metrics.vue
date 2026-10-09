<template>
  <v-row justify="center">
    <v-col cols="12">
      <v-card elevation="2" id="metrics-container-card">
        <div class="metrics-toolbar">
          <div class="metrics-groups">
            <div v-for="g in groups" :key="g.key" class="metrics-servers">
              <button class="toolbar-label group-toggle" :title="'show only ' + g.label.toLowerCase()" @click="selectOnly(g.names)">
                <v-icon x-small>{{ g.icon }}</v-icon> {{ g.label }}
              </button>
              <v-chip
                v-for="name in g.names"
                :key="name"
                small
                :outlined="!selected.includes(name)"
                :style="chipStyle(name)"
                @click="toggleServer(name)"
              >
                <v-icon x-small left>{{ g.icon }}</v-icon>{{ name }}
              </v-chip>
            </div>
          </div>
          <div class="metrics-range">
            <v-btn-toggle v-model="range" mandatory dense @change="loadMetrics">
              <v-btn v-for="r in ranges" :key="r.label" :value="r.hours" small>{{ r.label }}</v-btn>
            </v-btn-toggle>
            <v-btn icon small :loading="loading" @click="reload" title="reload">
              <v-icon small>mdi-refresh</v-icon>
            </v-btn>
          </div>
        </div>

        <div v-if="!servers.length && !loading" class="metrics-empty">
          No server metrics yet. They are saved every minute by each Xatu with
          <code>CASSANDRA_ACTIVE=true</code> (see README).
        </div>

        <template v-else>
          <template v-for="g in groups">
          <div v-if="summary.some((row) => g.names.includes(row.server))" :key="g.key + '-label'" class="summary-label">
            <v-icon small>{{ g.icon }}</v-icon> {{ g.label }}
          </div>
          <div v-if="summary.some((row) => g.names.includes(row.server))" :key="g.key + '-cards'" class="metrics-summary">
            <v-card v-for="row in summary.filter((x) => g.names.includes(x.server))" :key="row.server" class="summary-card" outlined>
              <div class="summary-title" :style="{ color: color(row.server) }">
                <v-icon small :color="color(row.server)">{{ g.icon }}</v-icon>
                {{ row.server }}
                <span class="summary-age">{{ row.age }}</span>
              </div>
              <div class="summary-values">
                <div><span>cpu</span><strong>{{ row.cpu }}</strong><small>{{ row.cores }} cores</small></div>
                <div><span>memory</span><strong>{{ row.mem }}</strong><small>{{ row.memBytes }}</small></div>
                <div><span>disk</span><strong>{{ row.disk }}</strong><small>{{ row.diskBytes }}</small></div>
                <div><span>network</span><strong>↓ {{ row.rx }}</strong><small>↑ {{ row.tx }}</small></div>
                <div><span>load</span><strong>{{ row.load }}</strong><small>1m / 5m / 15m</small></div>
                <div v-if="row.cpuTemp" :class="{ hot: row.cpuHot }"><span>cpu temp</span><strong>{{ row.cpuTemp }}</strong><small>{{ row.cpuTempDetail }}</small></div>
                <div v-if="row.inlet" :class="{ hot: row.inletHot }"><span>inlet air</span><strong>{{ row.inlet }}</strong><small>{{ row.inletDetail }}</small></div>
                <div v-if="row.fan"><span>fans</span><strong>{{ row.fan }}</strong><small>{{ row.fanDetail }}</small></div>
                <div v-if="row.gpus"><span>gpu</span><strong>{{ row.gpuUtil }}</strong><small>{{ row.gpus }}× {{ row.gpuName }} · {{ row.gpuTemp }}</small></div>
              </div>
            </v-card>
          </div>
          </template>

          <div class="metrics-grid">
            <MetricsChart title="CPU usage" :datasets="cpuDatasets" :format-y="percent" :y-max="100" :from="from" :to="to" />
            <MetricsChart title="Memory usage" :datasets="memDatasets" :format-y="percent" :y-max="100" :from="from" :to="to" />
            <MetricsChart
              title="Network (solid: received, dashed: sent)"
              :datasets="netDatasets"
              :format-y="bytesPerSec"
              :from="from"
              :to="to"
            />
            <MetricsChart title="Load average (1m)" :datasets="loadDatasets" :format-y="decimal" :from="from" :to="to" />
            <MetricsChart title="Disk usage" :datasets="diskDatasets" :format-y="percent" :y-max="100" :from="from" :to="to" />
            <MetricsChart
              v-if="hasSwap"
              title="Swap usage"
              :datasets="swapDatasets"
              :format-y="bytes"
              :from="from"
              :to="to"
            />
          </div>
          <template v-if="gpuSeriesKeys.length">
            <h3 class="metrics-section">GPUs <small>one line per GPU · hover a point for the processes</small></h3>
            <div class="metrics-grid">
              <MetricsChart title="GPU utilization" :datasets="gpuSeries((p) => p.utilization_gpu_percent)" :format-y="percent" :y-max="100" :from="from" :to="to" />
              <MetricsChart title="GPU memory used" :datasets="gpuSeries((p) => p.mem_used_bytes)" :format-y="bytes" :from="from" :to="to" />
              <MetricsChart title="GPU temperature" :datasets="gpuSeries((p) => p.temperature_c)" :format-y="celsius" :from="from" :to="to" />
              <MetricsChart title="GPU power (dashed: limit)" :datasets="[...gpuSeries((p) => p.power_draw_w), ...gpuSeries((p) => p.power_limit_w, ' limit', true)]" :format-y="watts" :from="from" :to="to" />
              <MetricsChart title="GPU clock (SM)" :datasets="gpuSeries((p) => p.sm_clock_mhz)" :format-y="mhz" :from="from" :to="to" />
            </div>
            <div v-if="throttled.length" class="metrics-throttle">
              <v-icon small color="orange">mdi-alert</v-icon>
              throttling in this period: {{ throttled.join(' · ') }}
            </div>
          </template>
          <template v-if="sensorServers.length">
            <h3 class="metrics-section">
              Temperatures &amp; fans
              <small v-if="selected.length > 1">select only one server to see each core and fan</small>
            </h3>
            <div class="metrics-grid">
              <MetricsChart
                title="CPU temperature (solid: hottest core, dashed: coolest core)"
                :datasets="[...sensorSeries('cpu_temp', (ps) => Math.max(...ps.map((p) => p.max))), ...sensorSeries('cpu_temp', (ps) => Math.min(...ps.map((p) => p.min)), ' coolest', true)]"
                :format-y="celsius"
                :from="from"
                :to="to"
              />
              <MetricsChart
                v-if="hasSensor('fan')"
                title="Fans (solid: slowest fan, dashed: fastest)"
                :datasets="[...sensorSeries('fan', (ps) => Math.min(...ps.map((p) => p.min))), ...sensorSeries('fan', (ps) => Math.max(...ps.map((p) => p.max)), ' fastest', true)]"
                :format-y="rpm"
                :from="from"
                :to="to"
              />
              <MetricsChart
                v-if="hasSensor('ambient_temp')"
                title="Inlet air temperature (ipmi)"
                :datasets="sensorSeries('ambient_temp', (ps) => Math.max(...ps.map((p) => p.max)))"
                :format-y="celsius"
                :from="from"
                :to="to"
              />
              <template v-if="selected.length === 1">
                <MetricsChart
                  :title="'Each core of ' + selected[0] + (coreLimit ? ' (dashed: limit ' + coreLimit + '°C)' : '')"
                  :datasets="perSensor('cpu_temp', coreLimit)"
                  :format-y="celsius"
                  :from="from"
                  :to="to"
                />
                <MetricsChart
                  v-if="hasSensor('fan')"
                  :title="'Each fan of ' + selected[0]"
                  :datasets="perSensor('fan')"
                  :format-y="rpm"
                  :from="from"
                  :to="to"
                />
              </template>
            </div>
          </template>
          <div class="metrics-footer">
            one point every {{ stepLabel }} · data kept for 7 days · refreshes every minute
          </div>
        </template>
      </v-card>
    </v-col>
  </v-row>
</template>

<script>
import client from '../commons/client'
import { paletteColor } from '../commons/serverColor'

export default {
  name: 'MetricsPage',
  data() {
    return {
      servers: [],
      selected: [],
      ranges: [
        { label: '1h', hours: 1 },
        { label: '6h', hours: 6 },
        { label: '24h', hours: 24 },
        { label: '7d', hours: 168 },
      ],
      range: 1,
      from: Date.now() - 3600 * 1000,
      to: Date.now(),
      step: 60,
      results: {},
      gpus: [],
      xatuServers: [],
      gpuResults: {},
      sensorResults: {},
      loading: false,
    }
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    this.reload()
    this.refreshInterval = setInterval(() => this.reload(), 60000)
  },
  beforeDestroy() {
    clearInterval(this.refreshInterval)
  },
  computed: {
    // servers run the full Xatu (heartbeat in /status); the others are workers (agents, gpu miners).
    // Without /status (older backend) a machine with gpus counts as a worker.
    groups() {
      const names = this.servers.map((s) => s.server)
      const isServer = (n) => (this.xatuServers.length ? this.xatuServers.includes(n) : !this.gpus.some((g) => g.server === n))
      return [
        { key: 'servers', label: 'Servers', icon: 'mdi-server-network', names: names.filter(isServer) },
        { key: 'workers', label: 'Workers', icon: 'mdi-expansion-card', names: names.filter((n) => !isServer(n)) },
      ].filter((g) => g.names.length)
    },
    selectedResults() {
      return this.selected.filter((s) => this.results[s]).map((s) => this.results[s])
    },
    cpuDatasets() {
      return this.series((p) => p.cpu_percent)
    },
    memDatasets() {
      return this.series((p) => (p.mem_total_bytes ? (p.mem_used_bytes * 100) / p.mem_total_bytes : null))
    },
    diskDatasets() {
      return this.series((p) => (p.disk_total_bytes ? (p.disk_used_bytes * 100) / p.disk_total_bytes : null))
    },
    swapDatasets() {
      return this.series((p) => p.swap_used_bytes)
    },
    hasSwap() {
      return this.selectedResults.some((r) => r.points.some((p) => p.swap_total_bytes > 0))
    },
    loadDatasets() {
      return this.series((p) => p.load_1m)
    },
    netDatasets() {
      return [
        ...this.series((p) => p.net_rx_bytes_per_sec, ' ↓'),
        ...this.series((p) => p.net_tx_bytes_per_sec, ' ↑', true),
      ]
    },
    // (server, gpu_index) of the selected servers that have samples
    gpuSeriesKeys() {
      return this.selected.flatMap((server) => {
        const points = this.gpuResults[server] || []
        return [...new Set(points.map((p) => p.gpu_index))].sort().map((gpu) => ({ server, gpu }))
      })
    },
    throttled() {
      return this.gpuSeriesKeys.flatMap(({ server, gpu }) => {
        const reasons = new Set((this.gpuResults[server] || []).filter((p) => p.gpu_index === gpu).flatMap((p) => p.throttle_reasons || []))
        // idle and fixed application clocks are normal, not a slowdown
        reasons.delete('gpu_idle')
        reasons.delete('applications_clocks_setting')
        return reasons.size ? [`${this.gpuLabel(server, gpu)}: ${[...reasons].join(', ')}`] : []
      })
    },
    // selected servers with sensor readings
    sensorServers() {
      return this.selected.filter((s) => (this.sensorResults[s] || []).length)
    },
    // limit reported by coretemp ("max") of the single selected server
    coreLimit() {
      const points = (this.sensorResults[this.selected[0]] || []).filter((p) => p.kind === 'cpu_temp' && p.high)
      return points.length ? Math.round(points[points.length - 1].high) : null
    },
    stepLabel() {
      return this.step >= 3600 ? `${this.step / 3600}h` : this.step >= 60 ? `${this.step / 60} min` : `${this.step}s`
    },
    summary() {
      return this.selectedResults
        .filter((r) => r.points.length)
        .map((r) => {
          const p = r.points[r.points.length - 1]
          const info = this.servers.find((s) => s.server === r.server)
          const gpuPoints = this.gpuResults[r.server] || []
          const sensors = this.sensorSummary(r.server)
          const lastAt = Math.max(0, ...gpuPoints.map((g) => g.collected_at))
          const lastGpus = gpuPoints.filter((g) => g.collected_at === lastAt)
          return {
            gpus: lastGpus.length,
            gpuName: lastGpus.length ? lastGpus[0].name.replace(/^NVIDIA /, '') : '',
            gpuUtil: lastGpus.length ? this.percent(Math.max(...lastGpus.map((g) => g.utilization_gpu_percent))) : '',
            gpuTemp: lastGpus.length ? `${Math.max(...lastGpus.map((g) => g.temperature_c))}°C` : '',
            ...sensors,
            server: r.server,
            age: `updated ${this.timeSince(info ? info.last_seen : p.collected_at)}`,
            cpu: this.percent(p.cpu_percent),
            cores: p.cpu_cores,
            mem: this.percent((p.mem_used_bytes * 100) / p.mem_total_bytes),
            memBytes: `${this.bytes(p.mem_used_bytes)} / ${this.bytes(p.mem_total_bytes)}`,
            disk: this.percent((p.disk_used_bytes * 100) / p.disk_total_bytes),
            diskBytes: `${this.bytes(p.disk_used_bytes)} / ${this.bytes(p.disk_total_bytes)}`,
            rx: this.bytesPerSec(p.net_rx_bytes_per_sec),
            tx: this.bytesPerSec(p.net_tx_bytes_per_sec),
            load: `${this.decimal(p.load_1m)} / ${this.decimal(p.load_5m)} / ${this.decimal(p.load_15m)}`,
          }
        })
    },
  },
  methods: {
    color(server, alpha = 1) {
      return paletteColor(Math.max(0, this.servers.findIndex((s) => s.server === server)), alpha)
    },
    chipStyle(server) {
      return this.selected.includes(server)
        ? { backgroundColor: this.color(server, 0.12), color: this.color(server), borderColor: this.color(server) }
        : { color: '#777' }
    },
    selectOnly(names) {
      this.selected = [...names]
      this.loadMetrics()
    },
    toggleServer(server) {
      this.selected = this.selected.includes(server) ? this.selected.filter((s) => s !== server) : [...this.selected, server]
      this.loadMetrics()
    },
    // One dataset per selected server. A gap larger than two steps (xatu stopped) breaks the line.
    series(value, suffix = '', dashed = false) {
      return this.selectedResults.map((r) => {
        const data = []
        let last = null
        for (const p of r.points) {
          if (last !== null && p.collected_at - last > 2 * r.step * 1000) data.push({ x: last + r.step * 1000, y: null })
          data.push({ x: p.collected_at, y: value(p) })
          last = p.collected_at
        }
        return { label: r.server + suffix, color: this.color(r.server), dashed, data }
      })
    },
    percent(v) {
      return v === null || v === undefined || isNaN(v) ? '-' : `${Number(v).toFixed(v < 10 ? 1 : 0)}%`
    },
    rpm(v) {
      return v === null || v === undefined ? '-' : `${Math.round(v)} RPM`
    },
    hasSensor(kind) {
      return this.sensorServers.some((s) => this.sensorResults[s].some((p) => p.kind === kind))
    },
    // sensor points of a kind of a server, grouped by time: [[at, points]]
    sensorBuckets(server, kind) {
      const byTime = new Map()
      for (const p of this.sensorResults[server] || []) {
        if (p.kind !== kind) continue
        if (!byTime.has(p.collected_at)) byTime.set(p.collected_at, [])
        byTime.get(p.collected_at).push(p)
      }
      return [...byTime.entries()].sort((a, b) => a[0] - b[0])
    },
    withGaps(points) {
      const data = []
      let last = null
      for (const [x, y] of points) {
        if (last !== null && x - last > 2 * this.step * 1000) data.push({ x: last + this.step * 1000, y: null })
        data.push({ x, y })
        last = x
      }
      return data
    },
    // one line per server, `pick` makes one value of the sensors of the bucket (e.g. the hottest core)
    sensorSeries(kind, pick, suffix = '', dashed = false) {
      return this.sensorServers
        .map((server) => ({ server, buckets: this.sensorBuckets(server, kind) }))
        .filter((x) => x.buckets.length)
        .map(({ server, buckets }) => ({
          label: server + suffix,
          color: this.color(server, dashed ? 0.6 : 1),
          dashed,
          data: this.withGaps(buckets.map(([at, ps]) => [at, pick(ps)])),
        }))
    },
    // one line per sensor of the single selected server, plus the limit
    perSensor(kind, limit = null) {
      const server = this.selected[0]
      const points = (this.sensorResults[server] || []).filter((p) => p.kind === kind)
      const names = [...new Set(points.map((p) => p.sensor))]
      const lines = names.map((name, i) => ({
        label: name,
        color: paletteColor(i),
        data: this.withGaps(points.filter((p) => p.sensor === name).map((p) => [p.collected_at, p.avg])),
      }))
      if (limit && points.length) {
        lines.push({ label: 'limit', color: '#d32f2f', dashed: true, data: [{ x: this.from, y: limit }, { x: this.to, y: limit }] })
      }
      return lines
    },
    // values for the summary card: now (last bucket) and the extremes of the period
    sensorSummary(server) {
      const result = {}
      const temps = this.sensorBuckets(server, 'cpu_temp')
      if (temps.length) {
        const now = temps[temps.length - 1][1]
        const hottest = Math.max(...now.map((p) => p.last))
        const coolest = Math.min(...now.map((p) => p.last))
        const peak = Math.max(...temps.flatMap(([, ps]) => ps.map((p) => p.max)))
        const low = Math.min(...temps.flatMap(([, ps]) => ps.map((p) => p.min)))
        const limit = Math.min(...now.map((p) => p.high || Infinity))
        result.cpuTemp = this.celsius(hottest)
        result.cpuTempDetail = `cores ${Math.round(coolest)}–${Math.round(hottest)}°C · period ${Math.round(low)}–${Math.round(peak)}°C`
        result.cpuHot = isFinite(limit) && hottest >= limit - 5
      }
      const fans = this.sensorBuckets(server, 'fan')
      if (fans.length) {
        const now = fans[fans.length - 1][1]
        result.fan = this.rpm(Math.min(...now.map((p) => p.last)))
        result.fanDetail = `slowest of ${now.length} · fastest ${this.rpm(Math.max(...now.map((p) => p.last)))}`
      }
      const inlet = this.sensorBuckets(server, 'ambient_temp')
      if (inlet.length) {
        const now = Math.max(...inlet[inlet.length - 1][1].map((p) => p.last))
        const low = Math.min(...inlet.flatMap(([, ps]) => ps.map((p) => p.min)))
        const peak = Math.max(...inlet.flatMap(([, ps]) => ps.map((p) => p.max)))
        result.inlet = this.celsius(now)
        result.inletDetail = `period ${Math.round(low)}–${Math.round(peak)}°C`
        // dell servers want the air inlet below 35°C
        result.inletHot = now >= 35
      }
      return result
    },
    celsius(v) {
      return v === null || v === undefined ? '-' : `${Math.round(v)}°C`
    },
    watts(v) {
      return v === null || v === undefined ? '-' : `${Math.round(v)} W`
    },
    mhz(v) {
      return v === null || v === undefined ? '-' : `${Math.round(v)} MHz`
    },
    gpuLabel(server, gpu) {
      const perServer = this.gpuSeriesKeys.filter((k) => k.server === server).length
      return perServer > 1 ? `${server} gpu${gpu}` : server
    },
    // One dataset per (server, gpu); gpus of the same server share its color in different dashes.
    gpuSeries(value, suffix = '', dashed = false) {
      return this.gpuSeriesKeys.map(({ server, gpu }) => {
        const points = (this.gpuResults[server] || []).filter((p) => p.gpu_index === gpu)
        const data = []
        let last = null
        for (const p of points) {
          if (last !== null && p.collected_at - last > 2 * this.step * 1000) data.push({ x: last + this.step * 1000, y: null })
          data.push({ x: p.collected_at, y: value(p), processes: (p.processes || []).map((x) => x.name.split('/').pop()).join(', ') })
          last = p.collected_at
        }
        return { label: this.gpuLabel(server, gpu) + suffix, color: this.color(server, gpu === 0 ? 1 : 0.55), dashed, data }
      })
    },
    decimal(v) {
      return v === null || v === undefined ? '-' : Number(v).toFixed(2)
    },
    bytes(v) {
      if (v === null || v === undefined) return '-'
      const units = ['B', 'KB', 'MB', 'GB', 'TB']
      let i = 0
      let n = Number(v)
      while (n >= 1024 && i < units.length - 1) {
        n /= 1024
        i++
      }
      return `${n.toFixed(n < 10 && i > 0 ? 1 : 0)} ${units[i]}`
    },
    bytesPerSec(v) {
      return v === null || v === undefined ? '-' : `${this.bytes(v)}/s`
    },
    timeSince(ms) {
      const seconds = Math.floor((Date.now() - ms) / 1000)
      if (seconds < 120) return `${seconds}s ago`
      if (seconds < 7200) return `${Math.floor(seconds / 60)} min ago`
      return `${Math.floor(seconds / 3600)}h ago`
    },
    showError(e) {
      console.error(e)
      const message = e.response && e.response.data ? e.response.data.message : e.message
      if (this.logModal) this.logModal.open(e.name, message)
    },
    reload() {
      this.loading = true
      Promise.all([
        client.get('/metrics/servers'),
        client.get('/metrics/gpus').catch(() => ({ data: { hits: [] } })),
        client.get('/status').catch(() => ({ data: {} })),
      ])
        .then(([servers, gpus, status]) => {
          const first = !this.servers.length
          this.servers = servers.data.hits
          this.gpus = gpus.data.hits
          this.xatuServers = (status.data.servers || []).map((x) => x.server)
          const names = this.servers.map((s) => s.server)
          this.selected = first ? names : this.selected.filter((s) => names.includes(s))
          return this.loadMetrics()
        })
        .catch((e) => this.showError(e))
        .finally(() => {
          this.loading = false
        })
    },
    loadMetrics() {
      const to = Date.now()
      const from = to - this.range * 3600 * 1000
      this.loading = true
      const withGpu = this.selected.filter((server) => this.gpus.some((g) => g.server === server))
      return Promise.all([
        Promise.all(this.selected.map((server) => client.get('/metrics', { params: { server, from, to } }).then((response) => response.data))),
        Promise.all(withGpu.map((server) => client.get('/metrics/gpus/samples', { params: { server, from, to } }).then((response) => [server, response.data.hits]))),
        // older backends have no sensors: no temperatures, not an error
        Promise.all(
          this.selected.map((server) =>
            client
              .get('/metrics/sensors', { params: { server, from, to } })
              .then((response) => [server, response.data.hits])
              .catch(() => [server, []])
          )
        ),
      ])
        .then(([results, gpuResults, sensorResults]) => {
          const byServer = {}
          results.forEach((r) => (byServer[r.server] = r))
          this.results = byServer
          this.gpuResults = Object.fromEntries(gpuResults)
          this.sensorResults = Object.fromEntries(sensorResults)
          this.from = from
          this.to = to
          if (results.length) this.step = results[0].step
        })
        .catch((e) => this.showError(e))
        .finally(() => {
          this.loading = false
        })
    },
  },
}
</script>

<style lang="scss">
#metrics-container-card {
  background-color: $primary-color;
  padding: 24px;
}

.metrics-toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.metrics-groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.group-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 78px;
  font-weight: 600;
  text-transform: uppercase;
  font-size: 11px !important;
  letter-spacing: 0.4px;
  cursor: pointer;

  &:hover {
    text-decoration: underline;
  }
}

.summary-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: #33563f;
  margin: 2px 0 6px;
}

.metrics-servers {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.toolbar-label {
  font-size: 13px;
  color: #33563f;
  margin-right: 4px;
}

.metrics-range {
  display: flex;
  align-items: center;
  gap: 8px;
}

.metrics-empty {
  padding: 32px;
  text-align: center;
  color: #33563f;
}

.metrics-summary {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(420px, 100%), 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.summary-card {
  padding: 10px 14px;

  .summary-title {
    font-weight: 600;
    margin-bottom: 6px;
  }

  .summary-age {
    font-weight: normal;
    font-size: 12px;
    color: #888;
    margin-left: 6px;
  }

  .summary-values {
    display: flex;
    flex-wrap: wrap;
    gap: 18px;

    div {
      display: flex;
      flex-direction: column;
    }

    span {
      font-size: 11px;
      text-transform: uppercase;
      color: #888;
    }

    strong {
      font-size: 16px;
    }

    .hot strong {
      color: #d32f2f;
    }

    small {
      font-size: 11px;
      color: #666;
    }
  }
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(520px, 100%), 1fr));
  gap: 12px;
}

.metrics-section {
  margin: 18px 0 10px;
  color: #33563f;
  font-size: 16px;

  small {
    font-weight: normal;
    font-size: 12px;
    color: #5a7563;
    margin-left: 8px;
  }
}

.metrics-throttle {
  margin-top: 8px;
  font-size: 13px;
  color: #8a5a00;
}

.metrics-footer {
  margin-top: 10px;
  font-size: 12px;
  color: #33563f;
  text-align: right;
}
</style>
