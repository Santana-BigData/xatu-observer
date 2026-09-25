<template>
  <v-row justify="center">
    <v-col cols="12">
      <v-card elevation="2" id="metrics-container-card">
        <div class="metrics-toolbar">
          <div class="metrics-servers">
            <span class="toolbar-label">servers</span>
            <v-chip
              v-for="s in servers"
              :key="s.server"
              small
              :outlined="!selected.includes(s.server)"
              :style="chipStyle(s.server)"
              @click="toggleServer(s.server)"
            >
              <v-icon x-small left>mdi-server-network</v-icon>{{ s.server }}
            </v-chip>
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
          <div class="metrics-summary">
            <v-card v-for="row in summary" :key="row.server" class="summary-card" outlined>
              <div class="summary-title" :style="{ color: color(row.server) }">
                <v-icon small :color="color(row.server)">mdi-server-network</v-icon>
                {{ row.server }}
                <span class="summary-age">{{ row.age }}</span>
              </div>
              <div class="summary-values">
                <div><span>cpu</span><strong>{{ row.cpu }}</strong><small>{{ row.cores }} cores</small></div>
                <div><span>memory</span><strong>{{ row.mem }}</strong><small>{{ row.memBytes }}</small></div>
                <div><span>disk</span><strong>{{ row.disk }}</strong><small>{{ row.diskBytes }}</small></div>
                <div><span>network</span><strong>↓ {{ row.rx }}</strong><small>↑ {{ row.tx }}</small></div>
                <div><span>load</span><strong>{{ row.load }}</strong><small>1m / 5m / 15m</small></div>
              </div>
            </v-card>
          </div>

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
    stepLabel() {
      return this.step >= 3600 ? `${this.step / 3600}h` : this.step >= 60 ? `${this.step / 60} min` : `${this.step}s`
    },
    summary() {
      return this.selectedResults
        .filter((r) => r.points.length)
        .map((r) => {
          const p = r.points[r.points.length - 1]
          const info = this.servers.find((s) => s.server === r.server)
          return {
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
      client
        .get('/metrics/servers')
        .then((response) => {
          const first = !this.servers.length
          this.servers = response.data.hits
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
      return Promise.all(
        this.selected.map((server) =>
          client.get('/metrics', { params: { server, from, to } }).then((response) => response.data)
        )
      )
        .then((results) => {
          const byServer = {}
          results.forEach((r) => (byServer[r.server] = r))
          this.results = byServer
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

.metrics-footer {
  margin-top: 10px;
  font-size: 12px;
  color: #33563f;
  text-align: right;
}
</style>
