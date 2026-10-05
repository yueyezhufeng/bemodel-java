<template>
  <div class="story-wrap">
    <div class="story-bar">
      <template v-if="story && story.length">
        <div v-for="(n, i) in story" :key="i" :class="['story-node', `tone-${n.tone}`, { reached: i < lit }]">
          <span class="story-dot">{{ i + 1 }}</span>
          <span class="story-label">{{ n.label }}<template v-if="n.count > 1"> ×{{ n.count }}</template></span>
          <span v-if="n.expandable" class="story-expand" @click="toggle(i)">{{ open[i] ? '收起' : '展开' }}</span>
        </div>
      </template>
      <div v-else class="story-node waiting">
        <span class="story-dot">…</span>
        <span class="story-label">本组运行中</span>
      </div>
      <el-button v-if="done && story && story.length" size="small" link type="primary" class="replay-btn" @click="replay">重播</el-button>
    </div>
    <div v-if="openRows.length" class="story-members">
      <div v-for="(m, i) in openRows" :key="i" class="story-member">{{ m }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'

const props = defineProps({
  story: { type: Array, default: null },
  done: { type: Boolean, default: false }
})

// 点亮是纯前端动画:story 从无到有(或条数变化)时从左到右逐个点亮;
// 运行中快闪(150ms/节点),重播慢速(spec §4 0.4s/节点)。不重复请求,不改轮询。
const lit = ref(0)
const open = ref({})
let cascadeTimer = null

const openRows = computed(() => {
  const rows = []
  ;(props.story || []).forEach((n, i) => {
    if (open.value[i] && Array.isArray(n.members)) rows.push(...n.members)
  })
  return rows
})

const stopCascade = () => {
  if (cascadeTimer) {
    clearInterval(cascadeTimer)
    cascadeTimer = null
  }
}
const cascade = (interval) => {
  stopCascade()
  lit.value = 0
  if (!props.story || !props.story.length) return
  let i = 0
  cascadeTimer = setInterval(() => {
    i += 1
    lit.value = i
    if (i >= props.story.length) stopCascade()
  }, interval)
}
// 签名=全部标签拼接:臂未变时轮询替换数组不重播,臂真有进展才重新点亮;
// 标签串变化=新一轮运行,展开状态一并复位(重播不触发 watch,展开保持)
const sig = computed(() => (props.story ? props.story.map((n) => n.label).join('|') : ''))
watch(sig, () => {
  open.value = {}
  cascade(props.done ? 400 : 150)
}, { immediate: true })
watch(() => props.done, (d) => { if (d) cascade(400) })
onBeforeUnmount(stopCascade)

const toggle = (i) => { open.value[i] = !open.value[i] }
const replay = () => cascade(400)
</script>

<style scoped>
.story-wrap { margin-top: 2px; }
.story-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 4px; }
.story-node { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: #c0c4cc; }
.story-dot {
  width: 18px; height: 18px; border-radius: 50%;
  border: 1.5px solid #dcdfe6; color: #c0c4cc; font-size: 11px;
  display: inline-flex; align-items: center; justify-content: center; background: #fff;
  flex-shrink: 0;
}
.story-node.reached .story-dot { background: #909399; border-color: #909399; color: #fff; }
.story-node.reached .story-label { color: #606266; }
.tone-key.reached .story-dot { background: #67c23a; border-color: #67c23a; }
.tone-key.reached .story-label { color: #529b2e; font-weight: 600; }
.tone-success.reached .story-dot { background: #529b2e; border-color: #529b2e; }
.tone-success.reached .story-label { color: #529b2e; font-weight: 600; }
.tone-violation.reached .story-dot { background: #f56c6c; border-color: #f56c6c; }
.tone-violation.reached .story-label { color: #f56c6c; font-weight: 600; }
.tone-honest.reached .story-dot { background: #e6a23c; border-color: #e6a23c; }
.tone-honest.reached .story-label { color: #e6a23c; font-weight: 600; }
.tone-failure.reached .story-dot { background: #f56c6c; border-color: #f56c6c; }
.tone-failure.reached .story-label { color: #f56c6c; }
.story-node.waiting .story-dot { animation: storyBreath 1.6s ease-in-out infinite; }
@keyframes storyBreath {
  0%, 100% { opacity: 0.35; }
  50% { opacity: 1; }
}
.story-expand { color: #409eff; cursor: pointer; font-size: 12px; }
.replay-btn { margin-left: 6px; }
.story-members {
  margin-top: 6px; padding: 6px 10px; background: rgba(255, 255, 255, 0.6);
  border-radius: 4px; font-size: 12px; color: #606266; line-height: 1.8;
}
</style>
