<template>
  <v-row justify="center">
    <v-col cols="12">
      <v-card elevation="2" id="docker-card">
        <!-- summary per server: click to filter -->
        <div class="row-label"><v-icon small>mdi-server</v-icon> Servers</div>
        <div class="docker-servers">
          <v-card
            v-for="s in serverSummary"
            :key="s.server"
            outlined
            :class="['server-card', { selected: filters.servers.includes(s.server) }]"
            :style="{ borderColor: filters.servers.includes(s.server) ? color(s.server) : undefined }"
            :title="'show only ' + s.server"
            @click="toggleServer(s.server)"
          >
            <div class="server-card-title" :style="{ color: color(s.server) }">
              <v-icon small :color="color(s.server)">mdi-server-network</v-icon> {{ s.server }}
            </div>
            <div class="server-card-values">
              <div><span>containers</span><strong>{{ s.count }}</strong></div>
              <div>
                <span>cpu</span><strong>{{ s.cores.toFixed(1) }}</strong><small>{{ s.hostCores ? 'of ' + s.hostCores + ' cores' : 'cores' }}</small>
              </div>
              <div>
                <span>memory</span><strong>{{ bytes(s.mem) }}</strong><small>{{ s.hostMem ? 'of ' + bytes(s.hostMem) : '' }}</small>
              </div>
              <div v-if="s.problems"><span>problems</span><strong class="bad">{{ s.problems }}</strong></div>
            </div>
          </v-card>
        </div>

        <!-- workers: machines with only the agent (gpu miners) -->
        <template v-if="workerCards.length">
          <div class="row-label"><v-icon small>mdi-expansion-card</v-icon> Workers</div>
          <div class="docker-workers">
            <v-card v-for="w in workerCards" :key="w.server" outlined class="worker-card">
              <div class="server-card-title" :style="{ color: color(w.server) }">
                <v-icon small :color="color(w.server)">mdi-expansion-card</v-icon> {{ w.server }}
                <span v-if="w.stale" class="worker-stale" :title="'last sample ' + ago(w.lastSeen) + ' ago'">no data for {{ ago(w.lastSeen) }}</span>
              </div>
              <div class="server-card-values">
                <div><span>cpu</span><strong>{{ percent(w.cpu) }}</strong><small>{{ w.cores }} cores</small></div>
                <div><span>memory</span><strong>{{ bytes(w.mem) }}</strong><small>of {{ bytes(w.memTotal) }}</small></div>
                <div><span>load</span><strong>{{ w.load }}</strong><small>1m</small></div>
              </div>
              <div v-for="g in w.gpus" :key="g.gpu_index" class="gpu-row">
                <div class="gpu-head">
                  <strong>GPU {{ g.gpu_index }}</strong>
                  <span class="gpu-name">{{ g.name.replace(/^NVIDIA /, '') }}</span>
                  <span :class="['gpu-pstate', g.pstate === 'P0' ? 'busy' : 'idle']" :title="g.pstate === 'P0' ? 'max performance state' : 'power saving state'">{{ g.pstate }}</span>
                  <v-icon v-if="throttling(g).length" x-small color="orange" :title="'throttling: ' + throttling(g).join(', ')">mdi-alert</v-icon>
                </div>
                <div class="gpu-meters">
                  <div class="gpu-meter" :title="'utilization ' + g.utilization_gpu_percent + '%'">
                    <span>util</span>
                    <div class="meter"><div :class="['meter-fill', level(g.utilization_gpu_percent / 100)]" :style="{ width: bar(g.utilization_gpu_percent / 100) }"></div></div>
                    <small>{{ g.utilization_gpu_percent }}%</small>
                  </div>
                  <div class="gpu-meter" :title="bytes(g.mem_used_bytes) + ' of ' + bytes(g.mem_total_bytes)">
                    <span>mem</span>
                    <div class="meter"><div :class="['meter-fill', level(g.mem_used_bytes / g.mem_total_bytes)]" :style="{ width: bar(g.mem_used_bytes / g.mem_total_bytes) }"></div></div>
                    <small>{{ bytes(g.mem_used_bytes) }} / {{ bytes(g.mem_total_bytes) }}</small>
                  </div>
                </div>
                <div class="gpu-facts">
                  <span :class="{ hot: g.temperature_c >= 80 }">{{ g.temperature_c }}°C</span>
                  <span>{{ Math.round(g.power_draw_w) }} / {{ Math.round(g.power_limit_w) }} W</span>
                  <span v-if="g.fan_percent !== undefined">fan {{ g.fan_percent }}%</span>
                  <span v-if="g.processes && g.processes.length" class="gpu-procs" :title="g.processes.map((p) => p.name + ' (pid ' + p.pid + ', ' + bytes(p.mem_used_bytes) + ')').join('\n')">
                    <v-icon x-small>mdi-application-cog</v-icon>{{ g.processes.map((p) => p.name.split('/').pop()).join(', ') }}
                  </span>
                  <span v-else class="gpu-free">no process</span>
                </div>
              </div>
              <div v-if="!w.gpus.length" class="gpu-free">no gpu samples</div>
            </v-card>
          </div>
        </template>

        <!-- search and filters -->
        <div class="docker-toolbar">
          <v-text-field
            ref="search"
            v-model="filters.q"
            prepend-inner-icon="mdi-magnify"
            placeholder="Search by name, image or id (press / to focus)"
            clearable
            hide-details
            dense
            outlined
            class="docker-search"
          ></v-text-field>
          <v-select
            v-model="filters.servers"
            :items="serverNames"
            label="servers"
            multiple
            chips
            small-chips
            deletable-chips
            clearable
            hide-details
            dense
            outlined
            class="docker-server-select"
          ></v-select>
          <div class="docker-refresh">
            <span class="updated">{{ updatedLabel }}</span>
            <v-btn icon small :loading="loading" title="reload" @click="load"><v-icon small>mdi-refresh</v-icon></v-btn>
          </div>
        </div>

        <div class="docker-problems">
          <span class="toolbar-label">show only</span>
          <v-chip
            v-for="p in problemFilters"
            :key="p.key"
            small
            :outlined="!filters.problems.includes(p.key)"
            :color="filters.problems.includes(p.key) ? p.color : undefined"
            :text-color="filters.problems.includes(p.key) ? 'white' : undefined"
            :disabled="!problemCounts[p.key] && !filters.problems.includes(p.key)"
            :title="p.help"
            @click="toggleProblem(p.key)"
          >
            <v-icon x-small left>{{ p.icon }}</v-icon>{{ p.label }}
            <span class="chip-count">{{ problemCounts[p.key] || 0 }}</span>
          </v-chip>
          <v-btn v-if="hasFilters" text x-small class="clear-filters" @click="clearFilters">
            <v-icon x-small left>mdi-filter-remove</v-icon>clear filters
          </v-btn>
        </div>

        <v-data-table
          :headers="headers"
          :items="filtered"
          item-key="key"
          :sort-by.sync="sortBy"
          :sort-desc.sync="sortDesc"
          :items-per-page="50"
          :footer-props="{ itemsPerPageOptions: [25, 50, 100, -1] }"
          :loading="loading && !rows.length"
          dense
          class="docker-table"
          @click:row="open"
        >
          <template v-slot:no-data>
            <div class="docker-empty">
              No container metrics yet. Each Xatu with <code>CASSANDRA_ACTIVE=true</code> and a tcp
              <code>DOCKER_HOST</code> sends them every minute.
            </div>
          </template>
          <template v-slot:no-results>
            <div class="docker-empty">No container matches the search/filters.</div>
          </template>

          <template v-slot:item.name="{ item }">
            <div class="cell-name">
              <span class="name">{{ item.name }}</span>
              <span class="image" :title="item.image">{{ item.image }}</span>
            </div>
          </template>

          <template v-slot:item.server="{ item }">
            <span class="server-tag" :style="serverStyle(item.server)">{{ item.server }}</span>
          </template>

          <template v-slot:item.cpu="{ item }">
            <div v-if="item.hasData" class="cell-meter" :title="cpuTitle(item)">
              <span>{{ percent(item.cpu) }}</span>
              <div class="meter"><div :class="['meter-fill', level(item.cpuRatio)]" :style="{ width: bar(item.cpuRatio) }"></div></div>
              <small>{{ item.cpuLimit ? 'limit ' + item.cpuLimit + ' cores' : 'no limit' }}</small>
            </div>
            <span v-else class="muted">-</span>
          </template>

          <template v-slot:item.mem="{ item }">
            <div v-if="item.hasData" class="cell-meter" :title="percent(item.memRatio * 100) + ' of the limit'">
              <span>{{ bytes(item.mem) }}</span>
              <div class="meter"><div :class="['meter-fill', level(item.memRatio)]" :style="{ width: bar(item.memRatio) }"></div></div>
              <small>of {{ bytes(item.memLimit) }}</small>
            </div>
            <span v-else class="muted">-</span>
          </template>

          <template v-slot:item.net="{ item }">
            <div v-if="item.hasData" class="cell-pair"><span>↓ {{ rate(item.rx) }}</span><span>↑ {{ rate(item.tx) }}</span></div>
            <span v-else class="muted">-</span>
          </template>

          <template v-slot:item.disk="{ item }">
            <div v-if="item.hasData" class="cell-pair"><span>R {{ rate(item.read) }}</span><span>W {{ rate(item.write) }}</span></div>
            <span v-else class="muted">-</span>
          </template>

          <template v-slot:item.pids="{ item }">
            <span v-if="item.hasData">{{ item.pids }}</span><span v-else class="muted">-</span>
          </template>

          <template v-slot:item.status="{ item }">
            <div class="cell-status">
              <v-icon v-if="item.stale" small color="grey" :title="'no data for ' + ago(item.lastSeen)">mdi-clock-alert-outline</v-icon>
              <v-icon v-if="item.health === 'healthy'" small color="green" title="healthy">mdi-heart-pulse</v-icon>
              <v-icon v-if="item.health === 'unhealthy'" small color="red" title="unhealthy">mdi-heart-broken</v-icon>
              <v-icon v-if="item.health === 'starting'" small color="orange" title="starting">mdi-heart-half-full</v-icon>
              <v-icon v-if="item.oom" small color="red" title="killed by out of memory">mdi-memory</v-icon>
              <span v-if="item.restarts" class="restarts" :title="item.restarts + ' restarts'"><v-icon x-small color="orange">mdi-restart</v-icon>{{ item.restarts }}</span>
            </div>
          </template>

          <template v-slot:item.uptime="{ item }">
            <span :title="'started ' + dateTime(item.startedAt)">{{ item.stale ? 'seen ' + ago(item.lastSeen) : duration(item.uptime) }}</span>
          </template>
        </v-data-table>
      </v-card>
    </v-col>

    <v-dialog v-model="detailOpen" max-width="1300" scrollable @click:outside="close" @keydown.esc="close">
      <ContainerDetail v-if="selected" :container="selected" :colors="color" @close="close" @error="showError" />
    </v-dialog>
  </v-row>
