<template>
  <v-row justify="center">
    <v-col cols="12" md="10" lg="8">
      <v-card class="tg-help" outlined tile>
        Every alert goes to the main chat and to each enabled phone or group below.
        <strong>To add a phone:</strong> on it, open
        <a v-if="bot" :href="'https://t.me/' + bot" target="_blank" rel="noopener">@{{ bot }}</a><span v-else>the bot</span>
        in Telegram and send <code>/start</code> (a bot can only write to who wrote to it first). Then click
        <strong>+</strong>, <em>Find chat id</em>, pick the phone and send a test message.
        <div v-if="loaded && !active" class="tg-warning">Telegram is not configured on this server (TELEGRAM_BOT_TOKEN), nothing is sent.</div>
      </v-card>

      <v-card class="tg-card" outlined tile>
        <v-card-title class="tg-card-title">
          <v-icon class="mr-2" color="green darken-2">mdi-cellphone-message</v-icon>
          Main chat
          <v-spacer></v-spacer>
          <v-btn v-if="primary" small text @click="test(primary)" :loading="testing === primary">
            <v-icon small left>mdi-send</v-icon>test
          </v-btn>
        </v-card-title>
        <v-card-text>
          <template v-if="primary"><code>{{ primary }}</code> &nbsp;always receives the alerts, it is set in the service (TELEGRAM_CHAT_ID).</template>
          <template v-else>No TELEGRAM_CHAT_ID set in the service.</template>
        </v-card-text>
      </v-card>

      <v-card v-for="c in chats" :key="c.id" class="tg-card" outlined tile>
        <v-card-title class="tg-card-title">
          <v-icon class="mr-2" :color="c.enabled ? 'green darken-2' : 'grey'">{{ c.enabled ? 'mdi-cellphone' : 'mdi-cellphone-off' }}</v-icon>
          <v-btn @click="edit(c)">{{ c.name }}</v-btn>
          <v-spacer></v-spacer>
          <v-switch :input-value="c.enabled" dense hide-details class="mt-0 mr-2" :label="c.enabled ? 'receives' : 'paused'" @change="(v) => setEnabled(c, v)" />
          <v-btn small text @click="test(c.chat_id)" :loading="testing === c.chat_id">
            <v-icon small left>mdi-send</v-icon>test
          </v-btn>
          <v-btn icon @click="askRemove(c)">
            <v-icon>mdi-close</v-icon>
          </v-btn>
        </v-card-title>
        <v-card-text>chat id <code>{{ c.chat_id }}</code> · added {{ date(c.create_date) }}</v-card-text>
      </v-card>

      <p v-if="loaded && !chats.length" class="tg-empty">No other phones yet.</p>

      <button @click="add" id="plus-button">
        <v-icon>mdi-plus</v-icon>
      </button>
    </v-col>

    <v-dialog v-model="dialog.active" max-width="640">
      <v-card>
        <v-card-title class="text-h5">{{ dialog.mode === 'add' ? 'Add a phone or group' : 'Edit' }}</v-card-title>
        <v-card-text>
          <v-text-field v-model="form.name" label="name" placeholder="e.g. Ana's phone" />
          <v-text-field v-model="form.chatId" label="chat id" placeholder="e.g. 2070462929 (a group starts with -)" hint="the number of the chat, not the phone number" persistent-hint>
            <template #append-outer>
              <v-btn small text @click="findChats" :loading="recent.loading">find chat id</v-btn>
            </template>
          </v-text-field>
          <v-switch v-model="form.enabled" label="receives the alerts" dense hide-details />

          <div v-if="recent.shown" class="tg-recent">
            <div class="tg-recent-title">Who wrote to the bot in the last 24 hours — click to use</div>
            <div v-if="recent.error" class="tg-error">{{ recent.error }}</div>
            <div v-else-if="!recent.chats.length" class="tg-muted">Nobody yet. Send <code>/start</code> to the bot from the phone and try again.</div>
            <button v-for="r in recent.chats" :key="r.chat_id" class="tg-recent-item" @click="pick(r)">
              <v-icon small>{{ r.kind === 'private' ? 'mdi-account' : 'mdi-account-group' }}</v-icon>
              <strong>{{ r.name || r.chat_id }}</strong>
              <code>{{ r.chat_id }}</code>
              <span class="tg-muted">"{{ r.last_message }}" · {{ ago(r.at) }}</span>
              <span v-if="known(r.chat_id)" class="tg-known">already receives</span>
            </button>
          </div>
        </v-card-text>
        <v-card-actions>
          <v-btn text :disabled="!form.chatId" @click="test(form.chatId)" :loading="testing === form.chatId">
            <v-icon small left>mdi-send</v-icon>send test
          </v-btn>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="save">OK</v-btn>
          <v-btn color="green darken-1" text @click="dialog.active = false">Cancel</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="removeDialog.active" max-width="560">
      <v-card>
        <v-card-title class="text-h5">Remove</v-card-title>
        <v-card-text>'{{ removeDialog.chat.name }}' will stop receiving the alerts. Remove it?</v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="remove">OK</v-btn>
          <v-btn color="green darken-1" text @click="removeDialog.active = false">Cancel</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-row>
