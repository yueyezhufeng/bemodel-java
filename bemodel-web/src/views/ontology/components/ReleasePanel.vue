<template>
  <el-row :gutter="16">
    <!-- 左栏：版本发布 -->
    <el-col :span="10">
      <el-card>
        <template #header><span>版本发布</span></template>
        <div class="current-version">
          <div>
            <div class="cv-label">当前版本</div>
            <div class="cv-value" :class="{ none: currentVersion === '未发布' }">
              {{ currentVersion || '-' }}
            </div>
          </div>
          <div style="display: flex; gap: 8px">
            <el-button :disabled="releases.length < 2" @click="openDiff">变更图谱</el-button>
            <el-button v-if="userStore.canReview" type="primary" :loading="checking" @click="openPublish">发布新版本</el-button>
          </div>
        </div>
        <el-table :data="releases" v-loading="loadingReleases" style="margin-top: 16px" size="small">
          <el-table-column label="版本号" width="90">
            <template #default="{ row }">
              <el-tag type="success"><b>{{ row.versionTag }}</b></el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="changeSummary" label="变更说明" min-width="180" show-overflow-tooltip />
          <el-table-column prop="elementCount" label="元素数" width="80" />
          <el-table-column prop="releasedBy" label="发布人" width="90" />
          <el-table-column prop="createdAt" label="发布时间" width="160" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openSnapshot(row)">快照</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>

    <!-- 右栏：LLM 调用审计 -->
    <el-col :span="14">
      <el-card>
        <template #header><span>LLM 调用审计</span></template>
        <div class="llm-stats" v-loading="loadingStats">
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.total ?? '-' }}</div>
            <div class="llm-stat-label">总调用次数</div>
          </div>
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.successRate ?? '-' }}%</div>
            <div class="llm-stat-label">成功率</div>
          </div>
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.avgLatencyMs ?? '-' }}ms</div>
            <div class="llm-stat-label">平均耗时</div>
          </div>
          <div class="llm-stat">
            <div class="llm-stat-value">{{ stats.currentOntologyVersion || '-' }}</div>
            <div class="llm-stat-label">当前本体版本</div>
          </div>
        </div>
        <div class="by-type" v-if="stats.byType && Object.keys(stats.byType).length">
          <el-tag
            v-for="(n, t) in stats.byType"
            :key="t"
            size="small"
            effect="plain"
            :type="callTypeTag(t)"
            style="margin-right: 8px"
          >{{ callTypeText(t) }} × {{ n }}</el-tag>
        </div>
        <el-table
          ref="logTableRef"
          :data="logs"
          v-loading="loadingLogs"
          size="small"
          style="margin-top: 12px"
          @row-click="toggleLogRow"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="prompt-digest">
                <div class="pd-title">Prompt 摘要</div>
                <pre class="pd-text">{{ row.promptDigest }}</pre>
                <div v-if="row.errMsg" class="pd-err">错误：{{ row.errMsg }}</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="callTypeTag(row.callType)">{{ callTypeText(row.callType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="model" label="模型" width="150" />
          <el-table-column label="提供方" width="80">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.provider === 'backup' ? 'warning' : 'info'">
                {{ row.provider === 'backup' ? '备路' : '主路' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="本体版本" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.ontologyVersion }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.latencyMs }}ms</template>
          </el-table-column>
          <el-table-column label="结果" width="70">
            <template #default="{ row }">
              <el-icon v-if="row.success === 1" color="#67c23a"><CircleCheck /></el-icon>
              <el-icon v-else color="#f56c6c"><CircleClose /></el-icon>
            </template>
          </el-table-column>
          <el-table-column prop="createdAt" label="时间" width="160" />
        </el-table>
        <el-pagination
          v-if="logTotal > 0"
          class="log-pager"
          small
          layout="total, prev, pager, next"
          :current-page="logPage"
          :page-size="pageSize"
          :total="logTotal"
          @current-change="loadLogs"
        />
      </el-card>
    </el-col>
  </el-row>

  <!-- 发布新版本 -->
  <el-dialog v-model="publishVisible" title="发布新版本" width="480px">
    <el-alert
      type="info"
      :closable="false"
      title="将当前全部已发布元素（概念/属性/关系/术语/指标/规则/动作）固化为不可变快照，版本号自动递增"
      style="margin-bottom: 12px"
    />
    <el-form :model="publishForm" label-width="90px">
      <el-form-item label="变更说明" required>
        <el-input v-model="publishForm.changeSummary" type="textarea" :rows="3" />
      </el-form-item>
      <el-form-item label="发布人" required>
        <el-input v-model="publishForm.releasedBy" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="publishVisible = false">取消</el-button>
      <el-button type="primary" :loading="publishing" @click="doPublish">发布</el-button>
    </template>
  </el-dialog>

  <!-- 发布前本体自检缺陷 -->
  <el-dialog v-model="checkVisible" title="发布前自检发现缺陷" width="720px">
    <el-alert
      :type="hasBlocker ? 'error' : 'warning'"
      :closable="false"
      :title="
        hasBlocker
          ? `存在 ${blockerCount} 个阻断级缺陷，禁止发布，请先修复`
          : `存在 ${warnCount} 个警告级缺陷，确认后可强制发布`
      "
      style="margin-bottom: 12px"
    />
    <el-table :data="defects" size="small" max-height="360">
      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.severity === 'BLOCKER' ? 'danger' : 'warning'">
            {{ row.severity === 'BLOCKER' ? '阻断' : '警告' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="200" show-overflow-tooltip />
      <el-table-column prop="message" label="缺陷描述" min-width="220" show-overflow-tooltip />
      <el-table-column label="涉及元素" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ (row.refs || []).join('、') }}</template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button @click="checkVisible = false">关闭</el-button>
      <el-button v-if="!hasBlocker" type="warning" @click="openPublishForce">仍要发布</el-button>
    </template>
  </el-dialog>

  <!-- 变更图谱：相邻版本快照 diff -->
  <el-dialog v-model="diffVisible" title="本体变更图谱" width="1100px" top="4vh">
    <div v-loading="loadingDiff">
      <!-- 版本演化时间线 -->
      <div class="diff-timeline">
        <div class="diff-timeline-title">版本演化</div>
        <div class="diff-timeline-track">
          <template v-for="(r, i) in releasesDesc" :key="r.id">
            <div
              class="tl-node"
              :class="{ sel: diffB?.id === r.id, base: diffA?.id === r.id }"
              :title="`基线 ${r.versionTag}`"
              @click="pickTimeline(r)"
            >
              <div class="tl-dot"></div>
              <div class="tl-tag">{{ r.versionTag }}</div>
              <div class="tl-date">{{ (r.createdAt || '').slice(0, 10) }}</div>
            </div>
            <div v-if="i < releasesDesc.length - 1" class="tl-line"></div>
          </template>
        </div>
      </div>

      <!-- A/B 选择 -->
      <div class="diff-pickers">
        <el-select v-model="diffAId" style="width: 200px" @change="loadDiff">
          <el-option v-for="r in releasesDesc" :key="r.id" :label="`基线：${r.versionTag}`" :value="r.id" />
        </el-select>
        <span class="diff-arrow">→</span>
        <el-select v-model="diffBId" style="width: 200px" @change="loadDiff">
          <el-option v-for="r in releasesDesc" :key="r.id" :label="`对比：${r.versionTag}`" :value="r.id" />
        </el-select>
        <span v-if="diffSummary" class="diff-summary">
          {{ diffSummary.releasedAt }} · {{ diffSummary.total }} 处变更
        </span>
      </div>

      <el-row :gutter="16" v-if="diffSummary">
        <!-- 左：变更清单 -->
        <el-col :span="12">
          <div class="diff-card">
            <div class="diff-card-title">{{ diffA?.versionTag }} → {{ diffB?.versionTag }} 变更内容</div>
            <template v-for="g in diffGroups" :key="g.type">
              <div class="dg-head">
                <span class="dg-name">{{ g.label }}</span>
                <span class="dg-counts">
                  <em v-if="g.added.length" class="c-add">+{{ g.added.length }}</em>
                  <em v-if="g.removed.length" class="c-del">-{{ g.removed.length }}</em>
                  <em v-if="g.changed.length" class="c-chg">±{{ g.changed.length }}</em>
                </span>
              </div>
              <div v-for="e in g.added" :key="'a' + g.type + e.key" class="dg-item">
                <el-tag size="small" type="success" effect="dark" class="dg-op">新增</el-tag>
                <b>{{ e.id }}</b>
                <span v-if="e.name" class="dg-name2">{{ e.name }}</span>
              </div>
              <div v-for="e in g.removed" :key="'r' + g.type + e.key" class="dg-item">
                <el-tag size="small" type="danger" effect="dark" class="dg-op">移除</el-tag>
                <b>{{ e.id }}</b>
                <span v-if="e.name" class="dg-name2">{{ e.name }}</span>
              </div>
              <div v-for="e in g.changed" :key="'c' + g.type + e.key" class="dg-item">
                <el-tag size="small" type="warning" effect="dark" class="dg-op">修改</el-tag>
                <b>{{ e.id }}</b>
                <span class="dg-fields">
                  <template v-for="(fv, f) in e.changes" :key="f">
                    <span class="dg-field">{{ fieldText(f) }}：<s>{{ fv[0] ?? '空' }}</s> → {{ fv[1] ?? '空' }}</span>
                  </template>
                </span>
              </div>
            </template>
            <el-empty
              v-if="!diffGroups.length"
              description="两个版本结构完全一致"
              :image-size="60"
            />
          </div>
        </el-col>

        <!-- 右：影响面 + OWL Diff -->
        <el-col :span="12">
          <div class="diff-card">
            <div class="diff-card-title">影响面分析（按变更元素类型实测统计）</div>
            <div class="impact-grid">
              <div v-for="g in diffGroups" :key="'i' + g.type" class="impact-box">
                <div class="impact-num">{{ g.added.length + g.removed.length + g.changed.length }}</div>
                <div class="impact-label">{{ g.label }}</div>
              </div>
              <div v-if="!diffGroups.length" class="impact-none">无结构变更</div>
            </div>
          </div>
          <div class="diff-card">
            <div class="diff-card-title">OWL Diff（节选，由快照 diff 生成）</div>
            <pre class="owl-diff"><template v-for="(line, i) in owlLines" :key="i"><span
  :class="{ 'owl-add': line.startsWith('+'), 'owl-del': line.startsWith('-') }">{{ line }}
</span></template></pre>
          </div>
        </el-col>
      </el-row>
    </div>
  </el-dialog>

  <!-- 版本快照 -->
  <el-dialog v-model="snapshotVisible" :title="`版本快照：${snapshotRelease?.versionTag || ''}`" width="960px">
    <div v-loading="loadingSnapshot">
      <el-tabs v-model="snapshotTab">
        <el-tab-pane label="元素清单" name="list">
      <template v-if="snapshot">
        <el-descriptions :column="3" border>
          <el-descriptions-item v-for="(label, key) in snapshotLabels" :key="key" :label="label">
            {{ snapshot[key]?.length ?? 0 }}
          </el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">概念清单</el-divider>
        <div class="code-list">
          <el-tag v-for="c in snapshot.concepts || []" :key="c.code" size="small" effect="plain">
            {{ c.code }}
          </el-tag>
        </div>
        <el-divider content-position="left">规则清单</el-divider>
        <div class="code-list">
          <el-tag
            v-for="r in snapshot.rules || []"
            :key="r.ruleCode"
            size="small"
            effect="plain"
            type="warning"
          >{{ r.ruleCode }}</el-tag>
          <span v-if="!(snapshot.rules || []).length" class="code-empty">无</span>
        </div>
        <el-divider content-position="left">动作清单</el-divider>
        <div class="code-list">
          <el-tag
            v-for="a in snapshot.actions || []"
            :key="a.actionCode"
            size="small"
            effect="plain"
            type="success"
          >{{ a.actionCode }}</el-tag>
          <span v-if="!(snapshot.actions || []).length" class="code-empty">无</span>
        </div>
      </template>
        </el-tab-pane>
        <el-tab-pane label="全图" name="graph">
          <template v-if="snapshot">
            <div class="snapshot-scope-note">
              口径：概念仅含已发布；关系边按两端概念过滤后折叠展示（与架构全景同规则）。
            </div>
            <div class="closure-badges" v-if="closureStats.length">
              <el-tag
                v-for="s in closureStats"
                :key="s.name"
                size="small"
                :type="s.capped ? 'danger' : 'info'"
                effect="plain"
              >传递闭包 {{ s.name }}：派生 {{ s.derived }} 条{{ s.capped ? '（超深截断）' : '' }}</el-tag>
            </div>
            <GraphCanvas
              v-if="snapshotGraph.nodes.length"
              :nodes="snapshotGraph.nodes"
              :edges="snapshotGraph.edges"
              :categories="snapshotCategories"
              layout="force"
              height="520px"
              :tooltip-formatter="snapshotTooltip"
            />
            <el-empty v-else description="该版本无已发布概念可画" :image-size="60" />
            <div class="graph-legend-tip">
              <span class="gl-item"><i class="gl-line gl-relation" />关系（绿实线）</span>
              <span class="gl-item"><i class="gl-line gl-transitive" />可传递（橙色虚线）</span>
              <span class="gl-item"><i class="gl-arrow">⇄</i>对称（双向箭头）</span>
              <span class="gl-item">· 尾缀＝约束公理，悬停关系边看含义</span>
            </div>
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-dialog>
</template>

<script setup>
import { useUserStore } from '../../../store/user'
const userStore = useUserStore()
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, CircleClose } from '@element-plus/icons-vue'
import {
  listReleases,
  currentRelease,
  publishRelease,
  releaseDetail,
  listLlmLogs,
  llmStats
} from '../../../api/release'
import { checkOntology } from '../../../api/ontology'
import GraphCanvas from '../../../components/GraphCanvas.vue'
import { axiomSuffixOf, axiomStyleOf, axiomTooltipOf, foldRelations } from '../../../utils/axiomEdge'

// ---------- 版本发布 ----------
const releases = ref([])
const loadingReleases = ref(false)
const currentVersion = ref('')

const loadReleases = async () => {
  loadingReleases.value = true
  try {
    const [list, cur] = await Promise.all([listReleases(), currentRelease()])
    releases.value = list
    currentVersion.value = cur.version
  } finally {
    loadingReleases.value = false
  }
}

const publishVisible = ref(false)
const publishing = ref(false)
const publishForm = reactive({ changeSummary: '', releasedBy: '' })

// 发布前自检：无缺陷直接进发布框；有缺陷先展示缺陷表，BLOCKER 禁止、WARN 可 force
const checking = ref(false)
const checkVisible = ref(false)
const defects = ref([])
const forcePublish = ref(false)
const blockerCount = computed(() => defects.value.filter((d) => d.severity === 'BLOCKER').length)
const warnCount = computed(() => defects.value.filter((d) => d.severity === 'WARN').length)
const hasBlocker = computed(() => blockerCount.value > 0)

const openPublishDialog = () => {
  Object.assign(publishForm, { changeSummary: '', releasedBy: '' })
  publishVisible.value = true
}

const openPublish = async () => {
  checking.value = true
  try {
    const res = await checkOntology()
    defects.value = res.defects || []
    if (!defects.value.length) {
      forcePublish.value = false
      openPublishDialog()
    } else {
      checkVisible.value = true
    }
  } finally {
    checking.value = false
  }
}

const openPublishForce = () => {
  forcePublish.value = true
  checkVisible.value = false
  openPublishDialog()
}

const doPublish = async () => {
  if (!publishForm.changeSummary.trim() || !publishForm.releasedBy.trim()) {
    ElMessage.warning('请填写变更说明和发布人')
    return
  }
  publishing.value = true
  try {
    const rel = await publishRelease({ ...publishForm, force: String(forcePublish.value) })
    ElMessage.success(`已发布 ${rel.versionTag}（${rel.elementCount} 个元素）`)
    publishVisible.value = false
    loadReleases()
  } finally {
    publishing.value = false
  }
}

// ---------- 快照 ----------
const snapshotVisible = ref(false)
const loadingSnapshot = ref(false)
const snapshotRelease = ref(null)
const snapshot = ref(null)

const snapshotLabels = {
  concepts: '概念',
  attributes: '属性',
  relations: '关系',
  terms: '术语',
  metrics: '指标',
  rules: '规则',
  actions: '动作'
}

const openSnapshot = async (row) => {
  snapshotRelease.value = row
  snapshot.value = null
  snapshotVisible.value = true
  loadingSnapshot.value = true
  try {
    const detail = await releaseDetail(row.id)
    snapshot.value = JSON.parse(detail.snapshotJson || '{}')
    snapshotTab.value = 'list'
  } finally {
    loadingSnapshot.value = false
  }
}

// ---------- 快照全图（spec 2026-09-21 §6）：零后端，snapshotJson 自足 ----------
const snapshotTab = ref('list')

// 快照 relations 是全状态实体：只画两端概念均已发布的边（口径说明在模板固定展示）
const snapshotGraph = computed(() => {
  if (!snapshot.value) return { nodes: [], edges: [] }
  const concepts = snapshot.value.concepts || []
  const published = new Set(concepts.map((c) => `C:${c.code}`))
  const nodes = concepts.map((c) => ({
    id: `C:${c.code}`,
    name: c.name,
    category: c.domainCode || '未分域',
    symbolSize: 22
  }))
  const rels = (snapshot.value.relations || []).filter(
    (r) => published.has(`C:${r.fromConcept}`) && published.has(`C:${r.toConcept}`)
  )
  const edges = foldRelations(rels).map((e) => {
    const style = axiomStyleOf(e, '#95d475', { width: 1.5, curveness: 0.12 })
    return {
      ...e,
      label: { show: true, formatter: e.label + axiomSuffixOf(e), fontSize: 11, color: '#909399' },
      ...style,
      _raw: e
    }
  })
  return { nodes, edges }
})

const DOMAIN_PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#13c2c2', '#d3adf7', '#f5dab1', '#909399']
const snapshotCategories = computed(() => {
  const domains = [...new Set(snapshotGraph.value.nodes.map((n) => n.category))]
  return domains.map((d, i) => ({ name: d, itemStyle: { color: DOMAIN_PALETTE[i % DOMAIN_PALETTE.length] } }))
})

// 闭包统计徽标（快照只存 stats，不存闭包行；capped=发布时超深截断）
const closureStats = computed(() => {
  const rc = snapshot.value?.relationClosures || {}
  return Object.entries(rc).map(([name, s]) => ({ name, edges: s.edges, derived: s.derived, capped: !!s.capped }))
})

const snapshotTooltip = (p) => {
  if (p.dataType === 'edge') {
    const e = p.data._raw
    if (!e) return ''
    const lines = [`<b>${e.label}</b>${axiomSuffixOf(e)}`]
    for (const l of axiomTooltipOf(e)) lines.push(`<br/>${l}`)
    return lines.join('')
  }
  return p.data.name || ''
}

// ---------- 变更图谱：相邻版本快照 diff ----------
// 各元素类型的 diff 键与参与比对的字段（快照里是发布时的完整实体）
const DIFF_TYPES = [
  { type: 'concepts', label: '概念', id: (e) => e.code, name: (e) => e.name,
    fields: ['name', 'definition', 'domainCode'], fieldText: { name: '名称', definition: '定义', domainCode: '业务域' } },
  { type: 'attributes', label: '属性', id: (e) => `${e.conceptCode}.${e.attrCode}`, name: (e) => e.attrName,
    fields: ['attrName', 'dataType', 'definition'], fieldText: { attrName: '属性名', dataType: '数据类型', definition: '定义' } },
  { type: 'relations', label: '关系', id: (e) => `${e.fromConcept} →${e.relationName}→ ${e.toConcept}`, name: () => '',
    fields: ['description'], fieldText: { description: '描述' } },
  { type: 'terms', label: '术语', id: (e) => e.term, name: (e) => e.conceptCode,
    fields: ['termType', 'codeSystem', 'standardCode'], fieldText: { termType: '类型', codeSystem: '编码体系', standardCode: '标准编码' } },
  { type: 'metrics', label: '指标', id: (e) => e.metricCode, name: (e) => e.name,
    fields: ['name', 'definition', 'formula', 'warnThreshold'], fieldText: { name: '名称', definition: '口径定义', formula: '公式', warnThreshold: '告警阈值' } },
  { type: 'rules', label: '规则', id: (e) => e.ruleCode, name: (e) => e.name,
    fields: ['name', 'severity', 'engine'], fieldText: { name: '名称', severity: '级别', engine: '引擎' } },
  { type: 'actions', label: '动作', id: (e) => e.actionCode, name: (e) => e.name,
    fields: ['name', 'triggerDesc', 'fromStatus', 'toStatus'], fieldText: { name: '名称', triggerDesc: '触发说明', fromStatus: '前置状态', toStatus: '目标状态' } }
]

const diffVisible = ref(false)
const loadingDiff = ref(false)
const diffAId = ref(null)
const diffBId = ref(null)
const diffA = ref(null)
const diffB = ref(null)
const snapA = ref({})
const snapB = ref({})

const releasesDesc = computed(() => [...releases.value].sort((x, y) => y.id - x.id))

// 结构化 diff：added/removed 按 key 集合，changed 比对字段值
const diffGroupOf = (spec) => {
  const aList = snapA.value[spec.type] || []
  const bList = snapB.value[spec.type] || []
  const mapA = new Map(aList.map((e) => [spec.id(e), e]))
  const mapB = new Map(bList.map((e) => [spec.id(e), e]))
  const added = bList.filter((e) => !mapA.has(spec.id(e))).map((e) => ({ key: spec.id(e), id: spec.id(e), name: spec.name(e) }))
  const removed = aList.filter((e) => !mapB.has(spec.id(e))).map((e) => ({ key: spec.id(e), id: spec.id(e), name: spec.name(e) }))
  const changed = []
  for (const [key, ea] of mapA) {
    const eb = mapB.get(key)
    if (!eb) continue
    const changes = {}
    for (const f of spec.fields) {
      const va = ea[f] ?? null
      const vb = eb[f] ?? null
      if (String(va) !== String(vb)) changes[f] = [va, vb]
    }
    if (Object.keys(changes).length) {
      changed.push({ key, id: key, name: spec.name(eb), changes })
    }
  }
  return { ...spec, added, removed, changed }
}

const diffGroups = computed(() =>
  diffA.value && diffB.value
    ? DIFF_TYPES.map(diffGroupOf).filter((g) => g.added.length || g.removed.length || g.changed.length)
    : []
)

const diffSummary = computed(() => {
  if (!diffB.value || !diffGroups.value.length) return null
  const total = diffGroups.value.reduce(
    (n, g) => n + g.added.length + g.removed.length + g.changed.length, 0
  )
  return { total, releasedAt: (diffB.value.createdAt || '').replace('T', ' ').slice(0, 16) }
})

const fieldText = (f) => {
  for (const t of DIFF_TYPES) if (t.fieldText[f]) return t.fieldText[f]
  return f
}

// OWL 风格 diff（由真实快照 diff 生成，节选前 26 行）
const owlLines = computed(() => {
  const lines = []
  for (const g of diffGroups.value) {
    for (const e of g.added) {
      if (g.type === 'concepts') {
        lines.push(`+ :${e.id} a owl:Class ;`)
        lines.push(`+   rdfs:label "${e.name || e.id}"@zh .`)
      } else if (g.type === 'attributes') {
        lines.push(`+ :${e.id} a owl:DatatypeProperty ;`)
        lines.push(`+   rdfs:label "${e.name || e.id}"@zh .`)
      } else if (g.type === 'relations') {
        lines.push(`+ ${e.id} .`)
      } else {
        lines.push(`+ :${e.id} rdfs:label "${e.name || e.id}"@zh . # ${g.label}`)
      }
    }
    for (const e of g.changed) {
      for (const [f, [va, vb]] of Object.entries(e.changes)) {
        lines.push(`- :${e.id} ${f} "${va ?? ''}"`)
        lines.push(`+ :${e.id} ${f} "${vb ?? ''}"`)
      }
    }
    for (const e of g.removed) {
      lines.push(`- :${e.id} a owl:Class . # ${g.label} 移除`)
    }
    if (lines.length > 26) break
  }
  if (lines.length > 26) lines.push(`... 共 ${diffSummary.value?.total ?? '-'} 处变更，已节选`)
  return lines
})

const pickTimeline = (r) => {
  // 点击时间线节点设为对比版本；基线自动取它的上一版
  diffBId.value = r.id
  const idx = releasesDesc.value.findIndex((x) => x.id === r.id)
  const prev = releasesDesc.value[idx + 1]
  if (prev) diffAId.value = prev.id
  loadDiff()
}

const openDiff = () => {
  const desc = releasesDesc.value
  if (desc.length < 2) return
  diffAId.value = desc[1].id
  diffBId.value = desc[0].id
  diffVisible.value = true
  loadDiff()
}

const loadDiff = async () => {
  if (!diffAId.value || !diffBId.value) return
  loadingDiff.value = true
  try {
    const [da, db] = await Promise.all([releaseDetail(diffAId.value), releaseDetail(diffBId.value)])
    diffA.value = da
    diffB.value = db
    snapA.value = JSON.parse(da.snapshotJson || '{}')
    snapB.value = JSON.parse(db.snapshotJson || '{}')
  } finally {
    loadingDiff.value = false
  }
}

// ---------- LLM 调用审计 ----------
const logs = ref([])
const logPage = ref(1)
const logTotal = ref(0)
const pageSize = 20
const stats = ref({})
const loadingLogs = ref(false)
const loadingStats = ref(false)
const logTableRef = ref(null)

const callTypeText = (t) =>
  ({
    MAPPING_SUGGEST: '映射推荐',
    SEARCH_ANSWER: '语义搜索',
    RCA_REPORT: '根因报告',
    IMPACT_ADVICE: '影响评估',
    CLARIFY_Q: '澄清追问',
    CS_REPLY: '客服回复',
    CS_ROUTE: '客服意图',
    CS_SEMANTIC_PLAN: '问数规划',
    CS_SEMANTIC_ANSWER: '语义问数',
    VALUE_SUMMARY: '价值总结',
    MISS_CLASSIFY: '缺口归类',
    MISS_PROPOSAL: '缺口提案',
    QC_REVIEW: '质控点评',
    LAB_A: '实验A组',
    LAB_B: '实验B组',
    CS_DOC_ANSWER: '文档问答',
    EMBED_INGEST: '文档向量化',
    EMBED_QUERY: '检索向量化'
  }[t] || t)

const callTypeTag = (t) =>
  ({
    MAPPING_SUGGEST: 'warning',
    SEARCH_ANSWER: 'success',
    RCA_REPORT: 'danger',
    IMPACT_ADVICE: 'primary',
    CLARIFY_Q: 'primary',
    CS_REPLY: 'primary',
    CS_ROUTE: 'primary',
    CS_SEMANTIC_PLAN: 'primary',
    CS_SEMANTIC_ANSWER: 'success',
    VALUE_SUMMARY: 'success',
    MISS_CLASSIFY: 'warning',
    MISS_PROPOSAL: 'warning',
    QC_REVIEW: 'danger',
    LAB_A: 'info',
    LAB_B: 'info',
    CS_DOC_ANSWER: 'primary',
    EMBED_INGEST: 'info',
    EMBED_QUERY: 'info'
  }[t] || 'info')

const toggleLogRow = (row) => logTableRef.value?.toggleRowExpansion(row)

const loadLogs = async (page = logPage.value) => {
  loadingLogs.value = true
  try {
    const res = await listLlmLogs(page, pageSize)
    logs.value = res.list
    logTotal.value = res.total
    logPage.value = res.pageNum
  } finally {
    loadingLogs.value = false
  }
}

const loadLlm = async () => {
  loadingStats.value = true
  try {
    await Promise.all([loadLogs(), llmStats().then((s) => (stats.value = s))])
  } finally {
    loadingStats.value = false
  }
}

onMounted(() => {
  loadReleases()
  loadLlm()
})
</script>

<style scoped>
.current-version {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cv-label {
  font-size: 12px;
  color: #909399;
}

.cv-value {
  font-size: 28px;
  font-weight: 700;
  color: #67c23a;
}

.cv-value.none {
  color: #909399;
}

.llm-stats {
  display: flex;
  gap: 32px;
}

.llm-stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}

.llm-stat-label {
  font-size: 12px;
  color: #909399;
  margin-top: 2px;
}

.by-type {
  margin-top: 12px;
}

.log-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.prompt-digest {
  padding: 8px 12px;
}

.pd-title {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.pd-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
}

.pd-err {
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.code-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.code-empty {
  color: #c0c4cc;
  font-size: 13px;
}

/* ---------- 变更图谱 ---------- */
.diff-timeline {
  margin-bottom: 14px;
}

.diff-timeline-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
}

.diff-timeline-track {
  display: flex;
  align-items: flex-start;
  overflow-x: auto;
  padding-bottom: 6px;
}

.tl-node {
  cursor: pointer;
  text-align: center;
  min-width: 76px;
  padding: 4px 6px;
  border-radius: 8px;
  transition: var(--transition, 0.2s);
}

.tl-node:hover {
  background: #f5f7fa;
}

.tl-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #dcdfe6;
  margin: 0 auto 6px;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #dcdfe6;
}

