<template>
  <div class="server-status">
    <strong>Status</strong>:
    <span :class="['status-label', 'status-' + status]">{{ label }}</span>
    <span v-if="servers.length" class="status-servers">
      <span
        v-for="s in servers"
        :key="s.server"
        :class="['status-server', 'status-' + s.status]"
        :title="tooltip(s)"
      >
        <span class="status-dot"></span>{{ s.server }}
        <span v-if="s.status === 'F' && s.message" class="status-message">{{ s.message }}</span>
      </span>
    </span>
    <span v-else class="status-none">no server is checking it</span>
  </div>
</template>

<script>
// Aggregated status plus the check of each server. status: W working, F failing, U unknown.
export default {
  name: 'ServerStatus',
  props: {
    status: { type: String, default: 'U' },
    servers: { type: Array, default: () => [] },
  },
  computed: {
    label() {
      return { W: 'Working', F: 'Failure' }[this.status] || 'Unknown'
    },
  },
  methods: {
    tooltip(s) {
      const seconds = Math.max(0, Math.round((Date.now() - s.checked_at) / 1000))
      return `${s.server}: ${s.status === 'W' ? 'working' : 'failing'}${s.message ? ' - ' + s.message : ''} (checked ${seconds}s ago)`
    },
  },
}
</script>

<style lang="scss">
.server-status {
  .status-label {
    font-weight: 600;
  }

  .status-W {
    color: green;
  }

  .status-F {
    color: #d32f2f;
  }

  .status-U {
    color: #888;
  }

  .status-servers {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 4px;
  }

  .status-server {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 0 8px;
    border: 1px solid currentColor;
    border-radius: 10px;
    font-size: 12px;
    cursor: default;
  }

  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background-color: currentColor;
  }

  .status-message {
    color: #666;
    max-width: 260px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .status-none {
    margin-left: 6px;
    font-size: 12px;
    color: #888;
  }
}
</style>
