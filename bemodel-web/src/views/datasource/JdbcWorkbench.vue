<template>
  <div class="page">
    <!-- 数据源卡片行 -->
    <div class="ds-cards" v-loading="loadingDs">
      <el-card
        v-for="ds in datasources"
        :key="ds.dsCode"
        class="ds-card"
        :class="{ active: currentDs?.dsCode === ds.dsCode }"
        shadow="hover"
        @click="selectDs(ds)"
      >
        <div class="ds-title">{{ ds.dsName }}</div>
        <div class="ds-meta">产品：{{ ds.productName }}</div>
        <div class="ds-meta">{{ ds.dbType }} · {{ ds.host }}:{{ ds.port }}/{{ ds.dbName }}</div>
        <div class="ds-meta">账号：{{ ds.username }}</div>
        <el-button
          v-if="!userStore.isViewer"
          size="small"
          class="scan-btn"
          :loading="scanningDs === ds.dsCode"
          @click.stop="doScan(ds)"
        >扫描</el-button>
      </el-card>
    </div>

    <template v-if="currentDs">
      <el-row :gutter="16" style="margin-top: 16px">
        <el-col :span="8">
          <el-card>
            <template #header>
              <span>物理表（{{ currentDs.dsCode }}）</span>
            </template>
            <el-table
              :data="tables"
              v-loading="loadingTables"
              highlight-current-row
              height="540"
              @row-click="selectTable"
            >
              <el-table-column prop="tableName" label="表名" width="160" />
              <el-table-column prop="tableComment" label="注释" show-overflow-tooltip />
            </el-table>
          </el-card>
        </el-col>
        <el-col :span="16">
          <el-card v-if="currentTable">
            <template #header>
              <div class="card-header">
                <span>
                  映射工作台：{{ currentTable }}
                  <el-tag
                    v-if="aiMeta"
                    size="small"
                    style="margin-left: 8px"
                    :type="aiMeta.llmUsed ? 'success' : 'info'"
                    effect="plain"
                  >
                    {{ aiMeta.llmUsed ? `AI生成（${aiMeta.model}）` : '规则降级' }}
                  </el-tag>
                </span>
                <el-tooltip content="只读角色无写权限" :disabled="!userStore.isViewer" placement="top">
                  <span>
                    <el-button type="primary" :disabled="userStore.isViewer" :loading="aiLoading" @click="runAiSuggest">
                      AI 推荐映射
                    </el-button>
                  </span>
                </el-tooltip>
              </div>
            </template>
            <el-table :data="rows" v-loading="loadingColumns">
              <el-table-column label="物理列" width="150">
                <template #default="{ row }">
                  {{ row.columnName }}
                  <el-tag v-if="row.isPk === 1" size="small" type="danger" effect="plain">PK</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="dataType" label="类型" width="90" />
              <el-table-column prop="columnComment" label="注释" width="130" show-overflow-tooltip />
              <el-table-column label="当前映射" min-width="200">
                <template #default="{ row }">
                  <template v-if="row.mapping">
                    <el-tooltip
                      :disabled="!row.mapping.valueMap"
                      :content="`值映射：${row.mapping.valueMap}`"
                      placement="top"
                    >
                      <el-tag>{{ row.mapping.conceptCode }}.{{ row.mapping.attrCode }}</el-tag>
                    </el-tooltip>
                    <el-tag
                      v-if="row.mapping.source === 'AI'"
                      size="small"
                      type="warning"
                      effect="plain"
                      style="margin-left: 4px"
                    >AI</el-tag>
                    <el-tag
                      size="small"
                      :type="statusMeta(row.mapping.status).type"
                      effect="plain"
                      style="margin-left: 4px"
                    >{{ statusMeta(row.mapping.status).label }}</el-tag>
                  </template>
                  <el-tag v-else type="info" effect="plain">未映射</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="AI 建议" min-width="230">
                <template #default="{ row }">
                  <template v-if="row.suggestion">
                    <el-tooltip :content="row.suggestion.reason" placement="top">
                      <el-tag type="warning" effect="plain">
                        {{ row.suggestion.conceptCode }}.{{ row.suggestion.attrCode }}
                        （{{ Math.round(row.suggestion.confidence * 100) }}%）
                      </el-tag>
                    </el-tooltip>
                    <el-button
                      v-if="!userStore.isViewer"
                      size="small"
                      link
                      type="primary"
                      :disabled="row.accepted"
                      @click="accept(row)"
                    >{{ row.accepted ? '已采纳' : '采纳' }}</el-button>
                  </template>
                  <span v-else class="no-suggest">-</span>
                </template>
              </el-table-column>
              <el-table-column v-if="!userStore.isViewer" label="操作" width="200" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click="openEdit(row)">编辑映射</el-button>
                  <el-button
                    v-if="row.mapping && row.mapping.status !== 'ACTIVE'"
                    size="small"
                    type="success"
                    plain
                    @click="doTransition(row.mapping)"
                  >生效</el-button>
                  <el-button
                    v-else-if="row.mapping"
                    size="small"
                    type="warning"
                    plain
                    @click="doTransition(row.mapping)"
                  >停用</el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="footer-bar" v-if="acceptedRows.length">
              <el-button type="success" :loading="saving" @click="saveAccepted">
                保存已采纳映射（{{ acceptedRows.length }}）
              </el-button>
            </div>
          </el-card>
          <el-card v-else>
            <el-empty description="请选择左侧物理表进行映射" />
          </el-card>
        </el-col>
      </el-row>
    </template>
    <el-card v-else style="margin-top: 16px">
      <el-empty description="请选择一个数据源" />
    </el-card>

    <!-- 编辑映射 -->
    <el-dialog v-model="editVisible" :title="`编辑映射：${editRow?.columnName || ''}`" width="480px">
      <el-form label-width="90px">
        <el-form-item label="物理列">
          <span>{{ editRow?.columnName }}（{{ editRow?.dataType }}）</span>
        </el-form-item>
        <el-form-item label="概念">
          <el-select
            v-model="editForm.conceptCode"
            style="width: 100%"
            filterable
            placeholder="选择概念"
            @change="onConceptChange"
          >
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="属性">
          <el-select
            v-model="editForm.attrCode"
            style="width: 100%"
            placeholder="选择属性"
            :disabled="!editForm.conceptCode"
          >
            <el-option
              v-for="a in attrOptions"
              :key="a.attrCode"
              :label="`${a.attrName}（${a.attrCode}）`"
              :value="a.attrCode"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
          v-if="editRow?.mapping"
          type="danger"
          plain
          :loading="clearing"
          @click="clearMapping"
        >清除映射</el-button>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!editForm.conceptCode || !editForm.attrCode"
          @click="saveEdit"
        >保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElLoading } from 'element-plus'
