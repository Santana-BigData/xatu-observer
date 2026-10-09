<template>
  <v-row justify="center">
    <v-col cols="12" lg="10">
      <v-card class="alerts-help" outlined tile>
        A rule alerts on telegram when a metric of a server stays <strong>above</strong> (or <strong>below</strong>) a value for more
        than some minutes, e.g. <em>CPU temperature above 80°C for more than 5 minutes</em>. A short spike does not alert, and
        missing data (a server that stopped sending) restarts the count. While it lasts the alert repeats every 30 minutes, and a
        "Resolved" message is sent when it ends. Rules on all servers include every server and miner that sends metrics.
      </v-card>

      <v-card v-if="!rules.length && loaded" class="alerts-help" outlined tile>No rules yet. Add one with the + button.</v-card>

      <v-card v-for="rule in rules" :key="rule.id" class="rule-card" elevation="2" outlined tile>
        <v-card-title class="rule-card-title">
          <v-icon :color="rule.firing ? 'red darken-2' : rule.enabled ? 'green darken-2' : 'grey'" class="mr-2">
            {{ rule.firing ? 'mdi-bell-ring' : rule.enabled ? 'mdi-bell-check-outline' : 'mdi-bell-off-outline' }}
          </v-icon>
          <v-btn @click="editRule(rule)">{{ rule.name }}</v-btn>
          <span v-if="rule.firing" class="rule-firing">FIRING</span>
          <v-spacer></v-spacer>
          <v-switch
            :input-value="rule.enabled"
            dense
            hide-details
            class="mt-0 mr-2"
            :label="rule.enabled ? 'on' : 'off'"
            @change="(v) => setEnabled(rule, v)"
          />
          <v-btn icon @click="removeRule(rule)">
            <v-icon>mdi-close</v-icon>
          </v-btn>
        </v-card-title>
        <v-card-text>
          <div class="rule-sentence">{{ sentence(rule) }}</div>
          <div class="rule-servers">
            <span v-for="s in rule.servers" :key="s.server" :class="['rule-server', 'state-' + stateOf(rule, s)]" :title="tooltip(rule, s)">
              <span class="state-dot"></span>{{ s.server }}
              <strong>{{ value(rule, s.current) }}</strong>
              <small v-if="stateOf(rule, s) === 'pending'">{{ minutesSince(s.in_condition_since) }}/{{ rule.duration_minutes }} min</small>
              <small v-if="stateOf(rule, s) === 'firing'">for {{ minutesSince(s.in_condition_since) }} min</small>
            </span>
            <span v-if="!rule.servers.length" class="rule-none">no server sends this metric</span>
          </div>
        </v-card-text>
      </v-card>

      <button @click="addRule" id="plus-button">
        <v-icon>mdi-plus</v-icon>
      </button>
    </v-col>

    <v-dialog v-model="dialog.active" max-width="860">
      <v-card>
        <v-card-title class="text-h5">{{ dialog.mode === 'add' ? 'New alert rule' : 'Edit alert rule' }}</v-card-title>
        <v-card-text>
          <v-text-field v-model="form.name" label="name" placeholder="e.g. cpu hot" />
          <v-row dense>
            <v-col cols="12" sm="6">
              <v-select v-model="form.metric" :items="metrics" item-text="label" item-value="key" label="metric" @change="metricChanged" />
            </v-col>
            <v-col cols="12" sm="6">
              <v-select v-model="form.server" :items="serverItems" label="server" />
            </v-col>
            <v-col cols="12" sm="4">
              <v-btn-toggle v-model="form.operator" mandatory dense>
                <v-btn value="above" small>above</v-btn>
                <v-btn value="below" small>below</v-btn>
              </v-btn-toggle>
            </v-col>
            <v-col cols="6" sm="4">
              <v-text-field v-model.number="form.threshold" type="number" label="value" :suffix="unit" />
            </v-col>
            <v-col cols="6" sm="4">
              <v-text-field v-model.number="form.durationMinutes" type="number" label="for more than" suffix="min" />
            </v-col>
          </v-row>
          <v-switch v-model="form.enabled" label="enabled" dense hide-details class="mt-0" />

          <div class="preview">
            <div class="preview-title">
              Preview <small>{{ sentence(formRule) }}</small>
              <v-progress-circular v-if="preview.loading" indeterminate size="14" width="2" class="ml-2" />
            </div>
            <div v-if="preview.error" class="preview-error">{{ preview.error }}</div>
            <table v-else-if="preview.data" class="preview-table">
              <thead>
                <tr><th>server</th><th>now</th><th>state</th><th>last {{ preview.data.hours }}h</th></tr>
              </thead>
              <tbody>
                <tr v-for="s in preview.data.servers" :key="s.server">
                  <td>{{ s.server }}</td>
                  <td>{{ value(formRule, s.current) }}</td>
                  <td :class="'state-' + stateOf(formRule, s)">{{ stateText(formRule, s) }}</td>
                  <td>{{ backtestText(s.server) }}</td>
                </tr>
                <tr v-if="!preview.data.servers.length"><td colspan="4">no server sends this metric</td></tr>
              </tbody>
            </table>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="save">OK</v-btn>
          <v-btn color="green darken-1" text @click="dialog.active = false">Cancel</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="removeDialog.active" max-width="600">
      <v-card>
        <v-card-title class="text-h5">Remove alert rule</v-card-title>
        <v-card-text>Are you sure you want to remove the rule '{{ removeDialog.rule.name }}'?</v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="confirmRemove">OK</v-btn>
          <v-btn color="green darken-1" text @click="removeDialog.active = false">Cancel</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-row>
