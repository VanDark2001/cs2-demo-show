// 展示层工具：集中处理 API 基址、地图/武器名称、头像地址和比分颜色。
export const API = import.meta.env.VITE_API_BASE_URL || '/api'

// 此处只负责显示名称；统计聚合和武器归一化必须在后端完成。
const mapNames = { de_inferno: '炼狱小镇', de_mirage: '荒漠迷城', de_ancient: '远古遗迹', de_dust2: '炙热沙城Ⅱ', de_nuke: '核子危机', de_anubis: '阿努比斯', de_vertigo: '殒命大厦', de_overpass: '死亡游乐园', de_train: '列车停放站', de_cache: '死城之谜' }
const weaponNames = { inferno: '燃烧弹', m4a1silencer: 'm4a1s', m4a1slencer: 'm4a1s', m4a1_silencer: 'm4a1s', m4a1_silencer_vip: 'm4a1s', fiveseven: 'fn57', hegrenade: '手雷' }

export const mapName = map => mapNames[map] || map?.replace('de_', '') || '未知地图'
export const weaponName = weapon => weaponNames[weapon?.toLowerCase()] || weapon || 'unknown'
export const avatarUrl = steamid => `${API}/avatars/${steamid}`
export const hideBrokenAvatar = event => { event.target.style.display = 'none' }
export const scoreValues = score => (String(score || '').match(/\d+/g) || []).map(Number)
// 当前赛制先到 13 分；只有达到胜场且为较高分的一侧显示胜方颜色。
export const scoreTone = (score, index) => {
  const values = scoreValues(score)
  if (values.length !== 2) return ''
  return values[index] >= 13 && values[index] === Math.max(...values) ? 'score-win' : 'score-loss'
}