import {
  listDatasources,
  scanDatasource,
  listTables,
  listColumns,
  listMappings,
  saveMappings,
  aiSuggest,
  deleteMapping,
  transitionMapping,
  mappingLog
} from '../../api/datasource'
import { conceptDetail } from '../../api/ontology'
import { useConceptStore } from '../../store/concept'
import { useUserStore } from '../../store/user'

const conceptStore = useConceptStore()
const userStore = useUserStore()

// ---------- 数据源 ----------
const datasources = ref([])
const loadingDs = ref(false)
const currentDs = ref(null)
const scanningDs = ref('')

const loadDatasources = async () => {
  loadingDs.value = true
  try {
    datasources.value = await listDatasources()
  } finally {
    loadingDs.value = false
  }
}

const selectDs = (ds) => {
  currentDs.value = ds
  currentTable.value = ''
  loadTables()
}

const doScan = async (ds) => {
  scanningDs.value = ds.dsCode
  try {
    const res = await scanDatasource(ds.dsCode)
    ElMessage.success(`扫描完成：${res.dsCode} 共 ${res.tableCount} 张表`)
    if (currentDs.value?.dsCode === ds.dsCode) {
      loadTables()
    }
  } finally {
    scanningDs.value = ''
  }
}

// ---------- 物理表 ----------
const tables = ref([])
const loadingTables = ref(false)
const currentTable = ref('')

const loadTables = async () => {
  if (!currentDs.value) return
  loadingTables.value = true
  try {
    tables.value = await listTables(currentDs.value.dsCode)
  } finally {
    loadingTables.value = false
  }
}

const selectTable = (row) => {
  currentTable.value = row.tableName
  aiMeta.value = null
  suggestions.value = {}
  loadWorkbench()
}

// ---------- 映射工作台 ----------
const columns = ref([])
const mappings = ref([])
const suggestions = ref({})
const accepted = ref(new Set())
const loadingColumns = ref(false)
const aiLoading = ref(false)
const aiMeta = ref(null)
const saving = ref(false)

const rows = computed(() =>
  columns.value.map((col) => ({
    ...col,
    mapping: mappings.value.find((m) => m.columnName === col.columnName) || null,
    suggestion: suggestions.value[col.columnName] || null,
    accepted: accepted.value.has(col.columnName)
  }))
)

const acceptedRows = computed(() => rows.value.filter((r) => r.accepted && r.suggestion))

const loadWorkbench = async () => {
  if (!currentDs.value || !currentTable.value) return
  loadingColumns.value = true
  try {
    const [cols, maps] = await Promise.all([
      listColumns(currentDs.value.dsCode, currentTable.value),
      listMappings(currentDs.value.dsCode, currentTable.value)
    ])
    columns.value = cols
    mappings.value = maps
    accepted.value = new Set()
  } finally {
    loadingColumns.value = false
  }
}

