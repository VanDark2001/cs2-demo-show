<!-- 比赛详情页：选择单场比赛并展示双方阵容、数据、高光、武器和残局。 -->
<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PlayerAvatar from '../components/PlayerAvatar.vue'
import MapIcon from '../components/MapIcon.vue'
import BaseChart from '../components/BaseChart.vue'
import { useDemoData } from '../composables/useDemoData'
import { mapName, scoreTone, scoreValues, weaponName } from '../lib/presentation'
import { axisStyle, tooltipStyle } from '../lib/echarts'

const route = useRoute()
const router = useRouter()
const { matches, active, analytics, loading, analyticsLoading, error, load, selectMatch } = useDemoData()
const tab = ref('overview')
const selectedMap = ref('all')
const page = ref(1)
const pageSize = 8
const tabs = [['overview', '综合数据'], ['kills', '击杀分析'], ['highlights', '高光'], ['weapons', '武器统计'], ['clutches', '残局分析']]
const mapOptions = computed(() => [...new Set(matches.value.map(match => match.map))].sort())
const filteredMatches = computed(() => selectedMap.value === 'all' ? matches.value : matches.value.filter(match => match.map === selectedMap.value))
const pageCount = computed(() => Math.max(1, Math.ceil(filteredMatches.value.length / pageSize)))
const pagedMatches = computed(() => filteredMatches.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch(pageCount, count => { page.value = Math.min(page.value, count) })
watch(() => active.value?.id, id => {
  const index = filteredMatches.value.findIndex(match => match.id === id)
  if (index >= 0) page.value = Math.floor(index / pageSize) + 1
})
const players = computed(() => active.value?.players || [])
const teamNumbers = computed(() => [...new Set(players.value.map(player => player.teamNumber).filter(value => value != null))].sort((a, b) => b - a))
const groups = computed(() => [{ name: 'A组', rows: players.value.filter(player => player.teamNumber === teamNumbers.value[0]) }, { name: 'B组', rows: players.value.filter(player => player.teamNumber === teamNumbers.value[1]) }])
const killRanking = computed(() => [...players.value].sort((a, b) => Number(b.kills || 0) - Number(a.kills || 0)))
const matchChartOption = computed(() => ({
  color: ['#35dfb7', '#4c8de8'], tooltip: { ...tooltipStyle, trigger: 'axis' },
  legend: { data: ['击杀', 'ADR'], textStyle: { color: '#899397' } },
  grid: { left: 28, right: 38, top: 44, bottom: 54, containLabel: true },
  xAxis: { ...axisStyle, type: 'category', data: players.value.map(player => player.name), axisLabel: { color: '#899397', rotate: 24 } },
  yAxis: [{ ...axisStyle, type: 'value', name: '击杀' }, { ...axisStyle, type: 'value', name: 'ADR' }],
  series: [{ name: '击杀', type: 'bar', data: players.value.map(player => player.kills), barMaxWidth: 24, itemStyle: { borderRadius: [4, 4, 0, 0] } }, { name: 'ADR', type: 'line', yAxisIndex: 1, data: players.value.map(player => player.adr), symbolSize: 7, lineStyle: { width: 2 } }]
}))

watch(() => route.params.id, async id => {
  await load(id)
  if (id) {
    await selectMatch(id)
    if (active.value && active.value.id !== Number(id)) await router.replace({ name: 'matches', params: { id: active.value.id } })
  }
  tab.value = 'overview'
}, { immediate: true })

function chooseMatch(match) {
  router.push({ name: 'matches', params: { id: match.id } })
}

function filterMatches() {
  page.value = 1
  if (!filteredMatches.value.some(match => match.id === active.value?.id) && filteredMatches.value[0]) chooseMatch(filteredMatches.value[0])
}

function openPlayer(player) {
  router.push({ name: 'player-detail', params: { steamid: player.steamid } })
}
</script>

<template>
  <div class="workspace">
    <aside class="match-sidebar paginated-sidebar"><div class="sidebar-head"><div><small>MATCH HISTORY</small><h3>比赛记录</h3></div><span>{{ filteredMatches.length }}</span></div><label class="map-filter"><span>地图筛选</span><select v-model="selectedMap" @change="filterMatches"><option value="all">全部地图</option><option v-for="map in mapOptions" :key="map" :value="map">{{ mapName(map) }}</option></select></label><div v-if="!filteredMatches.length&&!loading" class="empty-list">没有符合条件的比赛</div><div class="sidebar-matches"><button v-for="match in pagedMatches" :key="match.id" class="match-item" :class="{selected:active?.id===match.id}" @click="chooseMatch(match)"><MapIcon :map="match.map" :label="mapName(match.map)"/><span class="match-copy"><b>{{ mapName(match.map) }}</b><small>{{ match.date }}</small><em>{{ match.server||'未知服务器' }}</em></span><strong v-if="scoreValues(match.score).length===2" class="match-score"><span :class="scoreTone(match.score,0)">{{ scoreValues(match.score)[0] }}</span><i>:</i><span :class="scoreTone(match.score,1)">{{ scoreValues(match.score)[1] }}</span></strong><strong v-else>{{ match.score }}</strong></button></div><div class="sidebar-pagination" aria-label="比赛列表分页"><button :disabled="page===1" aria-label="上一页" @click="page--">‹</button><label>第 <select v-model.number="page" aria-label="选择页码"><option v-for="number in pageCount" :key="number" :value="number">{{ number }}</option></select> / {{ pageCount }} 页</label><button :disabled="page===pageCount" aria-label="下一页" @click="page++">›</button><small>每页 {{ pageSize }} 场 · 共 {{ filteredMatches.length }} 场</small></div></aside>
    <main>
      <section v-if="active" class="hero"><div><div class="eyebrow">MATCH OVERVIEW · {{ active.tickRate }} TICK</div><h1>{{ mapName(active.map) }} <strong v-if="scoreValues(active.score).length===2" class="hero-score"><span :class="scoreTone(active.score,0)">{{ scoreValues(active.score)[0] }}</span><i>:</i><span :class="scoreTone(active.score,1)">{{ scoreValues(active.score)[1] }}</span></strong></h1><p>{{ active.map }} <i>·</i> {{ active.date }} <i>·</i> {{ active.server }}</p></div></section>
      <div class="tabs"><span v-for="item in tabs" :key="item[0]" :class="{selected:tab===item[0]}" @click="tab=item[0]">{{ item[1] }}</span></div>
      <div v-if="loading" class="state">正在读取 MySQL 数据…</div><div v-else-if="error" class="state error">{{ error }}</div>
      <template v-else-if="active&&tab==='overview'"><section class="card"><div class="card-head"><h2>玩家表现</h2><span>点击玩家查看 SteamID 对应的详细数据</span></div><div class="table"><div class="tr th"><span>玩家</span><span>K / A / D</span><span>ADR</span><span>爆头率</span><span>爆头</span><span>Rating</span><span title="全回合贡献分：有效伤害、敌方击杀、助攻与本队回合胜率综合计算，上限16.0">WE ⓘ</span></div><template v-for="group in groups" :key="group.name"><div class="team-title"><strong>{{ group.name }}</strong></div><div v-for="(player,index) in group.rows" :key="player.steamid" class="tr player-link" role="link" tabindex="0" @click="openPlayer(player)" @keydown.enter="openPlayer(player)"><span class="player"><em :class="{gold:index===0}">{{ index+1 }}</em><PlayerAvatar :steamid="player.steamid" :name="player.name"/><b>{{ player.name }}</b></span><span>{{ player.kills }} / {{ player.assists }} / {{ player.deaths }}</span><span :class="{green:player.adr>=100,red:player.adr<60}">{{ player.adr }}</span><span>{{ player.hs }}%</span><span>{{ player.aim }}</span><span class="rating">{{ Number(player.rating||0).toFixed(2) }}</span><span>{{ Number(player.we || 0).toFixed(1) }}</span></div></template></div></section></template>
      <template v-else-if="active&&tab==='kills'"><section class="card match-chart"><div class="card-head"><h2>击杀与 ADR</h2><span>本场10名玩家</span></div><BaseChart :option="matchChartOption" height="330px" /></section><section class="card analysis-card"><div class="card-head"><h2>击杀分析</h2><span>正式比赛区间 · 三杀及以上计入多杀回合</span></div><div class="ranking"><div v-for="(player,index) in killRanking" :key="player.steamid" class="rank-row player-link" role="link" tabindex="0" @click="openPlayer(player)" @keydown.enter="openPlayer(player)"><strong>{{ index+1 }}</strong><PlayerAvatar :steamid="player.steamid" :name="player.name"/><b>{{ player.name }}</b><div><small>击杀</small><em>{{ player.kills }}</em></div><div><small>爆头</small><em>{{ player.aim }}</em></div><div><small>爆头率</small><em>{{ player.hs }}%</em></div><div><small>多杀回合</small><em>{{ player.multiKillRounds||0 }}</em></div></div></div></section></template>
      <section v-else-if="active&&tab==='highlights'" class="card analysis-card"><div class="card-head"><h2>多杀高光</h2><span>单回合至少完成 3 次击杀</span></div><div v-if="analyticsLoading" class="panel-empty">正在加载…</div><div v-else class="highlight-grid"><div v-for="item in analytics.highlights" :key="item.steamid+'-'+item.round" class="highlight"><span>R{{ item.round }}</span><div><b>{{ item.player }}</b><small>三杀及以上</small></div><strong>{{ item.kills }}K</strong></div><div v-if="!analytics.highlights?.length" class="panel-empty">没有三杀及以上高光数据</div></div></section>
      <section v-else-if="active&&tab==='weapons'" class="card analysis-card"><div class="card-head"><h2>武器统计</h2><span>按击杀数排序</span></div><div class="weapon-list"><div v-for="(weapon,index) in analytics.weapons" :key="weapon.weapon" class="weapon-row"><strong>{{ index+1 }}</strong><b>{{ weaponName(weapon.weapon) }}</b><span><i :style="{width:(weapon.kills/(analytics.weapons[0]?.kills||1)*100)+'%'}"></i></span><em>{{ weapon.kills }} 击杀</em><small>{{ weapon.headshots }} 爆头</small></div><div v-if="!analytics.weapons?.length" class="panel-empty">没有武器数据</div></div></section>
      <section v-else-if="active&&tab==='clutches'" class="card analysis-card"><div class="card-head"><h2>残局分析</h2><span>成为最后一名存活队员并赢下该回合</span></div><div v-if="analyticsLoading" class="panel-empty">正在加载…</div><div v-else class="highlight-grid"><div v-for="item in analytics.clutches" :key="item.steamid+'-'+item.round" class="highlight clutch"><span>R{{ item.round }}</span><div><b>{{ item.player }}</b><small>残局获胜</small></div><strong>1v{{ item.x }}</strong></div><div v-if="!analytics.clutches?.length" class="panel-empty">没有可确认的残局获胜数据</div></div></section>
    </main>
  </div>
</template>
