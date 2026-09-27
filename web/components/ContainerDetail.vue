<template>
  <v-card class="container-detail">
    <v-card-title class="detail-title">
      <v-icon left>mdi-docker</v-icon>
      <span class="detail-name">{{ container.container_name }}</span>
      <v-spacer></v-spacer>
      <v-btn-toggle v-model="hours" mandatory dense @change="load">
        <v-btn v-for="r in ranges" :key="r.hours" :value="r.hours" small>{{ r.label }}</v-btn>
      </v-btn-toggle>
      <v-btn icon small class="ml-2" :loading="loading" title="reload" @click="load"><v-icon small>mdi-refresh</v-icon></v-btn>
      <v-btn icon small title="close (Esc)" @click="$emit('close')"><v-icon>mdi-close</v-icon></v-btn>
    </v-card-title>

    <v-card-text>
      <div class="detail-facts">
        <div><span>server</span><strong :style="{ color: color(container.server) }">{{ container.server }}</strong></div>
        <div><span>image</span><strong>{{ container.image }}</strong></div>
        <div>
          <span>id</span><strong :title="container.container_id">{{ (container.container_id || '').slice(0, 12) }}</strong>
          <v-btn icon x-small title="copy full id" @click="copy(container.container_id)"><v-icon x-small>mdi-content-copy</v-icon></v-btn>
        </div>
        <div><span>up</span><strong>{{ uptime }}</strong><small>since {{ dateTime(container.started_at) }}</small></div>
        <div v-if="latest"><span>cpu now</span><strong>{{ percent(latest.cpu_percent) }}</strong><small>{{ (latest.cpu_percent / 100).toFixed(2) }} cores</small></div>
        <div v-if="latest"><span>memory now</span><strong>{{ bytes(latest.mem_usage_bytes) }}</strong><small>{{ percent((latest.mem_usage_bytes * 100) / latest.mem_limit_bytes) }} of the limit</small></div>
        <div v-if="latest"><span>restarts</span><strong :class="{ bad: latest.restart_count > 0 }">{{ latest.restart_count }}</strong></div>
        <div v-if="latest"><span>health</span><strong :class="healthClass">{{ latest.health || 'no healthcheck' }}</strong></div>
        <div v-if="latest"><span>cpu limit</span><strong>{{ latest.cpu_limit_cores ? latest.cpu_limit_cores + ' cores' : 'none' }}</strong></div>
        <div v-if="latest"><span>memory limit</span><strong>{{ bytes(latest.mem_limit_bytes) }}</strong></div>
        <div v-if="latest && latest.oom_killed"><span>OOM</span><strong class="bad">killed by out of memory</strong></div>
      </div>

      <div v-if="moved" class="detail-moved">
        <v-icon small>mdi-swap-horizontal</v-icon> ran on {{ servers.length }} servers in this period: {{ servers.join(', ') }}
      </div>

      <div v-if="!points.length && !loading" class="detail-empty">No samples in this period.</div>

      <div v-else class="detail-grid">
        <MetricsChart title="CPU (100% = one core)" :datasets="series((p) => p.cpu_percent)" :format-y="percent" :from="from" :to="to" />
        <MetricsChart title="Memory (dashed: limit)" :datasets="memDatasets" :format-y="bytes" :from="from" :to="to" />
        <MetricsChart
          title="Network (solid: received, dashed: sent)"
          :datasets="[...series((p) => p.net_rx_bytes_per_sec, ' ↓'), ...series((p) => p.net_tx_bytes_per_sec, ' ↑', true)]"
          :format-y="rate"
          :from="from"
          :to="to"
        />
        <MetricsChart
          title="Disk I/O (solid: read, dashed: write)"
          :datasets="[...series((p) => p.block_read_bytes_per_sec, ' read'), ...series((p) => p.block_write_bytes_per_sec, ' write', true)]"
          :format-y="rate"
          :from="from"
          :to="to"
        />
        <MetricsChart title="Processes (pids)" :datasets="series((p) => p.pids)" :format-y="(v) => Math.round(v)" :from="from" :to="to" />
      </div>
      <div class="detail-footer">one point every {{ stepLabel }} · data kept for 7 days</div>
    </v-card-text>
  </v-card>