</template>

<script>
import client from '../commons/client'

const emptyForm = () => ({ id: null, name: '', metric: 'cpu_temp', server: '', operator: 'above', threshold: 80, durationMinutes: 5, enabled: true })

export default {
  name: 'AlertsPage',
  data() {
    return {
      rules: [],
      metrics: [],
      servers: [],
      loaded: false,
      dialog: { active: false, mode: 'add' },
      form: emptyForm(),
      preview: { loading: false, data: null, error: null },
      removeDialog: { active: false, rule: {} },
    }
  },
  computed: {
    serverItems() {
      return [{ text: 'All servers', value: '' }, ...this.servers.map((s) => ({ text: s, value: s }))]
    },
    unit() {
      const m = this.metrics.find((x) => x.key === this.form.metric)
      return m ? m.unit : ''
    },
    // the form as a rule, to reuse the formatting of the list
    formRule() {
      return {
        metric: this.form.metric,
        server: this.form.server,
        operator: this.form.operator,
        threshold: this.form.threshold,
        duration_minutes: this.form.durationMinutes,
      }
    },
  },
  watch: {
    form: {
      deep: true,
      handler() {
        if (this.dialog.active) this.schedulePreview()
      },
    },
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    client.get('/alert-rules/metrics').then((r) => (this.metrics = r.data.hits)).catch((e) => this.showError(e))
    client
      .get('/metrics/servers')
      .then((r) => (this.servers = r.data.hits.map((s) => s.server).sort()))
      .catch(() => {})
    this.reload()
    this.timer = setInterval(() => this.reload(), 30000)
  },
  beforeDestroy() {
    clearInterval(this.timer)
    clearTimeout(this.previewTimer)
  },
  methods: {
    showError(e) {
      console.error(e)
      const message = e.response && e.response.data ? e.response.data.message || (e.response.data.errors || []).join(', ') : e.message
      if (this.logModal) this.logModal.open(e.name, message)
    },
    reload() {
      client
        .get('/alert-rules')
        .then((r) => {
          this.rules = r.data.hits
          this.loaded = true
        })
        .catch((e) => this.showError(e))
    },
    metric(key) {
      return this.metrics.find((m) => m.key === key) || { label: key, unit: '' }
    },
    format(v) {
      return Number.isInteger(v) ? String(v) : Number(v).toFixed(1)
    },
    value(rule, v) {
      if (v === null || v === undefined) return 'no data'
      const unit = this.metric(rule.metric).unit
      return unit === '%' ? `${Number(v).toFixed(v < 10 ? 1 : 0)}%` : `${Math.round(v)}${unit === 'RPM' ? ' RPM' : unit}`
    },
    sentence(rule) {
      const m = this.metric(rule.metric)
      const where = rule.server ? `on ${rule.server}` : 'on any server'
      return `${m.label} ${rule.operator} ${this.format(rule.threshold)}${m.unit === 'RPM' ? ' RPM' : m.unit} for more than ${rule.duration_minutes} min, ${where}`
    },
    minutesSince(ms) {
      return Math.max(0, Math.floor((Date.now() - ms) / 60000))
    },
    // ok | pending (in the condition, not long enough) | firing | nodata
    stateOf(rule, s) {
      if (s.current === null || s.current === undefined) return 'nodata'
      if (s.firing) return 'firing'
      if (s.in_condition_since) return 'pending'
      return 'ok'
    },
    stateText(rule, s) {
      switch (this.stateOf(rule, s)) {
        case 'firing':
          return `would alert now (for ${this.minutesSince(s.in_condition_since)} min)`
        case 'pending':
          return `in the condition for ${this.minutesSince(s.in_condition_since)} of ${rule.duration_minutes} min`
        case 'nodata':
          return 'no recent data'
        default:
          return 'ok'
      }
    },
    tooltip(rule, s) {
      return `${s.server}: ${this.stateText(rule, s)}`
    },
    backtestText(server) {
      const b = (this.preview.data.backtest || []).find((x) => x.server === server)
      if (!b) return '-'
      if (!b.count) return 'would not have alerted'
      return `would have alerted ${b.count}× (${b.total_minutes} min in total, longest ${b.longest_minutes} min)`
    },
    metricChanged(key) {
      // suggests the usual operator and value of the metric
      const m = this.metric(key)
      if (m.default_operator) this.form.operator = m.default_operator
      if (m.default_threshold !== undefined) this.form.threshold = m.default_threshold
    },
    body() {
      return {
        name: this.form.name,
        metric: this.form.metric,
        server: this.form.server,
        operator: this.form.operator,
        threshold: Number(this.form.threshold),
        duration_minutes: Number(this.form.durationMinutes),
        enabled: this.form.enabled,
      }
    },
    schedulePreview() {
      clearTimeout(this.previewTimer)
      this.previewTimer = setTimeout(() => this.loadPreview(), 400)
    },
    loadPreview() {
      this.preview.loading = true
      const asked = JSON.stringify(this.body())
      client
        .post('/alert-rules/preview', this.body())
        .then((r) => {
          // ignores an older answer that arrives after the form changed
          if (asked !== JSON.stringify(this.body())) return
          this.preview.data = r.data
          this.preview.error = null
        })
        .catch((e) => {
          this.preview.data = null
          this.preview.error = e.response && e.response.data ? e.response.data.message || (e.response.data.errors || []).join(', ') : e.message
        })
        .finally(() => (this.preview.loading = false))
    },
    openDialog(mode, form) {
      this.dialog.mode = mode
      this.form = form
      this.preview = { loading: false, data: null, error: null }
      this.dialog.active = true
      this.loadPreview()
    },
    addRule() {
      this.openDialog('add', emptyForm())
    },
    editRule(rule) {
      this.openDialog('edit', {
        id: rule.id,
        name: rule.name,
        metric: rule.metric,
        server: rule.server,
        operator: rule.operator,
        threshold: rule.threshold,
        durationMinutes: rule.duration_minutes,
        enabled: rule.enabled,
      })
    },
    save() {
      const request = this.dialog.mode === 'add' ? client.post('/alert-rule', this.body()) : client.put('/alert-rule/' + this.form.id, this.body())
      request
        .then(() => {
          this.dialog.active = false
          this.reload()
        })
        // keeps the dialog open, so a validation error does not lose the rule
        .catch((e) => this.showError(e))
    },
    setEnabled(rule, enabled) {
      client
        .put('/alert-rule/' + rule.id, {
          name: rule.name,
          metric: rule.metric,
          server: rule.server,
          operator: rule.operator,
          threshold: rule.threshold,
          duration_minutes: rule.duration_minutes,
          enabled,
        })
        .then(() => this.reload())
        .catch((e) => {
          this.showError(e)
          this.reload()
        })
    },
    removeRule(rule) {
      this.removeDialog.rule = rule
      this.removeDialog.active = true
    },
    confirmRemove() {
      client
        .delete('/alert-rule/' + this.removeDialog.rule.id)
        .then(() => {
          this.removeDialog.active = false
          this.reload()
        })
        .catch((e) => {
          this.removeDialog.active = false
          this.showError(e)
        })
    },
  },
}
</script>

