// Same name always gets the same hue, so a server/service has the same color
// in the logs tags and in the metrics charts. FNV-1a spread by the golden angle,
// so names that differ in one character (server-1, server-2) get distant hues.
export function hue(name) {
  let h = 0x811c9dc5
  for (const c of String(name)) h = Math.imul(h ^ c.charCodeAt(0), 0x01000193) >>> 0
  return Math.round((h * 137.508) % 360)
}

export function lineColor(name, alpha = 1) {
  return `hsla(${hue(name)}, 70%, 45%, ${alpha})`
}

// Distinct colors for charts, assigned by position so the visible series never clash.
const PALETTE = ['#1f77b4', '#d62728', '#2ca02c', '#ff7f0e', '#9467bd', '#8c564b', '#e377c2', '#17becf', '#bcbd22', '#7f7f7f']

export function paletteColor(index, alpha = 1) {
  const hex = PALETTE[index % PALETTE.length]
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}
