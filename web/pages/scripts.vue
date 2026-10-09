<template>
  <v-row justify="center" align="center">
    <v-col cols="12" sm="10" md="8">
      <v-card class="scripts-help" outlined tile>
        Each script runs with <code>bash</code> on every server (as the user of the Xatu service), once every interval.
        It fails when it exits with a code other than 0 or takes longer than the timeout, and the failure goes to telegram.
        <code>$XATU_SERVER</code> has the name of the server.
      </v-card>

      <v-card v-if="!scripts.length && loaded" class="scripts-help" outlined tile>No scripts yet. Add one with the + button.</v-card>

      <v-card class="script-card" v-for="script in scripts" :key="script.id" elevation="2" outlined tile>
        <v-card-title class="script-card-title">
          <span>{{ script.id }} -&ensp;</span>
          <v-btn @click="() => editScript(script)">{{ script.name }}</v-btn>
          <v-spacer></v-spacer>
          <span class="script-schedule">
            every {{ duration(script.interval_seconds) }}, timeout {{ duration(script.timeout_seconds) }}
          </span>
          <v-btn icon @click="() => removeScript(script)">
            <v-icon>mdi-close</v-icon>
          </v-btn>
        </v-card-title>
        <v-card-text>
          <pre class="script-code" @click="toggle(script.id)" :title="expanded[script.id] ? 'click to collapse' : 'click to see it all'">{{ preview(script) }}</pre>
          <ServerStatus :status="script.status" :servers="script.servers" />
        </v-card-text>
      </v-card>

      <button @click="addScript" id="plus-button">
        <v-icon>mdi-plus</v-icon>
      </button>
    </v-col>
    <v-dialog v-model="addEditDialog.active" max-width="800">
      <v-card>
        <v-card-title class="text-h5">
          {{ addEditDialog.mode === "add" ? "Add a new script" : "Edit script" }}
        </v-card-title>
        <v-card-text>
          <v-text-field placeholder="what it checks, e.g. backup-done" label="name" v-model="addEditDialog.name" />
          <v-textarea
            class="script-editor"
            label="bash script"
            placeholder="test -n &quot;$(find /backup -name '*.tar.gz' -mmin -1500)&quot; || { echo 'no backup in the last 25h'; exit 1; }"
            v-model="addEditDialog.script"
            rows="10"
            auto-grow
            outlined
            spellcheck="false"
          />
          <v-row>
            <v-col cols="6">
              <v-text-field type="number" label="interval (seconds)" v-model.number="addEditDialog.intervalSeconds"
                :hint="'runs every ' + duration(addEditDialog.intervalSeconds) + ', at least 10s'" persistent-hint />
            </v-col>
            <v-col cols="6">
              <v-text-field type="number" label="timeout (seconds)" v-model.number="addEditDialog.timeoutSeconds"
                :hint="'killed after ' + duration(addEditDialog.timeoutSeconds) + ', not more than the interval'" persistent-hint />
            </v-col>
          </v-row>
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="addOrEdit">
            OK
          </v-btn>
          <v-btn color="green darken-1" text @click="addEditDialog.active = false">
            Cancel
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog v-model="removeDialog.active" max-width="600">
      <v-card>
        <v-card-title class="text-h5">
          Remove script
        </v-card-title>
        <br />
        <v-card-text>
          Are you sure you want to remove the script '{{ removeDialog.toRemoveScript.name }}'
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="confirmRemoveScript">
            OK
          </v-btn>
          <v-btn color="green darken-1" text @click="removeDialog.active = false">
            Cancel
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-row>
</template>

<script>
import client from '../commons/client'

const PREVIEW_LINES = 4

