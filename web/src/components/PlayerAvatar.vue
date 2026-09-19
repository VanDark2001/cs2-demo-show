<!-- 玩家头像：通过后端缓存接口加载，并在图片缺失时显示昵称首字母。 -->
<script setup>
import { avatarUrl, hideBrokenAvatar } from '../lib/presentation'
defineProps({ steamid: String, name: String })
</script>

<template><span class="avatar"><img :src="avatarUrl(steamid)" :alt="name" @error="hideBrokenAvatar">{{ name?.slice(0,1) }}</span></template>
