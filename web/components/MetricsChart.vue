<template>
  <v-card class="metrics-chart" elevation="1">
    <div class="metrics-chart-title">{{ title }}</div>
    <div class="metrics-chart-canvas">
      <canvas ref="canvas"></canvas>
    </div>
  </v-card>
</template>

<script>
// Line chart with time on the x axis (epoch millis, linear scale, so no date adapter).
// datasets: [{ label, color, data: [{ x, y }], dashed }]
export default {
  name: 'MetricsChart',
  props: {
    title: { type: String, required: true },
    datasets: { type: Array, required: true },
    formatY: { type: Function, default: (v) => v },
    yMax: { type: Number, default: undefined },
    from: { type: Number, required: true },
    to: { type: Number, required: true },
  },
  data() {
    return { chart: null }
  },
  async mounted() {
    // chart.js touches window, so it is loaded only in the browser
    const { default: Chart } = await import('chart.js/auto')
    this.chart = new Chart(this.$refs.canvas, {
      type: 'line',
      data: { datasets: this.chartDatasets() },
      options: this.options(),
    })
  },
  beforeDestroy() {
    if (this.chart) this.chart.destroy()
  },
  watch: {
    datasets() {
      this.refresh()
    },
    from() {
      this.refresh()
    },
  },
  methods: {
    refresh() {
      if (!this.chart) return
      this.chart.data.datasets = this.chartDatasets()
      this.chart.options = this.options()
      this.chart.update('none')
    },
    chartDatasets() {
      return this.datasets.map((d) => ({
        label: d.label,
        data: d.data,
        borderColor: d.color,
        backgroundColor: d.color,
        borderWidth: 1.5,
        borderDash: d.dashed ? [5, 4] : [],
        pointRadius: 0,
        pointHitRadius: 6,
        tension: 0.2,
        spanGaps: false,
      }))
    },
    formatTime(ms, withDate) {
      const d = new Date(ms)
      const pad = (n) => String(n).padStart(2, '0')
      const time = `${pad(d.getHours())}:${pad(d.getMinutes())}`
      return withDate ? `${pad(d.getDate())}/${pad(d.getMonth() + 1)} ${time}` : time
    },
    options() {
      const that = this
      const longRange = this.to - this.from > 24 * 3600 * 1000
      return {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { position: 'bottom', labels: { boxWidth: 12, boxHeight: 2, font: { size: 11 } } },
          tooltip: {
            callbacks: {
              title: (items) => (items.length ? that.formatTime(items[0].parsed.x, true) : ''),
              label: (item) => `${item.dataset.label}: ${that.formatY(item.parsed.y)}`,
            },
          },
        },
        scales: {
          x: {
            type: 'linear',
            min: this.from,
            max: this.to,
            ticks: { maxTicksLimit: 8, callback: (v) => that.formatTime(v, longRange), font: { size: 11 } },
            grid: { color: 'rgba(0, 0, 0, 0.05)' },
          },
          y: {
            beginAtZero: true,
            max: this.yMax,
            ticks: { maxTicksLimit: 6, callback: (v) => that.formatY(v), font: { size: 11 } },
            grid: { color: 'rgba(0, 0, 0, 0.06)' },
          },
        },
      }
    },
  },
}
</script>

<style lang="scss">
.metrics-chart {
  padding: 12px 12px 4px;

  .metrics-chart-title {
    font-weight: 600;
    font-size: 14px;
    color: #33563f;
    margin-bottom: 4px;
  }

  .metrics-chart-canvas {
    position: relative;
    height: 240px;
  }
}
</style>
