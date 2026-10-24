<template>
  <el-card>
    <div class="filter-bar">
      <el-select v-model="conceptCode" filterable style="width: 260px" @change="load">
        <el-option
          v-for="c in availableConcepts"
          :key="c.code"
          :label="`${c.name}（${c.code}）`"
          :value="c.code"
        />
      </el-select>
    </div>
    <el-alert
      type="info"
      :closable="false"
      title="当前仅查询已配置的物理映射；未接入工业源时显示为空。演示实例请在工业本体全景连接 ForgePilot 快照。"
      style="margin-bottom: 12px"
    />
    <div v-loading="loading">
      <template v-if="data">
        <div class="inst-header">
          <b>{{ data.conceptName }}（{{ data.conceptCode }}）</b>
          <span class="inst-def">{{ data.definition }}</span>
          <el-tag size="small" effect="plain">{{ data.sourceCount }} 个来源库表</el-tag>
        </div>
        <el-card
          v-for="src in data.sources"
          :key="`${src.dsCode}.${src.tableName}`"
          class="source-card"
          shadow="never"
          v-loading="src.loading"
        >
          <template #header>
            <el-badge :value="`共 ${src.totalRows} 行`" type="primary">
              <span class="source-title">{{ src.dsCode }}.{{ src.tableName }}</span>
            </el-badge>
          </template>
          <el-table :data="src.instances" size="small" border max-height="420">
            <el-table-column
              v-for="col in Object.keys(src.instances[0] || {})"
              :key="col"
              :prop="col"
              :label="col"
              min-width="110"
              show-overflow-tooltip
            />
          </el-table>
          <el-empty v-if="!src.instances.length" description="无实例数据" :image-size="40" />
          <el-pagination
            v-if="src.totalRows > 0"
            class="source-pager"
            small
            layout="total, prev, pager, next"
            :current-page="sourcePages[srcKey(src)] || 1"
            :page-size="pageSize"
            :total="src.totalRows"
            @current-change="(p) => loadSourcePage(src, p)"
          />
        </el-card>
        <el-empty v-if="!data.sources.length" description="该概念暂无物理映射，无法反查实例" />
      </template>
    </div>
  </el-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listInstances } from '../../../api/ontology'
import { useConceptStore } from '../../../store/concept'

const conceptStore = useConceptStore()

const conceptCode = ref('')
const availableConcepts = computed(() => conceptStore.concepts.filter(c => c.status !== 'DEPRECATED'))
const pageSize = 20
const data = ref(null)
const loading = ref(false)
// 每个来源表独立翻页：key 为 dsCode.tableName
const sourcePages = ref({})

const srcKey = (src) => `${src.dsCode}.${src.tableName}`

const load = async () => {
  if (!conceptCode.value) return
  loading.value = true
  try {
    data.value = null
    const requested = conceptCode.value
    const result = await listInstances(requested, { pageNum: 1, pageSize })
    if (requested !== conceptCode.value) return
    data.value = result
    sourcePages.value = {}
  } finally {
    loading.value = false
  }
}

// 单个来源表翻页：带 tableName 调接口，只刷新该表数据
const loadSourcePage = async (src, page) => {
  src.loading = true
  try {
    const res = await listInstances(conceptCode.value, {
      tableName: src.tableName,
      pageNum: page,
      pageSize
    })
    const updated = (res.sources || []).find(
      (s) => s.dsCode === src.dsCode && s.tableName === src.tableName
    )
    if (updated) {
      src.instances = updated.instances
      src.totalRows = updated.totalRows
    }
    sourcePages.value = { ...sourcePages.value, [srcKey(src)]: page }
  } finally {
    src.loading = false
  }
}

onMounted(async () => {
  await conceptStore.fetchAll()
  conceptCode.value = availableConcepts.value[0]?.code || ''
  if (conceptCode.value) await load()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.inst-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.inst-def {
  font-size: 12px;
  color: #909399;
  flex: 1;
}

.source-card {
  margin-bottom: 16px;
}

.source-title {
  font-size: 14px;
  font-weight: 600;
  margin-right: 16px;
}

.source-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
