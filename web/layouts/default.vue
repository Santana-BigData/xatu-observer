<template>
  <v-app id="main-app">
    <v-row>
      <v-col sm="4" md="2">
        <v-navigation-drawer absolut bottom permanent id="navigation-drawer">
          <v-list-item id="top-navigation-drawer">
            <v-list-item-content>
              <v-list-item-title class="text-h6">
                Xatu Observer
              </v-list-item-title>
              <v-list-item-subtitle>
                services, apis and docker containers
              </v-list-item-subtitle>
            </v-list-item-content>
          </v-list-item>

          <v-divider></v-divider>

          <v-list dense nav>
            <v-list-item><a href="/">Index</a></v-list-item>
            <v-list-item>
              <v-icon>mdi-api</v-icon>&nbsp;<a href="/apis">APIs</a>
            </v-list-item>
            <v-list-item>
              <v-icon>mdi-server</v-icon>&nbsp;<a href="/services">Services</a>
            </v-list-item>
            <v-list-item>
              <v-icon>mdi-docker</v-icon>&nbsp;<a href="/containers">Containers</a>
            </v-list-item>
            <v-list-item>
              <v-icon>mdi-math-log</v-icon>&nbsp;<a href="/logs">Logs</a>
            </v-list-item>
            <v-list-item>
              <v-icon>mdi-chart-line</v-icon>&nbsp;<a href="/metrics">Metrics</a>
            </v-list-item>
            <v-list-item>
              <v-icon>mdi-logout</v-icon>&nbsp;<button @click="logout">Logout</button>
            </v-list-item>
          </v-list>
        </v-navigation-drawer>
      </v-col>
      <v-col md="10" sm="8">"
        <div id="container">
          <nuxt />
        </div>
      </v-col>
    </v-row>
    <v-dialog v-model="dialog.active" max-width="600" id="log-modal">
      <v-card>
        <v-card-title class="text-h5">
          {{ dialog.title }}
        </v-card-title>
        <v-card-text>
          {{ dialog.body }}
        </v-card-text>
        <v-card-actions>
          <v-spacer></v-spacer>
          <v-btn color="green darken-1" text @click="dialog.active = false">
            OK
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-app>
</template>

<script>
export default {
  name: 'DefaultLayout',
  data() {
    return {
      severity: 'debug',
      severityColorCSS: 'background-color: gray',
      dialog: {
        active: false,
        title: "",
        body: ""
      }
    }
  },
  mounted() {
    const modal = document.getElementById("log-modal")

    modal.open = function (title, text) {
      this.dialog.title = title
      this.dialog.body = text
      this.dialog.active = true
    }

    modal.close = function () {
      this.dialog.active = false
    }

    modal.close = modal.close.bind(this)
    modal.open = modal.open.bind(this)

  },
  methods: {
    logout() {
      localStorage.removeItem('token')
      const getUrl = window.location
      window.location.href = getUrl.protocol + '//' + getUrl.host + '/login'
    }
  }
}
</script>

<style @scoped lang="scss">
#main-app {
  background-image: url("~assets/xatu.png");
  background-repeat: repeat-x repeat-y;
  background-size: 50px 50px;

  height: 100%;
}

#navigation-drawer {
  background-color: $primary-color;
}

#top-navigation-drawer {
  background-color: $title-color;
}

#container {
  margin-top: 30px;
}
</style>