.tl-node.sel .tl-dot {
  background: var(--el-color-success);
  box-shadow: 0 0 0 2px var(--el-color-success-light-5, #95d475);
}

.tl-node.base .tl-dot {
  background: #909399;
}

.tl-tag {
  font-size: 12px;
  font-weight: 700;
  color: #303133;
}

.tl-node.sel .tl-tag {
  color: var(--el-color-success);
}

.tl-date {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.tl-line {
  width: 40px;
  height: 2px;
  background: #e4e7ed;
  margin-top: 11px;
  flex-shrink: 0;
}

.diff-pickers {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.diff-arrow {
  color: #909399;
  font-size: 16px;
}

.diff-summary {
  margin-left: auto;
  font-size: 12px;
  color: #606266;
}

.diff-card {
  background: #fff;
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 14px;
}

.diff-card-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 12px;
}

.dg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
  border-bottom: 1px dashed #ebeef5;
  padding: 8px 0 4px;
  margin-bottom: 6px;
}

.dg-name {
  font-weight: 600;
}

.dg-counts em {
  font-style: normal;
  margin-left: 8px;
  font-weight: 700;
}

.c-add {
  color: var(--el-color-success);
}

.c-del {
  color: var(--el-color-danger);
}

.c-chg {
  color: var(--el-color-warning);
}

.dg-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
  padding: 4px 0;
  flex-wrap: wrap;
}

