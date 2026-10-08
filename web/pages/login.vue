<template>
  <div id="login-page">
    <v-card class="login-shell" elevation="10">
      <!-- brand panel, hidden on small screens -->
      <section class="login-side">
        <div class="side-brand">
          <img src="~/assets/xatu.png" alt="" class="side-logo" />
          <span>Xatu Observer</span>
        </div>
        <h2>Everything running on the cluster, in one place.</h2>
        <ul>
          <li><v-icon small color="white">mdi-docker</v-icon>CPU, memory, network and disk of every container</li>
          <li><v-icon small color="white">mdi-server-network</v-icon>Server metrics, services and APIs per server</li>
          <li><v-icon small color="white">mdi-text-search</v-icon>Logs of services and containers, with search</li>
          <li><v-icon small color="white">mdi-bell-ring-outline</v-icon>Telegram alerts when something breaks</li>
        </ul>
        <a class="side-docs" href="/login?next=%2Fapi-docs">
          <v-icon small color="white">mdi-api</v-icon>Integrating a system? Use an API token, see the API docs
        </a>
      </section>

      <section class="login-main">
        <div class="login-brand">
          <img src="~/assets/xatu.png" alt="Xatu" class="login-logo" />
          <div>
            <h1>Sign in</h1>
            <p>to Xatu Observer</p>
          </div>
        </div>

        <v-alert v-if="expired && !error" type="info" dense text class="login-alert">
          Your session expired. Sign in again to continue.
        </v-alert>

        <v-form ref="form" @submit.prevent="submit">
          <v-text-field
            v-model="email"
            label="Email"
            type="email"
            prepend-inner-icon="mdi-email-outline"
            autocomplete="username"
            :rules="[(v) => !!(v && v.trim()) || 'Email is required']"
            :disabled="loading"
            outlined
            color="green darken-2"
            :autofocus="!email"
            @input="error = ''"
          ></v-text-field>

          <v-text-field
            ref="password"
            v-model="password"
            label="Password"
            :type="showPassword ? 'text' : 'password'"
            prepend-inner-icon="mdi-lock-outline"
            :append-icon="showPassword ? 'mdi-eye-off' : 'mdi-eye'"
            autocomplete="current-password"
            :rules="[(v) => !!v || 'Password is required']"
            :disabled="loading"
            :hint="capsLock ? 'Caps Lock is on' : ''"
            persistent-hint
            :class="{ 'caps-lock': capsLock }"
            outlined
            color="green darken-2"
            :autofocus="!!email"
            @click:append="showPassword = !showPassword"
            @input="error = ''"
            @keydown="checkCapsLock"
            @keyup="checkCapsLock"
          ></v-text-field>

          <div class="login-options">
            <v-checkbox v-model="remember" label="Remember email" color="green darken-2" dense hide-details class="mt-0 pt-0"></v-checkbox>
          </div>

          <v-alert v-if="error" type="error" dense text class="login-alert">{{ error }}</v-alert>

          <v-btn type="submit" block large color="green darken-1" dark :loading="loading" class="login-button">
            Sign in
          </v-btn>
        </v-form>

        <div class="login-status" :title="statusTitle">
          <span :class="['status-dot', status.level]"></span>
          <span>{{ status.text }}</span>
        </div>
      </section>
    </v-card>
  </div>
</template>

<script>
import axios from 'axios'
import client from '../commons/client'

const REMEMBER_KEY = 'xatu-login-email'
const COMPONENTS = { redis: 'Redis', mysql: 'MySQL', docker: 'Docker', elasticsearch: 'Elasticsearch', cassandra: 'Cassandra' }

