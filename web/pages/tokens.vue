<template>
  <v-row justify="center">
    <v-col cols="12" lg="10">
      <v-card elevation="2" id="tokens-card">
        <div class="tokens-header">
          <div>
            <h2>API tokens</h2>
            <p class="tokens-help">
              Permanent tokens for integrations. Send them as <code>Authorization: Bearer xatu_...</code>.
              See the <a href="/api-docs">API docs</a>.
            </p>
          </div>
          <v-btn color="green darken-1" dark @click="openCreate">
            <v-icon left>mdi-plus</v-icon>New token
          </v-btn>
        </div>

        <div class="tokens-toolbar">
          <v-text-field
            v-model="search"
            prepend-inner-icon="mdi-magnify"
            placeholder="Search by name or prefix"
            clearable
            hide-details
            dense
            outlined
            class="tokens-search"
          ></v-text-field>
          <v-switch v-model="showRevoked" label="show revoked" hide-details dense class="mt-0"></v-switch>
        </div>

        <v-data-table
          :headers="headers"
          :items="visible"
          :search="search"
          :loading="loading"
          sort-by="create_date"
          sort-desc
          dense
          class="tokens-table"
        >
          <template v-slot:no-data>
            <div class="tokens-empty">No tokens yet. Create one with "New token".</div>
          </template>
          <template v-slot:item.name="{ item }">
            <strong :class="{ revoked: !item.active }">{{ item.name }}</strong>
          </template>
          <template v-slot:item.token_prefix="{ item }">
            <code>{{ item.token_prefix }}…</code>
          </template>
          <template v-slot:item.read_only="{ item }">
            <v-chip x-small :color="item.read_only ? 'blue lighten-4' : 'orange lighten-4'">
              <v-icon x-small left>{{ item.read_only ? 'mdi-eye' : 'mdi-pencil' }}</v-icon>{{ item.read_only ? 'read only' : 'full access' }}
            </v-chip>
          </template>
          <template v-slot:item.create_date="{ item }">{{ dateTime(item.create_date) }}</template>
          <template v-slot:item.last_used="{ item }">
            <span v-if="item.last_used" :title="dateTime(item.last_used)">{{ ago(item.last_used) }}</span>
            <span v-else class="muted">never</span>
          </template>
          <template v-slot:item.active="{ item }">
            <span v-if="item.active" class="active"><v-icon x-small color="green">mdi-circle</v-icon> active</span>
            <span v-else class="muted" :title="'revoked ' + dateTime(item.revoked_date)">revoked</span>
          </template>
          <template v-slot:item.actions="{ item }">
            <v-btn v-if="item.active" text x-small color="red" @click="askRevoke(item)">
              <v-icon x-small left>mdi-cancel</v-icon>revoke
            </v-btn>
          </template>
        </v-data-table>
      </v-card>
    </v-col>

    <!-- create -->
    <v-dialog v-model="create.open" max-width="640" persistent>
      <v-card v-if="!create.token">
        <v-card-title>New API token</v-card-title>
        <v-card-text>
          <v-form ref="form" @submit.prevent="submit">
            <v-text-field
              v-model="create.name"
              label="name"
              placeholder="who will use it, e.g. grafana, billing-sync"
              :rules="[(v) => !!(v && v.trim()) || 'name is required']"
              autofocus
              counter="200"
              maxlength="200"
            ></v-text-field>
            <v-switch v-model="create.readOnly" inset hide-details>
              <template v-slot:label>
                <span v-if="create.readOnly"><strong>read only</strong>: only GET requests (recommended for integrations)</span>
                <span v-else><strong>full access</strong>: can also create and delete monitored services, apis and containers</span>
              </template>
            </v-switch>
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="create.open = false">Cancel</v-btn>
          <v-btn color="green darken-1" dark :loading="create.saving" @click="submit">Create</v-btn>
        </v-card-actions>
      </v-card>

      <v-card v-else>
        <v-card-title><v-icon left color="green">mdi-check-circle</v-icon>Token "{{ create.name }}" created</v-card-title>
        <v-card-text>
          <v-alert type="warning" dense outlined>
            Copy it now: it is shown only once. Xatu keeps only its hash; if lost, revoke it and create another.
          </v-alert>
          <div class="token-box">
            <code>{{ create.token }}</code>
            <v-btn small color="green darken-1" dark @click="copy(create.token)">
              <v-icon small left>{{ copied ? 'mdi-check' : 'mdi-content-copy' }}</v-icon>{{ copied ? 'copied' : 'copy' }}
            </v-btn>
          </div>
          <div class="token-example">
            <span>example</span>
            <pre>curl -H "Authorization: Bearer {{ create.token }}" \
  "{{ apiBase }}/metrics/containers/overview?q=n8n"</pre>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="closeCreated">I saved it, close</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <!-- revoke -->
    <v-dialog v-model="revoke.open" max-width="480">
      <v-card v-if="revoke.item">
        <v-card-title>Revoke "{{ revoke.item.name }}"?</v-card-title>
        <v-card-text>
          Integrations using <code>{{ revoke.item.token_prefix }}…</code> will get <strong>401</strong> in up to 1 minute.
          This cannot be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn text @click="revoke.open = false">Cancel</v-btn>
          <v-btn color="red" dark :loading="revoke.saving" @click="confirmRevoke">Revoke</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-row>
