<template>
  <v-row justify="center">
    <v-col cols="12">
      <v-card
        elevation="2"
        id="logs-container-card"
      >
        <form
          @submit.prevent="searchForLogs"
          class="logs-form"
        >
          <v-text-field
            v-model="query"
            placeholder="service_id:1 AND message:healthcheck"
            label="query"
            class="query-field"
            hide-details
          ></v-text-field>
          <v-text-field
            v-model="since"
            placeholder="how much seconds ago"
            label="since (seconds)"
            class="since-field"
            hide-details
          ></v-text-field>
          <v-btn
            type="submit"
            color="green darken-1"
            dark
          >
            <v-icon left>mdi-magnify</v-icon>Search
          </v-btn>
          <v-checkbox
            label="follow"
            v-model="follow"
            hide-details
          ></v-checkbox>
          <v-btn
            text
            small
            class="options-toggle"
            @click="showOptions = !showOptions"
          >
            <v-icon small left>mdi-tune</v-icon>options
            <v-icon small right>{{ showOptions ? 'mdi-chevron-up' : 'mdi-chevron-down' }}</v-icon>
          </v-btn>
        </form>

        <div v-if="showOptions" class="logs-options">
          <v-text-field
            v-model="size"
            type="number"
            min="1"
            max="10000"
            label="max logs"
            placeholder="500"
            hint="max number of logs returned (1 - 10000), default 500"
            persistent-hint
            dense
            class="size-field"
          ></v-text-field>
        </div>

        <div class="logs-header">
          <span>
            {{ logs.length }} logs
            <span v-if="limitReached" class="limit-reached">
              (limit reached, older logs not shown: narrow the search or raise "max logs" in options)
            </span>
          </span>
          <span v-if="follow" class="following">
            <v-icon small color="green">mdi-circle</v-icon> following (every 5s)
          </span>
        </div>

        <div class="logs-card">
          <div v-if="!logs.length" class="logs-empty">
            No logs. Run a search to see logs here.
          </div>
          <div
            v-for="log in logs"
            :class="['log-row', 'log-' + levelOf(log.message)]"
            :key="hash(log)"
          >
            <span
              class="log-time"
              :title="timeSince(log.created_at)"
            >{{ formatTime(log.created_at) }}</span>
            <span class="log-tags">
              <span
                v-if="log.server"
                class="log-tag"
                :style="tagStyle(log.server)"
                title="server"
              >
                <v-icon x-small>mdi-server-network</v-icon> {{ log.server }}
              </span>
              <span
                v-if="log.service_id"
                class="log-tag"
                :style="tagStyle(log.service_name)"
                :title="'service ' + log.service_id + (log.filename ? ' - ' + log.filename : '')"
              >
                <v-icon x-small>mdi-cog</v-icon> {{ log.service_name }}
              </span>
              <span
                v-else
                class="log-tag"
                :style="tagStyle(log.container_name)"
                :title="'container ' + log.container_id"
              >
                <v-icon x-small>mdi-docker</v-icon> {{ log.container_name }}
              </span>
              <span
                v-if="log.filename"
                class="log-file"
              >{{ log.filename }}</span>
            </span>
            <span class="log-message">{{ log.message }}</span>
          </div>
        </div>
      </v-card>
    </v-col>
  </v-row>
</template>

<script>
import client from '../commons/client'
import MD5 from '../commons/MD5'