<style lang="scss">
#plus-button {
  height: 50px;
  width: 50px;
  border-radius: 50%;
  background-color: $secondary-color;
}

#plus-button i {
  color: white;
}

.alerts-help {
  margin-bottom: 20px;
  padding: 12px 16px;
  font-size: 0.9rem;
  background-color: $primary-color !important;
}

.rule-card {
  margin-bottom: 20px;
  background-color: $primary-color !important;
}

.rule-card-title {
  background-color: $title-color;
}

.rule-firing {
  margin-left: 10px;
  padding: 1px 8px;
  border-radius: 10px;
  background: #d32f2f;
  color: white;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.rule-sentence {
  font-size: 0.95rem;
  margin-bottom: 8px;
  color: #33563f;
}

.rule-servers {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.rule-server {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 9px;
  border: 1px solid currentColor;
  border-radius: 12px;
  font-size: 12px;

  small {
    opacity: 0.85;
  }
}

.state-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

.state-ok {
  color: green;
}

.state-pending {
  color: #e67e00;
}

.state-firing {
  color: #d32f2f;
  font-weight: 600;
}

.state-nodata,
.rule-none {
  color: #888;
}

.preview {
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.04);

  .preview-title {
    font-weight: 600;
    margin-bottom: 6px;

    small {
      font-weight: normal;
      margin-left: 6px;
      color: #555;
    }
  }

  .preview-error {
    color: #d32f2f;
  }
}

.preview-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  th {
    text-align: left;
    font-weight: 600;
    color: #555;
    border-bottom: 1px solid rgba(0, 0, 0, 0.12);
    padding: 3px 6px;
  }

  td {
    padding: 3px 6px;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  }
}
</style>
