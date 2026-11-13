const fs = require('fs')
const p = 'seatunnel-web-ui/src/pages/bi/index.less'
let s = fs.readFileSync(p, 'utf8')
s = s.replace(
  /--font-cn:[^;]+;/,
  "--font-cn: 'Microsoft YaHei', '微软雅黑', 'PingFang SC', sans-serif;",
)
s = s.replace(
  /--font-num:[^;]+;/,
  "--font-num: 'DS-Digital', 'Microsoft YaHei', '微软雅黑', sans-serif;",
)
s = s.replace(
  /^\/\*[\s\S]*?========================================================== \*\//m,
  `/* ==========================================================
   HJJ 数据引接大屏 — 视觉规范版
   依据《数据可视化大屏设计笔记》：画布 #050E20 / 面板 #0D203C / 主数据 #71E4FF
   面板节奏借鉴 vue3 AircasPanel；中文微软雅黑 · 数字 DS-Digital · 1080P 满屏
   ========================================================== */`,
)
fs.writeFileSync(p, s, { encoding: 'utf8' })
console.log(fs.readFileSync(p, 'utf8').slice(0, 280))