export default {
  name: 'LogsPage',
  data() {
    return { query: '', since: 60, size: '', showOptions: false, logs: [], follow: false, lastSize: 500 }
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
  },
  beforeDestroy() {
    clearInterval(this.followInterval)
  },
  computed: {
    limitReached() {
      return this.logs.length > 0 && this.logs.length >= this.lastSize
    },
  },
  watch: {
    follow(newVal) {
      const that = this
      if (newVal)
        that.followInterval = setInterval(() => {
          that.searchForLogs()
        }, 5000)
      else clearInterval(that.followInterval)
    },
  },
  methods: {
    hash(log) {
      return MD5(JSON.stringify(log))
    },
    formatTime(date) {
      const d = new Date(date)
      const pad = (n, size = 2) => String(n).padStart(size, '0')
      return (
        `${pad(d.getDate())}/${pad(d.getMonth() + 1)} ` +
        `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`
      )
    },
    levelOf(message) {
      if (/\b(error|exception|fatal|critical|panic|traceback)\b/i.test(message)) return 'error'
      if (/\b(warn|warning)\b/i.test(message)) return 'warn'
      return 'info'
    },
    tagStyle(name) {
      // Same name always gets the same color, so servers/sources are easy to tell apart
      let h = 0
      for (const c of String(name)) h = (h * 31 + c.charCodeAt(0)) % 360
      return {
        color: `hsl(${h}, 80%, 75%)`,
        backgroundColor: `hsla(${h}, 70%, 45%, 0.18)`,
        borderColor: `hsla(${h}, 70%, 60%, 0.45)`,
      }
    },
    timeSince(date) {
      var seconds = Math.floor((new Date() - date) / 1000)

      var interval = seconds / 31536000

      if (interval > 1) {
        return Math.floor(interval) + ' years ago'
      }
      interval = seconds / 2592000
      if (interval > 1) {
        return Math.floor(interval) + ' months ago'
      }
      interval = seconds / 86400
      if (interval > 1) {
        return Math.floor(interval) + ' days ago'
      }
      interval = seconds / 3600
      if (interval > 1) {
        return Math.floor(interval) + ' hours ago'
      }
      interval = seconds / 60
      if (interval > 1) {
        return Math.floor(interval) + ' minutes ago'
      }
      return Math.floor(seconds) + ' seconds ago'
    },

    searchForLogs() {
      const that = this
      var fullQuery = this.since
        ? `${this.query} AND created_at:>${
            Date.now() - 1000 * parseInt(this.since)
          }`
        : this.query
      const params = { query: fullQuery }
      if (this.size) params.size = parseInt(this.size)
      console.log('Searching for logs... Params: ', params)

      client
        .get('/logs', { params })
        .then((response) => {
          that.lastSize = params.size || 500
          that.logs = response.data.hits.sort(function (a, b) {
            return b.created_at - a.created_at
          })
        })
        .catch((e) => {
          console.error(e)
          const message = e.response.data.message
          that.logModal.open(e.name, message)
        })
    },
  },
}
</script>

<style @scoped lang="scss">
#logs-container-card {
  background-color: $primary-color;
  padding: 24px;
}

.logs-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px !important;

  .query-field {
    flex: 1 1 400px;
  }

  .since-field {
    flex: 0 0 160px;
  }

  .v-input--checkbox {
    margin-top: 0;
  }
}

.options-toggle {
  text-transform: none !important;
  color: #33563f !important;
  opacity: 0.8;
}

.logs-options {
  margin: -4px 0 16px;

  .size-field {
    max-width: 260px;
  }
}

.limit-reached {
  color: #b26a00;
  font-weight: 600;
}

.logs-header {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #33563f;
  margin-bottom: 6px;

  .following {
    font-weight: 600;
  }
}

.logs-card {
  background-color: #1b1f23;
  border-radius: 6px;
  height: calc(100vh - 260px);
  min-height: 450px;
  overflow-y: auto;
  font-family: 'JetBrains Mono', 'Fira Code', Consolas, 'Courier New', monospace;
  font-size: 13px;
  color: #d8dee4;
}

.logs-empty {
  padding: 24px;
  color: #8b949e;
  text-align: center;
}

.log-row {
  display: grid;
  grid-template-columns: max-content minmax(180px, max-content) 1fr;
  gap: 12px;
  align-items: start;
  padding: 5px 12px;
  border-left: 3px solid transparent;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);

  &:nth-child(even) {
    background-color: rgba(255, 255, 255, 0.025);
  }

  &:hover {
    background-color: rgba(255, 255, 255, 0.07);
  }

  &.log-error {
    border-left-color: #f85149;
    background-color: rgba(248, 81, 73, 0.1);
  }

  &.log-warn {
    border-left-color: #d29922;
    background-color: rgba(210, 153, 34, 0.08);
  }

  // Narrow screens: message goes below time and tags
  @media (max-width: 1280px) {
    grid-template-columns: max-content 1fr;
    gap: 4px 12px;

    .log-message {
      grid-column: 1 / -1;
    }
  }
}

.log-time {
  color: #8b949e;
  white-space: nowrap;
  cursor: default;
}

.log-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.log-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 0 6px;
  border: 1px solid;
  border-radius: 10px;
  font-size: 12px;
  white-space: nowrap;

  .v-icon {
    color: inherit !important;
  }
}

.log-file {
  color: #8b949e;
  font-size: 12px;
}

.log-message {
  white-space: pre-wrap;
  word-break: break-word;
}

.log-error .log-message {
  color: #ffa198;
}

.log-warn .log-message {
  color: #e3b341;
}
</style>
