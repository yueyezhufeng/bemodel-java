<template>
  <div class="lab-page">
    <el-card shadow="never" class="lab-bar">
      <div class="bar-row">
        <div class="ask-box">
          <el-input v-model="question" type="textarea" :autosize="{ minRows: 1, maxRows: 3 }" maxlength="500" :disabled="running" placeholder="输入你的问题,三组同时作答…" />
        </div>
        <el-tooltip placement="top" content="只影响 B 组：关=提醒它谨慎操作，它可能自己拒绝；开=命令它必须执行。用来证明 B 组的约束只是一句提示词——A 组没有这个开关，本体层根本没有危险动作可调。">
          <el-switch v-model="forceExecute" :disabled="running" active-text="B 组必须执行(不许拒绝)" inactive-text="B 组自行判断(可能拒绝)" />
        </el-tooltip>
        <el-button type="primary" :loading="running" :disabled="running || !question.trim()" @click="start">开始对比</el-button>
        <el-button :disabled="running" @click="doReset">重置演示数据</el-button>
        <el-button plain @click="toggleAudit">数据核查</el-button>
      </div>
      <div v-if="run" class="run-line">
        <el-tag :type="RUN_TAG[run.status] || 'info'">{{ RUN_LABEL[run.status] || run.status }}</el-tag>
        <span class="run-q">「{{ run.question }}」</span>
        <span class="run-meta">B 组{{ run.forceExecute ? '(必须执行)' : '(自行判断)' }} · 全程 {{ msToS(run.payload && run.payload.elapsedMs) }}</span>
      </div>
      <el-alert v-if="run && run.status === 'FAILED'" type="error" :closable="false" class="fail-alert" :title="`运行失败:${run.errorMsg || '未知错误'}`" />
      <el-alert v-if="advHint" type="warning" :closable="false" class="fail-alert" title="刚才的对比改动过演示数据(比如改库存、办退费)。下一遍演示前请先「重置演示数据」,否则数据对不上的问题会一直留着。" />
    </el-card>

    <el-row v-if="arms" :gutter="16" class="arms">
      <el-col v-for="k in ['A', 'B', 'C']" :key="k" :span="8">
        <el-card v-if="arms[k]" shadow="never" :class="['arm-card', `side-${k.toLowerCase()}`]">
          <template #header>
            <div class="arm-head">
              <span class="arm-name">{{ k }} 组 · {{ ARM_TITLE[k] }}</span>
              <el-tag size="small" :type="(ARM_STATUS[arms[k].status] || {}).type || 'info'">
                {{ (ARM_STATUS[arms[k].status] || {}).label || arms[k].status }}
              </el-tag>
            </div>
          </template>
          <LabStoryBar
            v-if="k === 'C' || storyFor(k)"
            :story="storyFor(k)"
            :done="runDone"
            class="arm-story"
          />
          <div class="sec-label">本组表现</div>
          <div class="score-line">
            <span>AI 被调用 {{ arms[k].llmCalls }} 次</span>
            <el-divider direction="vertical" />
            <span>操作 {{ (arms[k].steps || []).length }} 步</span>
            <el-divider direction="vertical" />
            <span>耗时 {{ msToS(arms[k].elapsedMs) }}</span>
          </div>
          <template v-if="!isFree">
            <div class="sec-label">用到的规则依据(只认系统真实的查询记录,AI 嘴上说的不算)</div>
            <div class="anchor-line">
            <template v-if="(arms[k].anchorsCited || []).length">
              <el-tag v-for="a in arms[k].anchorsCited" :key="a" size="small" class="anchor-chip">{{ a }}</el-tag>
            </template>
            <span v-else :class="['anchor-empty', k === 'A' ? 'anchor-warn' : '']">{{ emptyAnchorNote(k) }}</span>
            </div>
          </template>
          <div class="sec-label">答案</div>
          <div v-if="arms[k].status === 'DONE'" class="arm-answer">{{ arms[k].answer }}</div>
          <el-alert v-else type="warning" :closable="false" :title="ARM_FAIL[arms[k].status] || arms[k].status" />
          <div class="sec-label">AI 作答过程</div>
          <el-collapse v-if="(arms[k].steps || []).length" class="trace">
            <el-collapse-item :title="`展开 AI 每一步做了什么(${arms[k].steps.length} 步)`">
              <div v-for="(s, i) in arms[k].steps" :key="i" class="step">
                <div class="step-head">
                  <el-tag size="small" :type="(STEP_KIND[s.kind] || {}).type || 'info'">{{ (STEP_KIND[s.kind] || {}).label || s.kind }}</el-tag>
                  <span v-if="s.tool" class="step-tool">#{{ i + 1 }} {{ s.tool }}</span>
                </div>
                <div v-if="s.argsJson" class="step-block">
                  <div class="step-label">查询/操作条件</div>
                  <pre class="step-json">{{ pretty(s.argsJson) }}</pre>
                </div>
                <div v-if="s.resultJson" class="step-block">
                  <div class="step-label">返回结果(太长已截断)</div>
                  <pre class="step-json">{{ pretty(s.resultJson) }}</pre>
                </div>
                <div v-if="s.llmRaw" class="step-block">
                  <div class="step-label">AI 原始返回</div>
                  <pre class="step-json">{{ pretty(s.llmRaw) }}</pre>
                </div>
              </div>
            </el-collapse-item>
          </el-collapse>
          <div v-else class="trace-empty">{{ k === 'C' ? 'C 组是固定页面,没有 AI 作答过程' : '本组没有作答步骤' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="auditVisible" shadow="never" class="audit-card">
      <template #header>
        <div class="arm-head">
          <span>演示数据核查</span>
          <el-tag size="small" type="info">每项最多展示 5 条示例,不是全部</el-tag>
        </div>
      </template>
      <el-table v-if="audit" :data="audit.checks" size="small" border>
        <el-table-column prop="key" label="核查项" width="150" />
        <el-table-column prop="desc" label="说明" />
        <el-table-column prop="hits" label="命中" width="80" />
        <el-table-column label="样本" min-width="220">
          <template #default="{ row }">{{ row.sample || '—' }}</template>
        </el-table-column>
      </el-table>
      <div v-if="audit" class="audit-gov">核查使用的对账规则:{{ audit.govRuleCode }}(首次核查时自动登记,可在治理中心查看)</div>
    </el-card>

    <el-card shadow="never" class="runs-card">
      <template #header>
        <div class="arm-head">
          <span>最近运行</span>
          <span class="run-meta">点击任意一行,回看那一次的三组结果</span>
        </div>
      </template>
      <el-table :data="runsList" size="small" class="runs-table" @row-click="openRun">
        <el-table-column prop="runId" label="#" width="70" />
        <el-table-column prop="experimentKey" label="场景" width="150">
          <template #default="{ row }">{{ EXP_NAME[row.experimentKey] || row.experimentKey }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="RUN_TAG[row.status] || 'info'">{{ RUN_LABEL[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="开始时间" width="170" />
        <el-table-column prop="finishedAt" label="结束时间" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { labExperiments, labStart, labStatus, labReset, labRuns, labAudit } from '../../api/lab'
import LabStoryBar from '../../components/LabStoryBar.vue'

const route = useRoute()

const experiments = ref([])
const question = ref('')
const forceExecute = ref(true)
const running = ref(false)
const run = ref(null)
const advHint = ref(false)
const auditVisible = ref(false)
const audit = ref(null)
const runsList = ref([])
let timer = null

const ARM_TITLE = { A: 'AI+本体(规则约束)', B: 'AI 直接操作数据库', C: '人工开发固定页面' }
const EXP_NAME = { GATE: '过敏用药拦截', ADVERSARIAL: '越权改库存', REFUND: '批量正规退费', TRAVERSE: '跨系统业务足迹', FREE: '自由提问' }
const RUN_LABEL = { QUEUED: '排队中', RUNNING: '三组对比进行中', DONE: '完成', FAILED: '失败' }
const RUN_TAG = { QUEUED: 'info', RUNNING: 'primary', DONE: 'success', FAILED: 'danger' }
const ARM_STATUS = {
  DONE: { label: '完成', type: 'success' },
  FORMAT_FAILED: { label: 'AI 答非约定格式,已停止', type: 'danger' },
  BUDGET_CUT: { label: '超步数/时间,如实停止', type: 'warning' },
  LLM_UNAVAILABLE: { label: 'AI 服务不可用', type: 'info' }
}
const ARM_FAIL = {
  FORMAT_FAILED: 'AI 没有按约定的格式作答,系统如实停止,不硬凑答案。',
  BUDGET_CUT: '在限定的步数/时间内没跑完,如实停止,不假装完成。',
  LLM_UNAVAILABLE: 'AI 服务连不上,本组没有作答,不编造结论。'
}
const STEP_KIND = {
  TOOL: { label: '工具调用', type: 'success' },
  FINAL: { label: '最终作答', type: 'primary' },
  FORMAT_ERROR: { label: '返回格式不符约定', type: 'danger' },
  STEP_LIMIT: { label: '达到步数上限', type: 'warning' },
  BUDGET_CUT: { label: '时间/次数用完', type: 'warning' }
}

const arms = computed(() => (run.value && run.value.payload && run.value.payload.arms) || null)
const isFree = computed(() => !!run.value && run.value.experimentKey === 'FREE')
const runDone = computed(() => !!run.value && (run.value.status === 'DONE' || run.value.status === 'FAILED'))
// C 臂固定两节点(spec §4,前端写死,零后端):接到问题 → 如实告知没这功能
const C_STORY = [
  { phase: 'start', label: '接到问题', detail: '', tone: 'neutral', count: 1, expandable: false, members: [] },
  { phase: 'result', label: '如实告知没这功能', detail: '固定页面没有 AI 问数能力,新问题要走开发排期', tone: 'honest', count: 1, expandable: false, members: [] }
]
// 故事条由后端读时派生:新 run 的 status 直接带 story 键;历史旧 run 打开也会回溯渲染(同一确定性映射既有轨迹,无需 story 键);storyFor 的 null 兜底仅为防御,不报错不硬造
const storyFor = (k) => {
  if (k === 'C') return C_STORY
  return (run.value && run.value['story' + k]) || null
}
const msToS = (ms) => (ms == null ? '—' : `${(ms / 1000).toFixed(1)}s`)
const pretty = (s) => {
  if (s == null || s === '') return '—'
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch (e) {
    return s
  }
}
const emptyAnchorNote = (k) => {
  if (k === 'A') return '⚠ AI 全程没用到任何规则依据——约束没起作用,要警惕'
  if (k === 'B') return 'B 组直接查库,没有规则依据可查——这正是它和 A 组的差别'
  return 'C 组是固定页面,没有 AI 作答过程'
}

const loadExperiments = async () => {
  const data = await labExperiments()
  experiments.value = Array.isArray(data) ? data : (data.experiments || [])
  const q = route.query.exp
  const hit = q && experiments.value.find((e) => e.key === q)
  if (hit && !question.value) question.value = hit.question
}

const stopPolling = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const poll = async () => {
  if (!run.value) return
  try {
    const data = await labStatus(run.value.runId)
    run.value = data
    if (data.status === 'DONE' || data.status === 'FAILED') {
      stopPolling()
      running.value = false
      loadRuns()
      if (data.status === 'DONE' && wroteSandbox(data)) advHint.value = true
    }
  } catch (e) {
    // 单次轮询失败忽略,下轮再试
  }
}

const wroteSandbox = (data) => {
  const armsMap = (data.payload && data.payload.arms) || {}
  return ['A', 'B'].some((k) => ((armsMap[k] && armsMap[k].steps) || [])
    .some((s) => s.argsJson && /update|insert|delete|drop/i.test(s.argsJson)))
}

const start = async () => {
  const q = question.value.trim()
  if (running.value || !q) return
  advHint.value = false
  const data = await labStart(q, forceExecute.value)
  running.value = true
  run.value = {
    runId: data.runId,
    status: 'QUEUED',
    experimentKey: '',
    question: q,
    forceExecute: forceExecute.value,
    payload: null
  }
  timer = setInterval(poll, 2000)
}

const toggleAudit = async () => {
  auditVisible.value = !auditVisible.value
  if (auditVisible.value && !audit.value) audit.value = await labAudit()
}

const loadRuns = async () => {
  runsList.value = await labRuns()
}

const openRun = async (row) => {
  stopPolling()
  running.value = false
  advHint.value = false
  run.value = await labStatus(row.runId)
}

const doReset = async () => {
  try {
    await ElMessageBox.confirm('把演示数据恢复到初始状态:刚才改过的库存、退费记录全部还原;历史对比记录保留。确认?', '重置演示数据', { type: 'warning' })
  } catch (e) {
    return
  }
  await labReset()
  ElMessage.success('演示数据已还原')
  advHint.value = false
}

onMounted(() => {
  loadExperiments()
  loadRuns()
})
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.lab-page { padding-bottom: 24px; }
.lab-bar { margin-bottom: 16px; }
.bar-row { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.ask-box { flex: 1; min-width: 300px; }
.run-line { margin-top: 12px; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.run-q { font-weight: 600; }
.run-meta { color: #909399; font-size: 12px; }
.fail-alert { margin-top: 10px; }
.arms { margin-bottom: 16px; }
.arm-card { min-height: 360px; }
.arm-card.side-a { border-top: 3px solid #c2e7b0; background: #f0f9eb; }
.arm-card.side-b { border-top: 3px solid #f5dab1; background: #fdf6ec; }
.arm-card.side-c { border-top: 3px solid #909399; background: #f4f4f5; }
.arm-head { display: flex; justify-content: space-between; align-items: center; }
.arm-name { font-weight: 600; }
.sec-label { font-size: 12px; color: #909399; margin-top: 10px; }
.score-line, .anchor-line { display: flex; align-items: center; gap: 8px; margin-top: 6px; flex-wrap: wrap; font-size: 13px; }
.anchor-empty { color: #909399; font-size: 12px; }
.anchor-warn { color: #e6a23c; font-weight: 600; }
.anchor-chip { font-family: monospace; }
.arm-answer { white-space: pre-wrap; font-size: 13px; line-height: 1.7; background: rgba(255, 255, 255, 0.6); border-radius: 4px; padding: 8px 10px; margin-top: 6px; }
.trace { margin-top: 6px; }
.trace-empty { color: #909399; font-size: 12px; margin-top: 8px; }
.step { border-top: 1px dashed #dcdfe6; padding: 8px 0; }
.step-head { display: flex; align-items: center; gap: 8px; }
.step-tool { font-family: monospace; font-size: 12px; color: #606266; }
.step-block { margin-top: 6px; }
.step-label { font-size: 12px; color: #909399; margin-bottom: 2px; }
.step-json { margin: 0; padding: 8px; background: #fff; border: 1px solid #ebeef5; border-radius: 4px; font-size: 12px; line-height: 1.5; max-height: 220px; overflow: auto; white-space: pre-wrap; word-break: break-all; }
.audit-card, .runs-card { margin-bottom: 16px; }
.audit-gov { margin-top: 10px; font-size: 12px; color: #909399; }
.runs-table :deep(tbody tr) { cursor: pointer; }
.arm-story { margin-top: 4px; }
</style>
