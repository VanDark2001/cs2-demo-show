<!-- 玩家列表页：展示按 SteamID 跨比赛合并后的个人数据。 -->
<script setup>
import { onMounted } from 'vue'
import PlayerAvatar from '../components/PlayerAvatar.vue'
import { useDemoData } from '../composables/useDemoData'
const { matches, globalPlayers, loading, error, load } = useDemoData()
onMounted(() => load())
</script>

<template><main class="portal-page"><div v-if="loading" class="state">正在读取 MySQL 数据…</div><div v-else-if="error" class="state error">{{ error }}</div><template v-else><section class="portal-hero"><div><div class="eyebrow">PLAYER DATABASE</div><h1>玩家</h1><p>按 SteamID 合并最近 {{ matches.length }} 场比赛的数据</p></div><strong>{{ globalPlayers.length }}</strong></section><section class="card portal-card"><div class="portal-table player-summary"><div class="portal-row portal-th"><span>#</span><span>玩家</span><span>场次</span><span>K / A / D</span><span>平均 ADR</span><span>爆头率</span><span>平均 Rating</span></div><RouterLink v-for="(player,index) in globalPlayers" :key="player.steamid" :to="{ name: 'player-detail', params: { steamid: player.steamid } }" class="portal-row player-link"><span>{{ index+1 }}</span><span class="player"><PlayerAvatar :steamid="player.steamid" :name="player.name"/><b>{{ player.name }}</b></span><span>{{ player.matches }}</span><span>{{ player.kills }} / {{ player.assists }} / {{ player.deaths }}</span><span>{{ player.adr }}</span><span>{{ player.hs }}%</span><span>{{ player.rating }}</span></RouterLink></div></section></template></main></template>
