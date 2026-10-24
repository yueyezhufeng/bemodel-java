<template>
  <el-card>
    <el-alert
      type="info"
      :closable="false"
      title="公理定义工业对象与关系的形式化约束，为批次来源、质量放行与履约规则提供可追溯依据"
      style="margin-bottom: 12px"
    />
    <div class="filter-bar">
      <el-button type="primary" @click="openDialog">新建公理</el-button>
    </div>
    <el-table :data="axioms" v-loading="loading">
      <el-table-column prop="axiomCode" label="公理编码" width="110" />
      <el-table-column prop="subject" label="主语" min-width="150" show-overflow-tooltip />
      <el-table-column label="谓词" width="90">
        <template #default="{ row }">
          <b class="predicate">{{ row.predicate }}</b>
        </template>
      </el-table-column>
      <el-table-column prop="object" label="宾语" min-width="150" show-overflow-tooltip />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag size="small" class="ax-tag" :class="axiomTypeClass(row.axiomType)">
            {{ row.axiomType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" min-width="240" show-overflow-tooltip />
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button size="small" type="danger" link @click="removeAxiom(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建公理 -->
    <el-dialog v-model="dialogVisible" title="新建公理" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="公理编码" required>
          <el-input v-model="form.axiomCode" placeholder="如 AX-007" />
        </el-form-item>
        <el-form-item label="主语" required>
          <el-input v-model="form.subject" placeholder="如 批次来源核验" />
        </el-form-item>
        <el-form-item label="谓词" required>
          <el-select v-model="form.predicate" style="width: 100%">
            <el-option v-for="t in axiomTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="宾语" required>
          <el-input v-model="form.object" placeholder="如 授权放行" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.axiomType" style="width: 100%">
            <el-option v-for="t in axiomTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
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
import { listAxioms, createAxiom, deleteAxiom } from '../../../api/ontology'

const axiomTypes = ['依赖', '互斥', '支撑', '因果', '继承']

// 依赖蓝 / 互斥红 / 支撑绿 / 因果橙 / 继承紫（Element 无紫色 type，用自定义类）
const axiomTypeClass = (t) =>
  ({ 依赖: 'ax-dep', 互斥: 'ax-mutex', 支撑: 'ax-support', 因果: 'ax-causal', 继承: 'ax-inherit' }[t] ||
  '')

const axioms = ref([])
const loading = ref(false)

const loadAxioms = async () => {
  loading.value = true
  try {
    axioms.value = await listAxioms()
  } finally {
    loading.value = false
  }
}

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  axiomCode: '',
  subject: '',
  predicate: '依赖',
  object: '',
  axiomType: '依赖',
  description: ''
})

const openDialog = () => {
  Object.assign(form, {
    axiomCode: '',
    subject: '',
    predicate: '依赖',
    object: '',
    axiomType: '依赖',
    description: ''
  })
  dialogVisible.value = true
}

const submit = async () => {
  if (!form.axiomCode || !form.subject || !form.predicate || !form.object) {
    ElMessage.warning('请填写公理编码、主语、谓词和宾语')
    return
  }
  saving.value = true
  try {
    await createAxiom({ ...form })
    ElMessage.success('公理已保存')
    dialogVisible.value = false
    loadAxioms()
  } finally {
    saving.value = false
  }
}

const removeAxiom = async (row) => {
  await ElMessageBox.confirm(
    `确认删除公理「${row.axiomCode}：${row.subject} ${row.predicate} ${row.object}」？`,
    '提示',
    { type: 'warning' }
  )
  await deleteAxiom(row.id)
  ElMessage.success('已删除')
  loadAxioms()
}

onMounted(loadAxioms)
</script>

<style scoped>
.filter-bar {
  margin-bottom: 12px;
}

.predicate {
  font-weight: 700;
}

.ax-tag.ax-dep {
  color: #409eff;
  border-color: #b3d8ff;
  background: #ecf5ff;
}

.ax-tag.ax-mutex {
  color: #f56c6c;
  border-color: #fab6b6;
  background: #fef0f0;
}

.ax-tag.ax-support {
  color: #67c23a;
  border-color: #c2e7b0;
  background: #f0f9eb;
}

.ax-tag.ax-causal {
  color: #e6a23c;
  border-color: #f5dab1;
  background: #fdf6ec;
}

.ax-tag.ax-inherit {
  color: #722ed1;
  border-color: #d3adf7;
  background: #f9f0ff;
}
</style>