</template>

<script>
import client from '../commons/client'

export default {
  name: 'TelegramPage',
  data() {
    return {
      chats: [],
      primary: null,
      active: true,
      bot: null,
      loaded: false,
      testing: null,
      dialog: { active: false, mode: 'add' },
      form: { id: null, name: '', chatId: '', enabled: true },
      recent: { shown: false, loading: false, chats: [], error: null },
      removeDialog: { active: false, chat: {} },
    }
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    this.reload()
  },
  methods: {
    message(e) {
      return e.response && e.response.data ? e.response.data.message || (e.response.data.errors || []).join(', ') : e.message
    },
    showError(e) {
      console.error(e)
      if (this.logModal) this.logModal.open(e.name, this.message(e))
    },
    reload() {
      client
        .get('/telegram/chats')
        .then((r) => {
          this.chats = r.data.hits
          this.primary = r.data.primary
          this.active = r.data.active
          this.bot = r.data.bot
          this.loaded = true
        })
        .catch((e) => this.showError(e))
    },
    date(ts) {
      return new Date(ts).toLocaleDateString()
    },
    ago(ms) {
      const minutes = Math.floor((Date.now() - ms) / 60000)
      return minutes < 60 ? `${minutes} min ago` : `${Math.floor(minutes / 60)}h ago`
    },
    known(chatId) {
      return chatId === this.primary || this.chats.some((c) => c.chat_id === chatId)
    },
    test(chatId) {
      this.testing = chatId
      client
        .post('/telegram/test', { chat_id: String(chatId) })
        .then((r) => this.logModal.open('Sent', r.data.message + '. Check the phone.'))
        .catch((e) => this.showError(e))
        .finally(() => (this.testing = null))
    },
    openDialog(mode, form) {
      this.dialog.mode = mode
      this.form = form
      this.recent = { shown: false, loading: false, chats: [], error: null }
      this.dialog.active = true
    },
    add() {
      this.openDialog('add', { id: null, name: '', chatId: '', enabled: true })
    },
    edit(c) {
      this.openDialog('edit', { id: c.id, name: c.name, chatId: c.chat_id, enabled: c.enabled })
    },
    findChats() {
      this.recent.loading = true
      this.recent.shown = true
      client
        .get('/telegram/recent-chats')
        .then((r) => {
          this.recent.chats = r.data.hits
          this.recent.error = null
        })
        .catch((e) => (this.recent.error = this.message(e)))
        .finally(() => (this.recent.loading = false))
    },
    pick(r) {
      this.form.chatId = r.chat_id
      if (!this.form.name) this.form.name = r.name
    },
    body(form) {
      return { name: form.name, chat_id: String(form.chatId).trim(), enabled: form.enabled }
    },
    save() {
      const request = this.dialog.mode === 'add' ? client.post('/telegram/chat', this.body(this.form)) : client.put('/telegram/chat/' + this.form.id, this.body(this.form))
      request
        .then(() => {
          this.dialog.active = false
          this.reload()
        })
        // keeps the dialog open on a validation error
        .catch((e) => this.showError(e))
    },
    setEnabled(c, enabled) {
      client
        .put('/telegram/chat/' + c.id, this.body({ name: c.name, chatId: c.chat_id, enabled }))
        .then(() => this.reload())
        .catch((e) => {
          this.showError(e)
          this.reload()
        })
    },
    askRemove(c) {
      this.removeDialog.chat = c
      this.removeDialog.active = true
    },
    remove() {
      client
        .delete('/telegram/chat/' + this.removeDialog.chat.id)
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

.tg-help {
  margin-bottom: 20px;
  padding: 12px 16px;
  font-size: 0.9rem;
  background-color: $primary-color !important;
}

.tg-warning,
.tg-error {
  margin-top: 6px;
  color: #d32f2f;
}

.tg-card {
  margin-bottom: 16px;
  background-color: $primary-color !important;
}

.tg-card-title {
  background-color: $title-color;
}

.tg-empty,
.tg-muted {
  color: #666;
  font-size: 0.9rem;
}

.tg-recent {
  margin-top: 16px;
  padding: 10px 12px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.04);

  .tg-recent-title {
    font-weight: 600;
    margin-bottom: 6px;
  }
}

.tg-recent-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  text-align: left;
  padding: 6px 8px;
  border-radius: 4px;

  &:hover {
    background: rgba(0, 0, 0, 0.06);
  }
}

.tg-known {
  margin-left: auto;
  font-size: 12px;
  color: green;
}
</style>