// ---------- 映射生命周期（V30） ----------
const STATUS_META = {
  PROPOSED: { label: '待生效', type: 'warning' },
  ACTIVE: { label: '生效中', type: 'success' },
  DEPRECATED: { label: '已停用', type: 'info' }
}
const statusMeta = (s) => STATUS_META[s || 'ACTIVE'] || { label: s, type: 'info' }

const doTransition = async (m) => {
  const target = m.status === 'ACTIVE' ? 'DEPRECATED' : 'ACTIVE'
  await transitionMapping(m.id, target)
  ElMessage.success(target === 'ACTIVE' ? '映射已生效' : '映射已停用（可重新启用）')
  loadWorkbench()
}

// ---------- AI 推荐 ----------
const runAiSuggest = async () => {
  aiLoading.value = true
  const loadingInstance = ElLoading.service({
    text: 'AI 推理中，宽表可能需要数分钟，请稍候…',
    background: 'rgba(255, 255, 255, 0.7)'
  })
  try {
    const res = await aiSuggest(currentDs.value.dsCode, currentTable.value)
    aiMeta.value = { llmUsed: res.llmUsed, model: res.model }
    const map = {}
    for (const s of res.suggestions || []) {
      map[s.column] = s
    }
    suggestions.value = map
    accepted.value = new Set()
    const base = res.llmUsed
      ? `AI 推荐完成，共 ${res.suggestions?.length || 0} 条建议`
      : 'LLM 不可用，已按规则降级推荐'
    ElMessage.success(
      (res.failedBatches || 0) > 0 ? `${base}（${res.failedBatches} 批调用失败，已按规则补齐）` : base
    )
  } finally {
    loadingInstance.close()
    aiLoading.value = false
  }
}

const accept = (row) => {
  accepted.value = new Set([...accepted.value, row.columnName])
}

const saveAccepted = async () => {
  saving.value = true
  try {
    const payload = acceptedRows.value.map((r) => ({
      dsCode: currentDs.value.dsCode,
      tableName: currentTable.value,
      columnName: r.columnName,
      conceptCode: r.suggestion.conceptCode,
      attrCode: r.suggestion.attrCode,
      confirmed: 0,
      source: 'AI'
    }))
    await saveMappings(payload)
    ElMessage.success(`已保存 ${payload.length} 条提议映射（待建模员生效）`)
    suggestions.value = {}
    aiMeta.value = null
    loadWorkbench()
  } finally {
    saving.value = false
  }
}

// ---------- 手动编辑映射 ----------
const editVisible = ref(false)
const editRow = ref(null)
const editForm = reactive({ conceptCode: '', attrCode: '' })
const attrOptions = ref([])
const clearing = ref(false)

const onConceptChange = async (code) => {
  editForm.attrCode = ''
  attrOptions.value = []
  if (!code) return
  const detail = await conceptDetail(code)
  attrOptions.value = detail.attributes || []
}

const openEdit = async (row) => {
  editRow.value = row
  editForm.conceptCode = row.mapping?.conceptCode || ''
  editForm.attrCode = row.mapping?.attrCode || ''
  attrOptions.value = []
  editVisible.value = true
  if (editForm.conceptCode) {
    const detail = await conceptDetail(editForm.conceptCode)
    attrOptions.value = detail.attributes || []
  }
}

const saveEdit = async () => {
  saving.value = true
  try {
    await saveMappings([
      {
        dsCode: currentDs.value.dsCode,
        tableName: currentTable.value,
        columnName: editRow.value.columnName,
        conceptCode: editForm.conceptCode,
        attrCode: editForm.attrCode,
        confirmed: 1,
        source: 'MANUAL'
      }
    ])
    ElMessage.success('映射已保存')
    editVisible.value = false
    loadWorkbench()
  } finally {
    saving.value = false
  }
}

const clearMapping = async () => {
  clearing.value = true
  try {
    await deleteMapping(editRow.value.mapping.id)
    ElMessage.success('映射已清除')
    editVisible.value = false
    loadWorkbench()
  } finally {
    clearing.value = false
  }
}

onMounted(() => {
  loadDatasources()
  conceptStore.fetchAll()
})
</script>

<style scoped>
.ds-cards {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.ds-card {
  width: 260px;
  cursor: pointer;
  border: 1px solid #e4e7ed;
  position: relative;
}

.ds-card.active {
  border-color: #409eff;
  box-shadow: 0 0 0 1px #409eff inset;
}

.ds-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 8px;
}

.ds-meta {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}

.scan-btn {
  margin-top: 8px;
}

.footer-bar {
  margin-top: 12px;
  text-align: right;
}

.no-suggest {
  color: #c0c4cc;
}
</style>
