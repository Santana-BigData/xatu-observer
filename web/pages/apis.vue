<template>
  <v-row
    justify="center"
    align="center"
  >
    <v-col
      cols="12"
      sm="8"
      md="6"
    >
      <v-card
        class="api-card"
        v-for="api in apis"
        :key="api.id"
        elevation="2"
        outlined
        tile
      >
        <v-card-title class="api-card-title">
          <span>{{ api.id }} - &ensp;</span>
          <v-btn @click="() => editAPI(api)">{{ api.name }}</v-btn>
          <v-btn
            icon
            @click="() => removeAPI(api)"
          >
            <v-icon>mdi-close</v-icon>
          </v-btn>
        </v-card-title>
        <v-card-text>
          <strong>Host</strong>: {{ api.host }} <br />
          <strong>Port</strong>: {{ api.port }}<br />
          <strong>HealthcheckRoute</strong>: {{ api.healthcheck_route }}<br />
          <ServerStatus :status="api.status" :servers="api.servers" />
          <br />
        </v-card-text>
      </v-card>

      <button
        @click="addAPI"
        id="plus-button"
      >
        <v-icon>mdi-plus</v-icon>
      </button>
    </v-col>
    <v-dialog
      v-model="addEditDialog.active"
      max-width="600"
    >
      <v-card>
        <v-card-title class="text-h5">
          {{ addEditDialog.mode === "add" ? "Add a new API" : "Edit an API" }}
        </v-card-title>
        <br />
        <v-card-text>
          <v-text-field
            placeholder="name of the API"
            label='name'
            v-model="addEditDialog.name"
          />
          <v-text-field
            placeholder="host of the API"
            label='host'
            v-model="addEditDialog.host"
          />
          <v-text-field
            placeholder="PORT"
            label='port'
            v-model="addEditDialog.port"
          />
          <v-text-field
            placeholder="route of the healthcheck"
            label='healthcheckRoute'
            v-model="addEditDialog.healthcheckRoute"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn
            color="green darken-1"
            text
            @click="addOrEdit"
          >
            OK
          </v-btn>
          <v-btn
            color="green darken-1"
            text
            @click="addEditDialog.active = false"
          >
            Cancel
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog
      v-model="removeDialog.active"
      max-width="600"
    >
      <v-card>
        <v-card-title class="text-h5">
          Remove API
        </v-card-title>
        <br />
        <v-card-text>
          Are you sure you want to remove the API '{{ removeDialog.toRemoveAPI.name }}'
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn
            color="green darken-1"
            text
            @click="confirmRemoveAPI"
          >
            OK
          </v-btn>
          <v-btn
            color="green darken-1"
            text
            @click="removeDialog.active = false"
          >
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
  name: 'APIsPage',
  data() {
    return {
      apis: [],
      addEditDialog: {
        active: false,
        mode: 'add',
        id: null,
        name: '',
        host: '',
        port: null,
        healthcheckRoute: '',
      },
      removeDialog: { active: false, toRemoveAPI: {} },
    }
  },
  mounted() {
    this.logModal = document.getElementById('log-modal')
    this.reload()
    const that = this
    setInterval(() => {
      that.reload()
    }, 5000)
  },
  methods: {
    reload() {
      const that = this

      client
        .get('/apis?limit=100&offset=0')
        .then((response) => {
          that.apis = response.data.hits
        })
        .catch((e) => {
          console.error(e)
          const message = e.response.data.message
          that.logModal.open(e.name, message)
        })
    },
    addAPI() {
      this.addEditDialog.mode = 'add'
      this.addEditDialog.active = true

      this.addEditDialog.name = ''
      this.addEditDialog.host = ''
      this.addEditDialog.port = null
      this.addEditDialog.healthcheckRoute = ''
    },
    removeAPI(api) {
      this.removeDialog.toRemoveAPI = api
      this.removeDialog.active = true
    },
    confirmRemoveAPI() {
      const that = this

      client
        .delete('/api/' + this.removeDialog.toRemoveAPI.id)
        .then((response) => {
          console.log(response)
          const message = response.data.message
          that.removeDialog.active = false
          that.logModal.open('Success', message)
          that.reload()
        })
        .catch((e) => {
          console.error(e)
          that.removeDialog.active = false
          const message = e.response.data.message
          that.logModal.open(e.name, message)
          that.reload()
        })
    },
    editAPI(api) {
      this.addEditDialog.mode = 'edit'
      this.addEditDialog.id = api.id
      this.addEditDialog.name = api.name
      this.addEditDialog.host = api.host
      this.addEditDialog.port = api.port
      this.addEditDialog.healthcheckRoute = api.healthcheck_route

      this.addEditDialog.active = true
    },
    addOrEdit() {
      const that = this

      if (this.addEditDialog.mode === 'add') {
        client
          .post('/api', {
            name: this.addEditDialog.name,
            host: this.addEditDialog.host,
            port: this.addEditDialog.port,
            healthcheck_route: this.addEditDialog.healthcheckRoute,
          })
          .then((response) => {
            console.log(response)
            const message = response.data.message
            that.addEditDialog.active = false
            that.logModal.open('Success', message)
            that.reload()
          })
          .catch((e) => {
            console.error(e)
            that.addEditDialog.active = false
            const message = e.response.data.message
            that.logModal.open(e.name, message)
            that.reload()
          })
      }

      if (this.addEditDialog.mode === 'edit') {
        client
          .put('/api/' + this.addEditDialog.id, {
            name: this.addEditDialog.name,
            host: this.addEditDialog.host,
            port: this.addEditDialog.port,
            healthcheck_route: this.addEditDialog.healthcheckRoute,
          })
          .then((response) => {
            console.log(response)
            const message = response.data.message
            that.addEditDialog.active = false
            that.logModal.open('Success', message)
            that.reload()
          })
          .catch((e) => {
            console.error(e)
            that.addEditDialog.active = false
            const message = e.response.data.message
            that.logModal.open(e.name, message)
            that.reload()
          })
      }
    },
  },
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
  color: white;
}

.api-card {
  margin-bottom: 20px;
  background-color: $primary-color !important;
}

.api-card-title {
  background-color: $title-color;
}
</style>
