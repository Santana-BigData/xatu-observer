<template>
  <v-row justify="center" align="center">
    <v-col cols="12" sm="8" md="6">
      <v-card class="service-card" v-for="service in services" :key="service.id" elevation="2" outlined tile>
        <v-card-title class="service-card-title">
          <span>{{ service.id }} -&ensp;</span>
          <v-btn @click="() => editService(service)">{{ service.name }}</v-btn>
          <v-btn icon @click="() => removeService(service)">
            <v-icon>mdi-close</v-icon>
          </v-btn>
        </v-card-title>
        <v-card-text>
          <strong>pidFile</strong>: {{ service.pid_file }} <br />
          <strong>logFileDirectory</strong>: {{ service.log_file_directory }}<br />
          <strong>logFileRegex</strong>: {{ service.log_file_regex }}<br />
          <ServerStatus :status="service.status" :servers="service.servers" />
        </v-card-text>
      </v-card>

      <button @click="addService" id="plus-button">
        <v-icon>mdi-plus</v-icon>
      </button>
    </v-col>
    <v-dialog v-model="addEditDialog.active" max-width="600">
      <v-card>
        <v-card-title class="text-h5">
          {{ addEditDialog.mode === "add" ? "Add a new service" : "Edit service" }}
        </v-card-title>
        <br />
        <v-card-text>
          <v-text-field placeholder="name of the service" label='name' v-model="addEditDialog.name" />
          <v-text-field placeholder="log files directory" label='logFileDirectory'
            v-model="addEditDialog.logFileDirectory" />
          <v-text-field placeholder="regex to match the log files" label='logFileRegex'
            v-model="addEditDialog.logFileRegex" />
          <v-text-field placeholder="file containing the pid of the process" label='pidFile'
            v-model="addEditDialog.pidFile" />
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
          Remove service
        </v-card-title>
        <br />
        <v-card-text>
          Are you sure you want to remove the service '{{ removeDialog.toRemoveService.name }}'
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="confirmRemoveService">
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

export default {
  name: 'ServicesPage',
  data() {
    return {
      services: [],
      addEditDialog: {
        active: false,
        mode: "add",
        id: null,
        name: "",
        logFileDirectory: "",
        logFileRegex: "",
        pidFile: ""
      },
      removeDialog: { active: false, toRemoveService: {} }
    }
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    this.reload()
    const that = this
    setInterval(() => { that.reload() }, 5000)
  },
  methods: {
    reload() {
      const that = this

      client.get("/services?limit=100&offset=0").then((response) => {
        that.services = response.data.hits
      }).catch((e) => {
        console.error(e)
        const message = e.response.data.message
        that.logModal.open(e.name, message)
      })
    },
    addService() {
      this.addEditDialog.mode = "add"
      this.addEditDialog.active = true

      this.addEditDialog.name = ""
      this.addEditDialog.logFileDirectory = ""
      this.addEditDialog.logFileRegex = ""
      this.addEditDialog.pidFile = ""
    },
    removeService(service) {
      this.removeDialog.toRemoveService = service
      this.removeDialog.active = true
    },
    confirmRemoveService() {
      const that = this

      client.delete("/service/" + this.removeDialog.toRemoveService.id)
        .then((response) => {
          console.log(response)
          const message = response.data.message
          that.removeDialog.active = false
          that.logModal.open("Success", message)
          that.reload()
        }).catch((e) => {
          console.error(e)
          that.removeDialog.active = false
          const message = e.response.data.message
          that.logModal.open(e.name, message)
          that.reload()
        })
    },
    editService(service) {
      this.addEditDialog.mode = 'edit'
      this.addEditDialog.id = service.id
      this.addEditDialog.name = service.name
      this.addEditDialog.logFileDirectory = service.log_file_directory
      this.addEditDialog.logFileRegex = service.log_file_regex
      this.addEditDialog.pidFile = service.pid_file

      this.addEditDialog.active = true
    },
    addOrEdit() {
      const that = this

      if (this.addEditDialog.mode === "add") {
        client.post("/service", {
          name: this.addEditDialog.name,
          log_file_directory: this.addEditDialog.logFileDirectory,
          log_file_regex: this.addEditDialog.logFileRegex,
          pid_file: this.addEditDialog.pidFile
        }).then((response) => {
          console.log(response)
          const message = response.data.message
          that.addEditDialog.active = false
          that.logModal.open("Success", message)
          that.reload()
        }).catch((e) => {
          console.error(e)
          that.addEditDialog.active = false
          const message = e.response.data.message
          that.logModal.open(e.name, message)
          that.reload()
        })
      }

      if (this.addEditDialog.mode === 'edit') {
        client.put("/service/" + this.addEditDialog.id, {
          name: this.addEditDialog.name,
          log_file_directory: this.addEditDialog.logFileDirectory,
          log_file_regex: this.addEditDialog.logFileRegex,
          pid_file: this.addEditDialog.pidFile
        }).then((response) => {
          console.log(response)
          const message = response.data.message
          that.addEditDialog.active = false
          that.logModal.open("Success", message)
          that.reload()
        }).catch((e) => {
          console.error(e)
          that.addEditDialog.active = false
          const message = e.response.data.message
          that.logModal.open(e.name, message)
          that.reload()
        })
      }
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

.service-card {
  margin-bottom: 20px;
  background-color: $primary-color !important;
}

.service-card-title {
  background-color: $title-color;
}
</style>