export default {
  name: 'LoginPage',
  layout: 'empty',
  data() {
    return {
      email: '',
      password: '',
      showPassword: false,
      remember: false,
      capsLock: false,
      loading: false,
      error: '',
      expired: false,
      status: { level: 'unknown', text: 'Checking system status…' },
      health: null,
    }
  },
  computed: {
    statusTitle() {
      if (!this.health) return ''
      return Object.entries(COMPONENTS)
        .filter(([key]) => key in this.health)
        .map(([key, label]) => `${label}: ${this.health[key]}`)
        .join('\n')
    },
  },
  mounted() {
    const params = new URLSearchParams(window.location.search)
    this.expired = params.get('expired') === '1'
    try {
      const saved = localStorage.getItem(REMEMBER_KEY)
      if (saved) {
        this.email = saved
        this.remember = true
      }
    } catch (e) {
      // storage blocked: the page works without remembering the email
    }
    this.checkStatus()
  },
  methods: {
    checkCapsLock(e) {
      if (e.getModifierState) this.capsLock = e.getModifierState('CapsLock')
    },
    // healthcheck is public; a failing component answers 500 with the same body
    checkStatus() {
      axios
        .get(`${window.location.origin}/api/healthcheck`, { timeout: 15000, validateStatus: () => true })
        .then((response) => {
          const health = response.data && typeof response.data === 'object' ? response.data : null
          this.health = health
          if (!health) {
            this.status = { level: 'down', text: 'System status unavailable' }
            return
          }
          const failing = Object.keys(COMPONENTS).filter((k) => k in health && !['Ok', 'Inactive'].includes(health[k]))
          this.status = failing.length
            ? { level: 'degraded', text: `Degraded: ${failing.map((k) => COMPONENTS[k]).join(', ')}` }
            : { level: 'ok', text: 'All systems operational' }
        })
        .catch(() => (this.status = { level: 'down', text: 'Server unreachable' }))
    },
    // only a path of this site, never another origin (open redirect)
    nextPage() {
      const next = new URLSearchParams(window.location.search).get('next')
      return next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
    },
    saveEmail(email) {
      try {
        if (this.remember) localStorage.setItem(REMEMBER_KEY, email)
        else localStorage.removeItem(REMEMBER_KEY)
      } catch (e) {
        // storage blocked
      }
    },
    submit() {
      if (!this.$refs.form.validate()) return
      this.loading = true
      this.error = ''
      const email = this.email.trim()

      client
        .post('/login', { email, password: this.password })
        .then((response) => {
          this.saveEmail(email)
          localStorage.setItem('token', response.data.token)
          // full reload: the api client reads the token when the app starts
          window.location.href = this.nextPage()
        })
        .catch((e) => {
          console.error(e)
          const status = e.response && e.response.status
          // user not found (404) and wrong password (403) look the same on purpose
          if (status === 403 || status === 404) this.error = 'Incorrect email or password.'
          else if (!e.response) this.error = 'Could not reach the server. Check your connection and try again.'
          else this.error = `The server could not sign you in (error ${status}). Try again in a moment.`
          this.password = ''
          this.loading = false
          this.$nextTick(() => {
            // the cleared password is not a validation error, only the message above
            this.$refs.form.resetValidation()
            this.$refs.password.focus()
          })
        })
    },
  },
}
</script>

<style lang="scss">
#login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background-color: $primary-color;
  background-image: url('~assets/xatu.png');
  background-size: 50px 50px;
  // vuetify's reset sets no-repeat on every element
  background-repeat: repeat !important;
  background-blend-mode: soft-light;
}

.login-shell {
  display: flex;
  width: 100%;
  max-width: 860px;
  overflow: hidden;
  border-radius: 12px !important;
}

.login-side {
  flex: 1 1 50%;
  padding: 40px 36px;
  color: white;
  background: linear-gradient(160deg, #2f6b45 0%, #1f4a30 100%);
  display: flex;
  flex-direction: column;

  .side-brand {
    display: flex;
    align-items: center;
    gap: 10px;
    font-weight: 600;
    font-size: 16px;
    opacity: 0.95;
  }

  .side-logo {
    width: 36px;
    height: 36px;
    border-radius: 8px;
    background: $primary-color;
    padding: 2px;
  }

  h2 {
    margin: 36px 0 24px;
    font-size: 24px;
    line-height: 1.3;
    font-weight: 600;
  }

  .side-docs {
    margin-top: auto;
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    color: white !important;
    opacity: 0.8;
    text-decoration: none;

    &:hover {
      opacity: 1;
      text-decoration: underline;
    }
  }

  ul {
    list-style: none;
    padding: 0;
    margin: 0;

    li {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      margin-bottom: 14px;
      font-size: 14px;
      line-height: 1.4;
      opacity: 0.92;

      .v-icon {
        margin-top: 1px;
      }
    }
  }
}

.login-main {
  flex: 1 1 50%;
  padding: 40px 36px 24px;
  background: white;
  display: flex;
  flex-direction: column;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;

  .login-logo {
    width: 48px;
    height: 48px;
    border-radius: 10px;
    background: $primary-color;
    padding: 3px;
  }

  h1 {
    font-size: 24px;
    font-weight: 600;
    color: #1f3d2a;
    line-height: 1.2;
  }

  p {
    margin: 2px 0 0;
    font-size: 13px;
    color: #5a7563;
  }
}

.caps-lock .v-messages__message {
  color: #e65100;
  font-weight: 600;
}

.login-options {
  margin: -4px 0 14px;
}

.login-alert {
  margin-bottom: 14px !important;
}

.login-button {
  letter-spacing: 0.5px;
}

.login-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: auto;
  padding-top: 24px;
  font-size: 12px;
  color: #6b7f72;
  white-space: pre-line;
  cursor: default;

  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #bbb;

    &.ok {
      background: #43a047;
    }

    &.degraded {
      background: #fb8c00;
    }

    &.down {
      background: #e53935;
    }
  }
}

@media (max-width: 720px) {
  .login-side {
    display: none;
  }

  .login-shell {
    max-width: 420px;
  }

  .login-main {
    padding: 32px 24px 20px;
  }
}
</style>