</template>

<script>
import client from '../commons/client'
import { dateTime, duration } from '../commons/format'

export default {
  name: 'TokensPage',
  data() {
    return {
      tokens: [],
      loading: false,
      search: '',
      showRevoked: false,
      copied: false,
      create: { open: false, name: '', readOnly: true, saving: false, token: null },
      revoke: { open: false, item: null, saving: false },
      headers: [
        { text: 'Name', value: 'name' },
        { text: 'Token', value: 'token_prefix' },
        { text: 'Access', value: 'read_only' },
        { text: 'Created', value: 'create_date' },
        { text: 'Last used', value: 'last_used' },
        { text: 'Status', value: 'active' },
        { text: '', value: 'actions', sortable: false, align: 'end' },
      ],
    }
  },
  computed: {
    visible() {
      return this.showRevoked ? this.tokens : this.tokens.filter((t) => t.active)
    },
    apiBase() {
      return typeof window === 'undefined' ? '/api' : `${window.location.origin}/api`
    },
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    this.load()
  },
  methods: {
    dateTime,
    ago(ms) {
      return `${duration(Date.now() - ms)} ago`
    },
    showError(e) {
      console.error(e)
      const message = e.response && e.response.data ? e.response.data.message : e.message
      if (this.logModal) this.logModal.open(e.name, message)
    },
    load() {
      this.loading = true
      client
        .get('/tokens')
        .then((response) => (this.tokens = response.data.hits))
        .catch((e) => this.showError(e))
        .finally(() => (this.loading = false))
    },
    openCreate() {
      this.create = { open: true, name: '', readOnly: true, saving: false, token: null }
      this.copied = false
    },
    submit() {
      if (!this.$refs.form.validate()) return
      this.create.saving = true
      client
        .post('/tokens', { name: this.create.name.trim(), read_only: this.create.readOnly })
        .then((response) => {
          this.create.token = response.data.token
          this.load()
        })
        .catch((e) => this.showError(e))
        .finally(() => (this.create.saving = false))
    },
    closeCreated() {
      // the token is not kept anywhere after the dialog closes
      this.create = { open: false, name: '', readOnly: true, saving: false, token: null }
    },
    copy(text) {
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(() => (this.copied = true))
    },
    askRevoke(item) {
      this.revoke = { open: true, item, saving: false }
    },
    confirmRevoke() {
      this.revoke.saving = true
      client
        .delete(`/tokens/${this.revoke.item.id}`)
        .then(() => {
          this.revoke.open = false
          this.load()
        })
        .catch((e) => this.showError(e))
        .finally(() => (this.revoke.saving = false))
    },
  },
}
</script>

<style lang="scss">
#tokens-card {
  background-color: $primary-color;
  padding: 20px 24px;
}

.tokens-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;

  h2 {
    color: #33563f;
  }

  .tokens-help {
    color: #33563f;
    margin: 4px 0 12px;
  }
}

.tokens-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 12px;

  .tokens-search {
    flex: 0 1 420px;
    background: white;
  }
}

.tokens-table {
  .revoked {
    text-decoration: line-through;
    color: #999;
  }

  .muted {
    color: #999;
  }

  .active {
    color: #2e7d32;
  }
}

.tokens-empty {
  padding: 24px;
  color: #666;
}

.token-box {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
  padding: 12px;
  background: #1b1f23;
  border-radius: 6px;

  code {
    flex: 1;
    background: transparent !important;
    color: #7ee787 !important;
    font-size: 14px;
    word-break: break-all;
  }
}

.token-example {
  span {
    font-size: 11px;
    text-transform: uppercase;
    color: #888;
  }

  pre {
    background: #f4f6f5;
    padding: 10px;
    border-radius: 6px;
    font-size: 12px;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
