<template>
  <v-row justify="center">
    <v-col cols="12" lg="10" xl="8">
      <v-card elevation="2" class="api-docs">
        <div class="api-docs-actions">
          <a href="/tokens"><v-icon small>mdi-key-variant</v-icon> create a token</a>
        </div>
        <!-- docs/API.md of the repository: the same file shown on github -->
        <article class="markdown" v-html="html"></article>
      </v-card>
    </v-col>
  </v-row>
</template>

<script>
import { marked } from 'marked'
import doc from '../../docs/API.md'

export default {
  name: 'ApiDocsPage',
  computed: {
    html() {
      // the document shows the production url; show the one this page is served from
      const origin = typeof window === 'undefined' ? null : window.location.origin
      const text = origin ? doc.split('https://xatu.santanabigdata.com.br').join(origin) : doc
      return marked.parse(text)
    },
  },
  mounted() {
    // v-html is rendered after the navigation, so the #anchor has to be followed by hand
    if (window.location.hash) {
      this.$nextTick(() => {
        const el = document.getElementById(decodeURIComponent(window.location.hash.slice(1)))
        if (el) el.scrollIntoView()
      })
    }
  },
}
</script>

<style lang="scss">
.api-docs {
  padding: 24px 40px 40px;
  background: white !important;

  .api-docs-actions {
    text-align: right;
    font-size: 13px;
  }
}

.markdown {
  color: #24292f;
  line-height: 1.6;
  font-size: 15px;

  h1,
  h2,
  h3 {
    color: #1f3d2a;
    scroll-margin-top: 12px;
  }

  h1 {
    font-size: 30px;
    border-bottom: 1px solid #d0d7de;
    padding-bottom: 8px;
  }

  h2 {
    font-size: 23px;
    margin-top: 32px;
    border-bottom: 1px solid #e3e8e4;
    padding-bottom: 6px;
  }

  h3 {
    font-size: 18px;
    margin-top: 24px;
  }

  p,
  ul,
  ol,
  table,
  pre,
  blockquote {
    margin: 10px 0;
  }

  code {
    font-family: 'JetBrains Mono', Consolas, monospace;
    font-size: 13px;
    background: #f1f4f2 !important;
    color: #1f3d2a !important;
    padding: 1px 5px;
    border-radius: 4px;
    box-shadow: none;
  }

  pre {
    background: #1b1f23;
    border-radius: 6px;
    padding: 14px 16px;
    overflow-x: auto;

    code {
      background: transparent !important;
      color: #e6edf3 !important;
      padding: 0;
      font-size: 12.5px;
    }
  }

  table {
    border-collapse: collapse;
    width: 100%;
    font-size: 14px;
  }

  th,
  td {
    border: 1px solid #d0d7de;
    padding: 6px 10px;
    text-align: left;
    vertical-align: top;
  }

  th {
    background: #f1f4f2;
  }

  blockquote {
    border-left: 4px solid #68b984;
    background: #f3faf5;
    padding: 8px 14px;
    color: #33563f;
  }

  hr {
    border: none;
    border-top: 1px solid #e3e8e4;
    margin: 28px 0;
  }

  a {
    color: #1a7f37;
  }
}
</style>
