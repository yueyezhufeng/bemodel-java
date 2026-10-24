<template>
  <el-card>
    <div class="filter-bar">
      <el-select
        v-model="filterConcept"
        placeholder="按概念筛选"
        clearable
        filterable
        style="width: 240px"
        @change="loadActions"
      >
        <el-option
          v-for="c in conceptStore.concepts"
          :key="c.code"
          :label="`${c.name}（${c.code}）`"
          :value="c.code"
        />
      </el-select>
      <el-button type="primary" @click="openDialog('create')">新建动作</el-button>
    </div>
    <el-table :data="actions" v-loading="loading">
      <el-table-column prop="actionCode" label="动作编码" width="170" />
      <el-table-column prop="name" label="动作动词" width="120" />
      <el-table-column prop="conceptCode" label="作用概念" width="120" />
      <el-table-column label="状态跃迁" min-width="180">
        <template #default="{ row }">
          <span class="transition-text">{{ row.fromStatus || '∅（创建）' }} → {{ row.toStatus }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="triggerDesc" label="触发方" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="70">
        <template #default="{ row }">v{{ row.version }}</template>
      </el-table-column>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'DRAFT'"
            link type="primary" size="small"
            :loading="actingCode === row.actionCode"
            @click="doTransition(row, 'REVIEW')"
          >提交评审</el-button>
          <template v-if="row.status === 'REVIEW'">
            <el-button
              link type="success" size="small"
              :loading="actingCode === row.actionCode"
              @click="doTransition(row, 'PUBLISHED')"
            >发布</el-button>
            <el-button
              link type="warning" size="small"
              :loading="actingCode === row.actionCode"
              @click="doTransition(row, 'DRAFT')"
            >退回草稿</el-button>
          </template>
          <el-button
            v-if="row.status === 'PUBLISHED'"
            link type="danger" size="small"
            :loading="actingCode === row.actionCode"
            @click="doTransition(row, 'DEPRECATED')"
          >废弃</el-button>
          <el-button
            v-if="row.status === 'DEPRECATED'"
            link type="primary" size="small"
            :loading="actingCode === row.actionCode"
            @click="doTransition(row, 'DRAFT')"
          >重建为草稿</el-button>
          <el-button link size="small" @click="openDialog('edit', row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="removeAction(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建/编辑动作 -->
    <el-dialog v-model="dialogVisible" :title="dialogMode === 'create' ? '新建动作' : '编辑动作'" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="动作编码" required>
          <el-input v-model="form.actionCode" :disabled="dialogMode === 'edit'" placeholder="如 ACT-ORDER-CANCEL" />
        </el-form-item>
        <el-form-item label="动作动词" required>
          <el-input v-model="form.name" placeholder="如：申请采购取消" />
        </el-form-item>
        <el-form-item label="作用概念" required>
          <el-select v-model="form.conceptCode" filterable style="width: 100%">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="前置状态">
          <el-input v-model="form.fromStatus" placeholder="多个用逗号分隔，创建类动作留空" />
        </el-form-item>
        <el-form-item label="目标状态" required>
          <el-input v-model="form.toStatus" placeholder="如：已取消" />
        </el-form-item>
        <el-form-item label="触发方">
          <el-input v-model="form.triggerDesc" placeholder="如：医生站取消操作 / 系统自动" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listActions,
  createAction,
  updateAction,
  transitionAction,
  deleteAction
} from '../../../api/ontology'
import { statusText, statusTagType } from '../../../utils/dict'
import { useConceptStore } from '../../../store/concept'

const conceptStore = useConceptStore()

const actions = ref([])
const loading = ref(false)
const filterConcept = ref('')
const actingCode = ref('')

const loadActions = async () => {
  loading.value = true
  try {
    actions.value = await listActions(filterConcept.value || undefined)
  } finally {
    loading.value = false
  }
}

const doTransition = async (row, target) => {
  actingCode.value = row.actionCode
  try {
    await transitionAction(row.actionCode, target)
    ElMessage.success('状态流转成功')
    loadActions()
  } finally {
    actingCode.value = ''
  }
}

const dialogVisible = ref(false)
const dialogMode = ref('create')
const saving = ref(false)
const form = reactive({
  id: null,
  actionCode: '',
  name: '',
  conceptCode: '',
  fromStatus: '',
  toStatus: '',
  triggerDesc: '',
  description: ''
})

const openDialog = (mode, row) => {
  dialogMode.value = mode
  if (mode === 'create') {
    Object.assign(form, {
      id: null,
      actionCode: '',
      name: '',
      conceptCode: filterConcept.value || '',
      fromStatus: '',
      toStatus: '',
      triggerDesc: '',
      description: ''
    })
  } else {
    Object.assign(form, {
      id: row.id,
      actionCode: row.actionCode,
      name: row.name,
      conceptCode: row.conceptCode,
      fromStatus: row.fromStatus || '',
      toStatus: row.toStatus,
      triggerDesc: row.triggerDesc,
      description: row.description
    })
  }
  dialogVisible.value = true
}

const submit = async () => {
  if (!form.actionCode || !form.name || !form.conceptCode || !form.toStatus) {
    ElMessage.warning('请填写动作编码、动作动词、作用概念和目标状态')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, fromStatus: form.fromStatus || null }
    if (dialogMode.value === 'create') {
      await createAction(payload)
      ElMessage.success('动作创建成功（草稿）')
    } else {
      const row = actions.value.find((a) => a.id === form.id)
      await updateAction({ ...row, ...payload })
      ElMessage.success('动作已更新')
    }
    dialogVisible.value = false
    loadActions()
  } finally {
    saving.value = false
  }
}

const removeAction = async (row) => {
  await ElMessageBox.confirm(`确认删除动作「${row.name}」？`, '提示', { type: 'warning' })
  await deleteAction(row.id)
  ElMessage.success('已删除')
  loadActions()
}

onMounted(() => {
  conceptStore.fetchAll()
  loadActions()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.transition-text {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
}
</style>
