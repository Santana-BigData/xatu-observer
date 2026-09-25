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
        class="container-card"
        v-for="container in containers"
        :key="container.id"
        elevation="2"
        outlined
        tile
      >
        <v-card-title class="container-card-title">
          <span>{{ container.id }} - {{ container.name }}</span>
          <v-btn
            icon
            @click="() => removeContainer(container)"
          >
            <v-icon>mdi-close</v-icon>
          </v-btn>
        </v-card-title>
        <v-card-text>
          <div v-if="container.info">
            <strong>ImageName</strong>: {{ container.info.image_name }} <br />
            <strong>ContainerId</strong>: {{ container.info.container_id }} <br />
          </div>
          <ServerStatus :status="container.status" :servers="container.servers" />
        </v-card-text>
      </v-card>

      <button
        @click="addContainer"
        id="plus-button"
      >
        <v-icon>mdi-plus</v-icon>
      </button>
    </v-col>
    <v-dialog
      v-model="addDialog.active"
      max-width="600"
    >
      <v-card>
        <v-card-title class="text-h5">
          Monitor a new container
        </v-card-title>
        <br />
        <v-card-text>
          <v-text-field
            placeholder="name of the container"
            label='name'
            v-model="addDialog.name"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn
            color="green darken-1"
            text
            @click="add"
          >
            OK
          </v-btn>
          <v-btn
            color="green darken-1"
            text
            @click="addDialog.active = false"
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
          Stop monitoring container
        </v-card-title>
        <br />
        <v-card-text>
          Are you sure you want to remove the container '{{ removeDialog.name }}'
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn
            color="green darken-1"
            text
            @click="confirmRemoveContainer"
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
  name: 'ContainersPage',
  data() {
    return {
      containers: [],
      addDialog: {
        active: false,
        id: null,
        name: '',
      },
      removeDialog: { active: false, name: null, id: null },
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
        .get('/containers?limit=100&offset=0')
        .then((response) => {
          that.containers = response.data.hits
          console.log('Response from /containers ->', response)
        })
        .catch((e) => {
          console.error(e)
          const message = e.response.data.message
          that.logModal.open(e.name, message)
        })
    },
    addContainer() {
      this.addDialog.active = true
      this.addDialog.name = ''
    },
    removeContainer(container) {
      this.removeDialog.name = container.name
      this.removeDialog.id = container.id
      this.removeDialog.active = true
    },
    confirmRemoveContainer() {
      const that = this

      client
        .delete('/container/' + this.removeDialog.id)
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
    add() {
      const that = this

      client
        .post('/container', {
          name: this.addDialog.name,
        })
        .then((response) => {
          console.log(response)
          const message = response.data.message
          that.addDialog.active = false
          that.logModal.open('Success', message)
          that.reload()
        })
        .catch((e) => {
          console.error(e)
          that.addDialog.active = false
          const message = e.response.data.message
          that.logModal.open(e.name, message)
          that.reload()
        })
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

.container-card {
  margin-bottom: 20px;
  background-color: $primary-color !important;
  width: 100%;
}

.container-card-title {
  background-color: $title-color;
}
</style>