export default {
  name: 'ScriptsPage',
  data() {
    return {
      scripts: [],
      loaded: false,
      expanded: {},
      addEditDialog: {
        active: false,
        mode: "add",
        id: null,
        name: "",
        script: "",
        intervalSeconds: 300,
        timeoutSeconds: 60
      },
      removeDialog: { active: false, toRemoveScript: {} }
    }
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    this.reload()
    this.timer = setInterval(() => { this.reload() }, 5000)
  },
  beforeDestroy() {
    clearInterval(this.timer)
  },
  methods: {
    reload() {
      client.get("/scripts?limit=1000&offset=0").then((response) => {
        this.scripts = response.data.hits
        this.loaded = true
      }).catch((e) => {
        console.error(e)
        this.logModal.open(e.name, e.response.data.message)
      })
    },
    duration(seconds) {
      const s = Number(seconds) || 0
      if (s >= 3600 && s % 3600 === 0) return (s / 3600) + 'h'
      if (s >= 60 && s % 60 === 0) return (s / 60) + 'min'
      if (s >= 60) return Math.floor(s / 60) + 'min ' + (s % 60) + 's'
      return s + 's'
    },
    preview(script) {
      const lines = script.script.split("\n")
      if (this.expanded[script.id] || lines.length <= PREVIEW_LINES) return script.script
      return lines.slice(0, PREVIEW_LINES).join("\n") + "\n… (" + (lines.length - PREVIEW_LINES) + " more lines)"
    },
    toggle(id) {
      this.$set(this.expanded, id, !this.expanded[id])
    },
    addScript() {
      Object.assign(this.addEditDialog, {
        mode: "add", id: null, name: "", script: "", intervalSeconds: 300, timeoutSeconds: 60, active: true
      })
    },
    editScript(script) {
      Object.assign(this.addEditDialog, {
        mode: "edit",
        id: script.id,
        name: script.name,
        script: script.script,
        intervalSeconds: script.interval_seconds,
        timeoutSeconds: script.timeout_seconds,
        active: true
      })
    },
    removeScript(script) {
      this.removeDialog.toRemoveScript = script
      this.removeDialog.active = true
    },
    confirmRemoveScript() {
      client.delete("/script/" + this.removeDialog.toRemoveScript.id)
        .then((response) => {
          this.removeDialog.active = false
          this.logModal.open("Success", response.data.message)
          this.reload()
        }).catch((e) => {
          console.error(e)
          this.removeDialog.active = false
          this.logModal.open(e.name, e.response.data.message)
          this.reload()
        })
    },
    addOrEdit() {
      const body = {
        name: this.addEditDialog.name,
        script: this.addEditDialog.script,
        interval_seconds: this.addEditDialog.intervalSeconds,
        timeout_seconds: this.addEditDialog.timeoutSeconds
      }
      const request = this.addEditDialog.mode === "add"
        ? client.post("/script", body)
        : client.put("/script/" + this.addEditDialog.id, body)

      request.then((response) => {
        this.addEditDialog.active = false
        this.logModal.open("Success", response.data.message || "Script created")
        this.reload()
      }).catch((e) => {
        // keeps the dialog open so a validation error does not lose the script being written
        console.error(e)
        this.logModal.open(e.name, e.response.data.message)
      })
    }
  }
}
</script>

<style @scoped lang="scss">
#plus-button {
  height: 50px;
  width: 50px;
  border-radius: 50%;
  background-color: $secondary-color;
}

#plus-button i {
  color: white
}

.scripts-help {
  margin-bottom: 20px;
  padding: 12px 16px;
  font-size: 0.9rem;
  background-color: $primary-color !important;
}

.script-card {
  margin-bottom: 20px;
  background-color: $primary-color !important;
}

.script-card-title {
  background-color: $title-color;
}

.script-schedule {
  font-size: 0.85rem;
  opacity: 0.8;
  margin-right: 8px;
}

.script-code {
  font-family: monospace;
  font-size: 0.85rem;
  white-space: pre-wrap;
  word-break: break-all;
  background: rgba(0, 0, 0, 0.25);
  padding: 8px;
  border-radius: 4px;
  margin-bottom: 8px;
  cursor: pointer;
}

.script-editor textarea {
  font-family: monospace !important;
  font-size: 0.85rem;
}
</style>