</template>

<script>
import client from '../commons/client'
import { bytes, dateTime, duration, percent, rate } from '../commons/format'

const STEPS = { 1: 60, 6: 120, 24: 300, 168: 900 }

export default {
  name: 'ContainerDetail',
  props: {
    container: { type: Object, required: true },
    // server name -> color, the same used in the table
    colors: { type: Function, required: true },
  },
  data() {
    return {
      ranges: [
        { label: '1h', hours: 1 },
        { label: '6h', hours: 6 },
        { label: '24h', hours: 24 },
        { label: '7d', hours: 168 },
      ],
      hours: 1,
      points: [],
      from: Date.now() - 3600 * 1000,
      to: Date.now(),
      loading: false,
    }
  },
  computed: {
    latest() {
      return this.container.latest
    },
    step() {
      return STEPS[this.hours]
    },
    stepLabel() {
      return this.step >= 60 ? `${this.step / 60} min` : `${this.step}s`
    },
    servers() {
      return [...new Set(this.points.map((p) => p.server))].sort()
    },
    moved() {
      return this.servers.length > 1
    },
    uptime() {
      return this.container.started_at ? duration(Date.now() - this.container.started_at) : '-'
    },
    healthClass() {
      const h = this.latest && this.latest.health
      return { bad: h === 'unhealthy', good: h === 'healthy' }
    },
    memDatasets() {
      return [...this.series((p) => p.mem_usage_bytes), ...this.series((p) => p.mem_limit_bytes, ' limit', true, 'rgba(120, 120, 120, 0.8)')]
    },
  },
  watch: {
    container() {
      this.load()
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    bytes,
    rate,
    percent,
    dateTime,
    color(server) {
      return this.colors(server)
    },
    copy(text) {
      if (navigator.clipboard) navigator.clipboard.writeText(text)
    },
    // one line per server (a container that moved has more than one); gaps break the line
    series(value, suffix = '', dashed = false, fixedColor = null) {
      return this.servers.map((server) => {
        const data = []
        let last = null
        for (const p of this.points.filter((x) => x.server === server)) {
          if (last !== null && p.collected_at - last > 2 * this.step * 1000) data.push({ x: last + this.step * 1000, y: null })
          data.push({ x: p.collected_at, y: value(p) })
          last = p.collected_at
        }
        const label = this.moved ? server + suffix : (suffix.trim() || this.container.container_name)
        return { label, color: fixedColor || this.colors(server), dashed, data }
      })
    },
    load() {
      const to = Date.now()
      const from = to - this.hours * 3600 * 1000
      this.loading = true
      client
        .get('/metrics/containers/samples', { params: { name: this.container.container_name, from, to, step: this.step } })
        .then((response) => {
          this.points = response.data.hits
          this.from = from
          this.to = to
        })
        .catch((e) => this.$emit('error', e))
        .finally(() => {
          this.loading = false
        })
    },
  },
}
</script>

<style lang="scss">
.container-detail {
  .detail-title {
    gap: 6px;
  }

  .detail-name {
    font-family: 'JetBrains Mono', Consolas, monospace;
    font-size: 18px;
    word-break: break-all;
  }

  .detail-facts {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 24px;
    margin-bottom: 12px;

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
      font-size: 14px;
      color: #222;
    }

    small {
      font-size: 11px;
      color: #777;
    }

    .bad {
      color: #d32f2f;
    }

    .good {
      color: #2e7d32;
    }
  }

  .detail-moved {
    margin-bottom: 10px;
    color: #8a5a00;
  }

  .detail-empty {
    padding: 32px;
    text-align: center;
    color: #777;
  }

  .detail-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(min(460px, 100%), 1fr));
    gap: 12px;
  }

  .detail-footer {
    margin-top: 8px;
    font-size: 12px;
    color: #777;
    text-align: right;
  }
}
</style>