</template>

<script>
import client from '../commons/client'
import { paletteColor } from '../commons/serverColor'
import { bytes, dateTime, duration, percent, rate } from '../commons/format'

const STALE_MS = 3 * 60 * 1000

export default {
  name: 'DockerPage',
  data() {
    return {
      overview: [],
      hostServers: [],
      xatuServers: [],
      workers: {},
      loading: false,
      updatedAt: null,
      now: Date.now(),
      filters: { q: '', servers: [], problems: [] },
      sortBy: 'cpu',
      sortDesc: true,
      selected: null,
      detailOpen: false,
      headers: [
        { text: 'Container', value: 'name' },
        { text: 'Server', value: 'server' },
        { text: 'CPU', value: 'cpu', align: 'start' },
        { text: 'Memory', value: 'mem' },
        { text: 'Network', value: 'net', sort: (a, b) => a - b },
        { text: 'Disk I/O', value: 'disk', sort: (a, b) => a - b },
        { text: 'PIDs', value: 'pids' },
        { text: 'Status', value: 'status', sortable: true },
        { text: 'Up', value: 'uptime' },
      ],
      problemFilters: [
        { key: 'unhealthy', label: 'unhealthy', icon: 'mdi-heart-broken', color: 'red', help: 'healthcheck failing' },
        { key: 'oom', label: 'OOM killed', icon: 'mdi-memory', color: 'red', help: 'killed by out of memory' },
        { key: 'restarted', label: 'restarted', icon: 'mdi-restart', color: 'orange', help: 'restarted at least once' },
        { key: 'memory', label: 'memory ≥ 90%', icon: 'mdi-gauge-full', color: 'deep-orange', help: 'memory at 90% of its limit or more' },
        { key: 'cpu', label: 'cpu ≥ 80%', icon: 'mdi-chip', color: 'deep-orange', help: 'cpu at 80% of its limit (or of the server) or more' },
        { key: 'stale', label: 'no recent data', icon: 'mdi-clock-alert-outline', color: 'grey', help: 'not seen in the last 3 minutes (stopped or moved)' },
      ],
    }
  },
  computed: {
    // servers run the full Xatu (heartbeat) or have containers; the rest are workers (agents)
    serverNames() {
      return [...new Set([...this.xatuServers, ...this.overview.map((c) => c.server)])].sort()
    },
    workerNames() {
      return this.hostServers.map((s) => s.server).filter((s) => !this.serverNames.includes(s)).sort()
    },
    workerCards() {
      return this.workerNames.map((server) => {
        const host = this.hostServers.find((h) => h.server === server) || {}
        const w = this.workers[server] || {}
        const p = w.point
        return {
          server,
          lastSeen: host.last_seen,
          stale: !host.last_seen || this.now - host.last_seen > 3 * 60 * 1000,
          cores: host.cpu_cores,
          cpu: p ? p.cpu_percent : null,
          mem: p ? p.mem_used_bytes : null,
          memTotal: p ? p.mem_total_bytes : host.mem_total_bytes,
          load: p ? p.load_1m.toFixed(2) : '-',
          gpus: w.gpus || [],
        }
      })
    },
    rows() {
      const hosts = Object.fromEntries(this.hostServers.map((s) => [s.server, s]))
      return this.overview.map((c) => {
        const l = c.latest || null
        const host = hosts[c.server]
        const cpu = l ? l.cpu_percent : null
        const cpuLimit = l ? l.cpu_limit_cores : 0
        const cpuCapacity = cpuLimit || (host && host.cpu_cores) || 0
        const row = {
          key: `${c.container_name}::${c.server}`,
          name: c.container_name,
          server: c.server,
          image: c.image,
          id: c.container_id,
          startedAt: c.started_at,
          lastSeen: c.last_seen,
          latest: l,
          hasData: !!l,
          stale: !l || this.now - c.last_seen > STALE_MS,
          cpu,
          cpuLimit,
          cpuRatio: l && cpuCapacity ? cpu / 100 / cpuCapacity : 0,
          mem: l ? l.mem_usage_bytes : null,
          memLimit: l ? l.mem_limit_bytes : null,
          memRatio: l && l.mem_limit_bytes ? l.mem_usage_bytes / l.mem_limit_bytes : 0,
          rx: l ? l.net_rx_bytes_per_sec : 0,
          tx: l ? l.net_tx_bytes_per_sec : 0,
          read: l ? l.block_read_bytes_per_sec : 0,
          write: l ? l.block_write_bytes_per_sec : 0,
          pids: l ? l.pids : null,
          restarts: l ? l.restart_count : 0,
          health: l ? l.health : null,
          oom: l ? l.oom_killed : false,
          uptime: c.started_at ? this.now - c.started_at : null,
        }
        // numeric keys for sorting the composite columns
        row.net = row.rx + row.tx
        row.disk = row.read + row.write
        row.status = (row.health === 'unhealthy' ? 100 : 0) + (row.oom ? 50 : 0) + (row.stale ? 20 : 0) + row.restarts
        row.problems = this.problemFilters.filter((p) => this.hasProblem(row, p.key)).map((p) => p.key)
        return row
      })
    },
    searched() {
      const terms = (this.filters.q || '').toLowerCase().split(/\s+/).filter(Boolean)
      return this.rows.filter((r) => {
        if (this.filters.servers.length && !this.filters.servers.includes(r.server)) return false
        const text = `${r.name} ${r.image} ${r.id}`.toLowerCase()
        return terms.every((t) => text.includes(t))
      })
    },
    filtered() {
      if (!this.filters.problems.length) return this.searched
      return this.searched.filter((r) => this.filters.problems.some((p) => r.problems.includes(p)))
    },
    problemCounts() {
      const counts = {}
      for (const r of this.searched) for (const p of r.problems) counts[p] = (counts[p] || 0) + 1
      return counts
    },
    serverSummary() {
      const hosts = Object.fromEntries(this.hostServers.map((s) => [s.server, s]))
      return this.serverNames.map((server) => {
        const rows = this.rows.filter((r) => r.server === server && !r.stale)
        return {
          server,
          count: rows.length,
          cores: rows.reduce((a, r) => a + (r.cpu || 0), 0) / 100,
          mem: rows.reduce((a, r) => a + (r.mem || 0), 0),
          hostCores: hosts[server] && hosts[server].cpu_cores,
          hostMem: hosts[server] && hosts[server].mem_total_bytes,
          problems: this.rows.filter((r) => r.server === server && r.problems.some((p) => p !== 'stale')).length,
        }
      })
    },
    hasFilters() {
      return !!(this.filters.q || this.filters.servers.length || this.filters.problems.length)
    },
    updatedLabel() {
      if (!this.updatedAt) return ''
      const s = Math.round((this.now - this.updatedAt) / 1000)
      return `${this.filtered.length} of ${this.rows.length} containers · updated ${s < 5 ? 'now' : s + 's ago'}`
    },
  },
  watch: {
    filters: {
      deep: true,
      handler() {
        this.syncUrl()
      },
    },
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    const q = this.$route.query
    this.filters.q = q.q || ''
    this.filters.servers = q.servers ? String(q.servers).split(',') : []
    this.filters.problems = q.problems ? String(q.problems).split(',') : []
    this.load().then(() => {
      if (q.container) this.openByName(String(q.container))
    })
    this.refreshInterval = setInterval(() => this.load(), 60000)
    this.clockInterval = setInterval(() => (this.now = Date.now()), 5000)
    this.onKey = (e) => {
      if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(document.activeElement.tagName)) {
        e.preventDefault()
        this.$refs.search.focus()
      }
    }
    window.addEventListener('keydown', this.onKey)
  },
  beforeDestroy() {
    clearInterval(this.refreshInterval)
    clearInterval(this.clockInterval)
    window.removeEventListener('keydown', this.onKey)
  },
  methods: {
    bytes,
    rate,
    percent,
    duration,
    dateTime,
    color(server) {
      return paletteColor(Math.max(0, [...this.serverNames, ...this.workerNames].indexOf(server)))
    },
    serverStyle(server) {
      return { color: this.color(server), borderColor: this.color(server) }
    },
    hasProblem(r, key) {
      switch (key) {
        case 'unhealthy':
          return r.health === 'unhealthy'
        case 'oom':
          return r.oom
        case 'restarted':
          return r.restarts > 0
        case 'memory':
          return r.hasData && r.memRatio >= 0.9
        case 'cpu':
          return r.hasData && r.cpuRatio >= 0.8
        case 'stale':
          return r.stale
      }
      return false
    },
    // reasons that mean a real slowdown; idle and fixed application clocks are normal
    throttling(g) {
      return (g.throttle_reasons || []).filter((t) => !['gpu_idle', 'applications_clocks_setting'].includes(t))
    },
    level(ratio) {
      return ratio >= 0.9 ? 'high' : ratio >= 0.7 ? 'medium' : 'low'
    },
    bar(ratio) {
      return `${Math.max(2, Math.min(100, ratio * 100))}%`
    },
    cpuTitle(r) {
      const base = r.cpuLimit ? `of its limit of ${r.cpuLimit} cores` : 'of the server cores'
      return `${(r.cpu / 100).toFixed(2)} cores = ${percent(r.cpuRatio * 100)} ${base}`
    },
    ago(ms) {
      return duration(this.now - ms)
    },
    toggleServer(server) {
      const s = this.filters.servers
      this.filters.servers = s.includes(server) ? s.filter((x) => x !== server) : [...s, server]
    },
    toggleProblem(key) {
      const p = this.filters.problems
      this.filters.problems = p.includes(key) ? p.filter((x) => x !== key) : [...p, key]
    },
    clearFilters() {
      this.filters = { q: '', servers: [], problems: [] }
    },
    syncUrl(container) {
      const query = {}
      if (this.filters.q) query.q = this.filters.q
      if (this.filters.servers.length) query.servers = this.filters.servers.join(',')
      if (this.filters.problems.length) query.problems = this.filters.problems.join(',')
      const open = container === undefined ? (this.selected && this.detailOpen ? this.selected.container_name : null) : container
      if (open) query.container = open
      if (JSON.stringify(query) !== JSON.stringify(this.$route.query)) this.$router.replace({ query }).catch(() => {})
    },
    open(row) {
      this.selected = this.overview.find((c) => c.container_name === row.name && c.server === row.server)
      this.detailOpen = true
      this.syncUrl(row.name)
    },
    openByName(name) {
      const row = this.rows.filter((r) => r.name === name).sort((a, b) => b.lastSeen - a.lastSeen)[0]
      if (row) this.open(row)
    },
    close() {
      this.detailOpen = false
      this.syncUrl(null)
    },
    showError(e) {
      console.error(e)
      const message = e.response && e.response.data ? e.response.data.message : e.message
      if (this.logModal) this.logModal.open(e.name, message)
    },
    // last host sample and last sample of each gpu of every worker
    loadWorkers() {
      const to = Date.now()
      const from = to - 5 * 60 * 1000
      return Promise.all(
        this.workerNames.map((server) =>
          Promise.all([
            client.get('/metrics', { params: { server, from, to, step: 60 } }).catch(() => ({ data: { points: [] } })),
            // the gpu api may not exist in an older backend
            client.get('/metrics/gpus/samples', { params: { server, from, to, step: 60 } }).catch(() => ({ data: { hits: [] } })),
          ]).then(([metrics, gpus]) => {
            const points = metrics.data.points
            const samples = gpus.data.hits
            const lastAt = Math.max(0, ...samples.map((g) => g.collected_at))
            return [server, { point: points[points.length - 1], gpus: samples.filter((g) => g.collected_at === lastAt).sort((a, b) => a.gpu_index - b.gpu_index) }]
          })
        )
      ).then((entries) => (this.workers = Object.fromEntries(entries)))
    },
    load() {
      this.loading = true
      return Promise.all([client.get('/metrics/containers/overview'), client.get('/metrics/servers'), client.get('/status').catch(() => ({ data: {} }))])
        .then(([overview, servers, status]) => {
          this.overview = overview.data.hits
          this.hostServers = servers.data.hits
          this.xatuServers = (status.data.servers || []).map((s) => s.server)
          this.updatedAt = Date.now()
          this.now = Date.now()
          return this.loadWorkers()
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
#docker-card {
  background-color: $primary-color;
  padding: 20px 24px;
}

.docker-servers {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(260px, 100%), 1fr));
  gap: 10px;
  margin-bottom: 16px;

  .server-card {
    padding: 8px 12px;
    cursor: pointer;
    border-width: 2px !important;
    transition: box-shadow 0.15s;

    &:hover {
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
    }
  }

  .server-card-title {
    font-weight: 600;
    margin-bottom: 4px;
  }

  .server-card-values {
    display: flex;
    flex-wrap: wrap;
    gap: 14px;

    div {
      display: flex;
      flex-direction: column;
    }

    span {
      font-size: 10px;
      text-transform: uppercase;
      color: #888;
    }

    strong {
      font-size: 15px;
    }

    small {
      font-size: 10px;
      color: #777;
    }

    .bad {
      color: #d32f2f;
    }
  }
}

.row-label {
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

.docker-workers {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(380px, 100%), 1fr));
  gap: 10px;
  margin-bottom: 16px;

  .worker-card {
    padding: 8px 12px 10px;
  }

  .worker-stale {
    font-size: 11px;
    font-weight: normal;
    color: #b26a00;
    margin-left: 6px;
  }

  .server-card-title {
    font-weight: 600;
    margin-bottom: 4px;
  }

  .server-card-values {
    display: flex;
    gap: 16px;
    margin-bottom: 6px;

    div {
      display: flex;
      flex-direction: column;
    }

    span {
      font-size: 10px;
      text-transform: uppercase;
      color: #888;
    }

    strong {
      font-size: 15px;
    }

    small {
      font-size: 10px;
      color: #777;
    }
  }

  .gpu-row {
    border-top: 1px solid #edf1ee;
    padding-top: 6px;
    margin-top: 6px;
  }

  .gpu-head {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;

    .gpu-name {
      color: #555;
    }

    .gpu-pstate {
      font-size: 10px;
      padding: 0 5px;
      border-radius: 6px;

      &.busy {
        background: #e8f5e9;
        color: #2e7d32;
      }

      &.idle {
        background: #f1f1f1;
        color: #888;
      }
    }
  }

  .gpu-meters {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 10px;
    margin: 4px 0 2px;
  }

  .gpu-meter {
    display: grid;
    grid-template-columns: 30px 1fr;
    align-items: center;
    column-gap: 6px;

    span {
      font-size: 10px;
      text-transform: uppercase;
      color: #888;
    }

    small {
      grid-column: 2;
      font-size: 11px;
      color: #555;
    }
  }

  .meter {
    height: 6px;
    background: #e8ece9;
    border-radius: 3px;
    overflow: hidden;
  }

  .meter-fill {
    height: 100%;
    border-radius: 3px;

    &.low {
      background: #43a047;
    }

    &.medium {
      background: #fb8c00;
    }

    &.high {
      background: #e53935;
    }
  }

  .gpu-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 4px 12px;
    font-size: 11.5px;
    color: #555;

    .hot {
      color: #d32f2f;
      font-weight: 600;
    }

    .gpu-procs {
      color: #1f3d2a;
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .gpu-free {
    font-size: 11.5px;
    color: #999;
  }
}

.docker-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;

  .docker-search {
    flex: 1 1 360px;
    background: white;
  }

  .docker-server-select {
    flex: 0 1 380px;
    background: white;
  }

  .docker-refresh {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-left: auto;

    .updated {
      font-size: 12px;
      color: #33563f;
    }
  }
}

