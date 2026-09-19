<!-- Demo 解析页：管理批量文件队列、并发数、任务状态和失败原因。 -->
<script setup>
import { useParserJobs } from '../composables/useParserJobs'
const { jobs, running, concurrency, summary, sizeLabel, addFiles, removeJob, startBatch } = useParserJobs()

function chooseFiles(event) { addFiles(event.target.files); event.target.value = '' }
function dropFiles(event) { addFiles(event.dataTransfer?.files) }
</script>

<template>
  <main class="portal-page parser-page">
    <section class="portal-hero"><div><div class="eyebrow">DEMO INGEST CONSOLE</div><h1>Demo 解析后台</h1><p>批量上传并发解析，写入 MySQL 后自动删除服务器端 Demo，仅保留文件指纹</p></div><strong>{{ summary.total }}</strong></section>
    <section class="parser-grid">
      <div class="parser-control card"><label class="batch-drop" @dragover.prevent @drop.prevent="dropFiles"><input type="file" accept=".dem" multiple :disabled="running" @change="chooseFiles"><span>⇧</span><b>选择或拖入多个 Demo</b><small>支持多选 .dem；单文件最大 512MB</small></label><div class="concurrency"><div><b>并发解析数</b><small>同时启动的 Python 解析进程</small></div><select v-model="concurrency" :disabled="running"><option :value="1">1 个</option><option :value="2">2 个</option><option :value="3">3 个</option></select></div><button class="primary" :disabled="running||!jobs.length" @click="startBatch">{{ running ? `解析中（${summary.active} 个运行）` : `开始解析 ${jobs.length} 个 Demo` }}</button></div>
      <div class="parser-summary"><article><small>队列</small><strong>{{ summary.total }}</strong></article><article><small>成功</small><strong>{{ summary.done }}</strong></article><article><small>跳过</small><strong>{{ summary.skipped }}</strong></article><article><small>失败</small><strong>{{ summary.failed }}</strong></article></div>
    </section>
    <section class="card parser-queue"><div class="card-head"><h2>解析队列</h2><span>上传前检查 MySQL，重复 Demo 直接跳过 · 解析结束自动删除源文件</span></div><div v-if="!jobs.length" class="panel-empty">尚未选择 Demo 文件</div><div v-for="job in jobs" :key="job.id" class="parser-job"><span class="job-state" :class="job.status">{{ job.status === 'success' ? '✓' : job.status === 'skipped' ? '↷' : job.status === 'failed' ? '!' : job.status === 'uploading' ? '↻' : '…' }}</span><div><b>{{ job.file.name }}</b><small>{{ sizeLabel(job.file.size) }}</small><p v-if="job.status==='failed'" class="job-error">失败原因：{{ job.message }}</p><p v-else class="job-message">{{ job.message }}</p></div><span class="job-label" :class="job.status">{{ { queued: '等待中', uploading: '检测/解析中', success: '成功', skipped: '已跳过', failed: '失败' }[job.status] }}</span><button :disabled="running" @click="removeJob(job.id)">×</button></div></section>
  </main>
</template>
