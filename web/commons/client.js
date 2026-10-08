const axios = require('axios')

let token = ''
let host = ''

if (process.browser) {
    token = localStorage.getItem('token')

    const getUrl = window.location
    host = getUrl.protocol + '//' + getUrl.host + '/api'
    if(process.env.NODE_ENV === 'development') {
      host = 'http://localhost:8089/api'
    }
}

const client = axios.create({
    baseURL: host,
    headers: { Authorization: token }
})

// back to the page the user was trying to open, after signing in
function loginUrl(expired = false) {
    const getUrl = window.location
    const next = getUrl.pathname + getUrl.search + getUrl.hash
    const params = new URLSearchParams()
    if (expired) params.set('expired', '1')
    if (next && next !== '/') params.set('next', next)
    const query = params.toString()
    return getUrl.protocol + '//' + getUrl.host + '/login' + (query ? '?' + query : '')
}

if (process.browser) {
    if (!token) {
        if (!window.location.href.includes('login')) {
            window.location.href = loginUrl()
        }
    } else {
        client.user = axios.get(
            host + '/user',
            { headers: { Authorization: token } }).then((response) => {
                console.log('Response from /user: ', response)
                if (window.location.href.includes('login')) {
                    // already signed in: go to the requested page (only a path of this site)
                    const next = new URLSearchParams(window.location.search).get('next')
                    window.location.href = next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
                }

                client.token = token
                return response.data
            }).catch((e) => {
                console.warn(e)
                // expired session: drop the token so the login page does not bounce back.
                // Other errors (network, 5xx) keep it: the user is not logged out by an outage.
                const status = e.response && e.response.status
                const expired = status === 401 || status === 403
                if (expired) localStorage.removeItem('token')
                if (!window.location.href.includes('login')) {
                    window.location.href = loginUrl(expired)
                }
            })
    }
}

export default client