.docker-problems {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;

  .toolbar-label {
    font-size: 12px;
    color: #33563f;
    margin-right: 2px;
  }

  .chip-count {
    margin-left: 6px;
    font-weight: 600;
    opacity: 0.8;
  }

  .clear-filters {
    color: #33563f !important;
  }
}

.docker-table {
  th {
    vertical-align: middle;
    white-space: nowrap;
  }

  tbody tr {
    cursor: pointer;
  }

  td {
    padding-top: 4px !important;
    padding-bottom: 4px !important;
    vertical-align: middle;
  }

  .cell-name {
    display: flex;
    flex-direction: column;
    min-width: 220px;

    .name {
      font-family: 'JetBrains Mono', Consolas, monospace;
      font-size: 12.5px;
      font-weight: 600;
      word-break: break-all;
    }

    .image {
      font-size: 11px;
      color: #888;
      max-width: 420px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .server-tag {
    display: inline-block;
    padding: 0 8px;
    border: 1px solid;
    border-radius: 10px;
    font-size: 12px;
    white-space: nowrap;
  }

  .cell-meter {
    display: flex;
    flex-direction: column;
    min-width: 110px;

    span {
      font-size: 13px;
      font-weight: 600;
    }

    small {
      font-size: 10px;
      color: #888;
    }
  }

  .meter {
    height: 5px;
    background: #e8ece9;
    border-radius: 3px;
    overflow: hidden;
    margin: 2px 0;
  }

  .meter-fill {
    height: 100%;
    border-radius: 3px;

    &.low {
      background: #43a047;
    }

    &.medium {
      background: #fb8c00;
    }

    &.high {
      background: #e53935;
    }
  }

  .cell-pair {
    display: flex;
    flex-direction: column;
    font-size: 12px;
    white-space: nowrap;
  }

  .cell-status {
    display: flex;
    align-items: center;
    gap: 4px;

    .restarts {
      font-size: 12px;
      color: #e65100;
    }
  }

  .muted {
    color: #aaa;
  }
}

.docker-empty {
  padding: 24px;
  color: #666;
}
</style>