.dg-op {
  flex-shrink: 0;
}

.dg-name2 {
  color: #909399;
  font-size: 12px;
}

.dg-fields {
  display: inline-flex;
  gap: 10px;
  flex-wrap: wrap;
  font-size: 12px;
  color: #606266;
}

.dg-field s {
  color: #c0c4cc;
}

.impact-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}

.impact-box {
  border: 1px solid var(--el-border-color-light, #e4e7ed);
  border-radius: 8px;
  padding: 10px;
  text-align: center;
}

.impact-num {
  font-size: 20px;
  font-weight: 700;
  color: var(--el-color-warning);
}

.impact-label {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.impact-none {
  grid-column: 1 / -1;
  text-align: center;
  color: #909399;
  font-size: 12px;
  padding: 8px;
}

.owl-diff {
  margin: 0;
  background: #0f172a;
  border-radius: 6px;
  padding: 12px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.8;
  color: #94a3b8;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.owl-add {
  color: #4ade80;
}

.owl-del {
  color: #f87171;
  text-decoration: line-through;
  opacity: 0.7;
}

.snapshot-scope-note {
  font-size: 12px;
  color: #909399;
  padding-bottom: 8px;
}

.closure-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-bottom: 8px;
}

.graph-legend-tip {
  display: flex;
  gap: 20px;
  align-items: center;
  padding: 8px 4px 0;
  font-size: 12px;
  color: #909399;
}

.gl-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.gl-line {
  width: 20px;
  display: inline-block;
}

.gl-line.gl-relation {
  border-top: 2px solid #95d475;
}

.gl-line.gl-transitive {
  border-top: 2px dashed #e6a23c;
}

.gl-arrow {
  font-style: normal;
  color: #606266;
  font-weight: 700;
}
</style>
