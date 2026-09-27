// Formatting shared by the monitoring pages.

export function bytes(v) {
  if (v === null || v === undefined || isNaN(v)) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0
  let n = Number(v)
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(n < 10 && i > 0 ? 1 : 0)} ${units[i]}`
}

export function rate(v) {
  return v === null || v === undefined ? '-' : `${bytes(v)}/s`
}

export function percent(v) {
  if (v === null || v === undefined || isNaN(v)) return '-'
  return `${Number(v).toFixed(v < 10 ? 1 : 0)}%`
}

export function duration(ms) {
  if (ms === null || ms === undefined || ms < 0) return '-'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}min`
  const h = Math.floor(m / 60)
  if (h < 48) return `${h}h ${m % 60}min`
  return `${Math.floor(h / 24)}d ${h % 24}h`
}

export function dateTime(ms) {
  if (!ms) return '-'
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getDate())}/${pad(d.getMonth() + 1)} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}
