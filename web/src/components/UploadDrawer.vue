<!-- 单文件上传抽屉：提交 Demo，等待解析完成后刷新比赛列表。 -->
<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { API } from '../lib/presentation'
import { useDemoData } from '../composables/useDemoData'

const emit = defineEmits(['close'])
const router = useRouter()
const { load } = useDemoData()
const file = ref(null)
const uploading = ref(false)
const message = ref('')

function chooseFile(event) { file.value = event.target.files?.[0] || null; message.value = '' }
async function upload() {
  // 上传期间锁定交互，避免同一文件被用户重复提交。
  if (!file.value || uploading.value) return
  uploading.value = true
  message.value = '正在上传并解析 Demo，大文件可能需要几分钟…'
  const body = new FormData()
  body.append('file', file.value)
  body.append('recordedAt', String(file.value.lastModified || 0))
  try {
    const response = await fetch(`${API}/demos`, { method: 'POST', body })
    const result = await response.json().catch(() => ({}))
    if (!response.ok) throw Error(result.message || `HTTP ${response.status}`)
    message.value = '解析完成，正在刷新比赛列表…'
    await load(null, true)
    await router.push('/matches')
    emit('close')
  } catch (exception) {
    message.value = `解析失败：${exception.message}`
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="overlay" @click.self="!uploading&&$emit('close')"><div class="drawer"><button class="close" :disabled="uploading" @click="$emit('close')">×</button><div class="eyebrow">ADMIN / INGEST</div><h2>上传并解析 Demo</h2><p>上传后自动调用项目内 Python 解析器并写入 MySQL。</p><label class="drop"><input type="file" accept=".dem" :disabled="uploading" @change="chooseFile"><span>⇧</span><b>{{ file?.name||'点击选择 .dem 文件' }}</b><small>支持最大 512MB</small></label><p v-if="message" class="upload-status">{{ message }}</p><button class="primary" :disabled="!file||uploading" @click="upload">{{ uploading?'正在解析…':'开始解析' }}</button></div></div>
</template>
