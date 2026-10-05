<template>
  <div class="page">
    <el-card>
      <el-tabs v-model="tab">
        <el-tab-pane label="制度文档" name="docs">
          <div class="filter-bar">
            <el-button v-if="!userStore.isViewer" type="primary" plain @click="openUploadDialog">
              上传文档
            </el-button>
            <span class="hint">上传后先解析切分，发布前可预览片段；发布需评审员审批。</span>
          </div>
          <el-table :data="docs" v-loading="loadingDocs">
            <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
            <el-table-column prop="docType" label="类型" width="80" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="70" />
            <el-table-column prop="chunkCount" label="片段数" width="80" />
            <el-table-column prop="uploadedBy" label="上传人" width="100" />
            <el-table-column label="操作" width="230">
              <template #default="{ row }">
                <el-button size="small" link type="primary" @click="previewChunks(row)">片段预览</el-button>
                <template v-if="!userStore.isViewer">
                  <el-button
                    v-for="a in nextActions(row.status)"
                    :key="a.target"
                    size="small"
                    link
                    :type="a.kind"
                    @click="doTransition(row, a.target, a.label)"
                  >{{ a.label }}</el-button>
                  <el-button
                    v-if="row.status === 'DRAFT' || row.status === 'REVIEW'"
                    size="small"
                    link
                    type="danger"
                    @click="removeDoc(row)"
                  >删除</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="知识条目" name="entries">
          <div class="filter-bar">
            <el-button v-if="!userStore.isViewer" type="primary" plain @click="openEntryDialog()">
              新增条目
            </el-button>
            <span class="hint">条目是结构化的经验文案：客服话术与核对归因按编码引用，改一条走一次审批。</span>
          </div>
          <el-table :data="entries" v-loading="loadingEntries">
            <el-table-column prop="code" label="编码" width="240" show-overflow-tooltip />
            <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
            <el-table-column prop="appliesTo" label="适用场景" width="180" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="70" />
            <el-table-column prop="owner" label="负责人" width="110" />
            <el-table-column label="操作" width="200">
              <template #default="{ row }">
                <el-button v-if="!userStore.isViewer" size="small" link type="primary"
                  @click="openEntryDialog(row)">编辑</el-button>
                <el-button
                  v-for="a in nextActions(row.status)"
                  :key="a.target"
                  size="small"
                  link
                  :type="a.kind"
                  @click="doTransitionEntry(row, a.target, a.label)"
                >{{ a.label }}</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
    <!-- 上传文档弹窗 -->
    <el-dialog v-model="uploadVisible" title="上传制度文档" width="520px">
      <el-upload
        v-model:file-list="fileList"
        drag
        :auto-upload="false"
        :limit="1"
        accept=".md,.pdf,.docx,.txt"
        :on-exceed="() => ElMessage.warning('一次选一个文件即可')"
      >
        <el-icon style="font-size: 40px; color: #909399"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽文件到这里，或<em>点击选择</em></div>
        <template #tip>
          <div class="el-upload__tip">支持 Markdown / PDF / Word / 文本，10MB 以内；重复内容会提示。</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传并解析</el-button>
      </template>
    </el-dialog>

    <!-- 切分预览弹窗 -->
    <el-dialog v-model="chunkVisible" :title="`片段预览：${chunkDocTitle}`" width="760px">
      <el-table :data="chunks" v-loading="loadingChunks" max-height="480">
        <el-table-column prop="seq" label="#" width="60" />
        <el-table-column prop="heading" label="所属标题" width="200" show-overflow-tooltip />
        <el-table-column prop="content" label="正文" show-overflow-tooltip />
      </el-table>
      <div v-if="chunks.length" class="hint" style="margin-top: 8px">
        共 {{ chunks.length }} 个片段；标注「未向量化」的片段在智能检索里只走关键词一路。
      </div>
    </el-dialog>

    <!-- 条目编辑弹窗 -->
    <el-dialog v-model="entryVisible" :title="entryForm.id ? '编辑知识条目' : '新增知识条目'" width="560px">
      <el-form :model="entryForm" label-width="80px">
        <el-form-item label="编码" required>
          <el-input v-model="entryForm.code" :disabled="!!entryForm.id" placeholder="如 CS_RULE_FEE_ROOT" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="entryForm.title" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="entryForm.content" type="textarea" :rows="6" />
        </el-form-item>
        <el-form-item label="适用场景">
          <el-input v-model="entryForm.appliesTo" placeholder="多个用英文逗号分隔，如 FEE,DISPENSE" />
        </el-form-item>
      </el-form>
      <div class="hint">已发布的条目再次保存会自动进入「待审核」，由评审员审批后生效。</div>
      <template #footer>
        <el-button @click="entryVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingEntry" @click="saveEntry">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { useUserStore } from '../../store/user'
import {
  listDocuments, uploadDocument, listChunks, transitionDocument, deleteDocument,
  listEntries, createEntry, updateEntry, transitionEntry,
} from '../../api/knowledge'

const userStore = useUserStore()
const tab = ref('docs')

// ---------- 文档 ----------
const docs = ref([])
const loadingDocs = ref(false)

const loadDocs = async () => {
  loadingDocs.value = true
  try {
    docs.value = await listDocuments()
  } finally {
    loadingDocs.value = false
  }
}

const uploadVisible = ref(false)
const fileList = ref([])
const uploading = ref(false)

const openUploadDialog = () => {
  fileList.value = []
  uploadVisible.value = true
}

const submitUpload = async () => {
  if (!fileList.value.length) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  try {
    await uploadDocument(fileList.value[0].raw)
    ElMessage.success('上传并解析完成，待审核')
    uploadVisible.value = false
    await loadDocs()
  } finally {
    uploading.value = false
  }
}

// ---------- 切分预览 ----------
const chunkVisible = ref(false)
const chunkDocTitle = ref('')
const chunks = ref([])
const loadingChunks = ref(false)

const previewChunks = async (row) => {
  chunkDocTitle.value = row.title
  chunkVisible.value = true
  loadingChunks.value = true
  try {
    chunks.value = await listChunks(row.id)
  } finally {
    loadingChunks.value = false
  }
}

// ---------- 状态流转 ----------
const STATUS_TEXT = {
  DRAFT: '草稿', REVIEW: '待审核', PUBLISHED: '已发布', DEPRECATED: '已停用',
}
const statusText = (s) => STATUS_TEXT[s] || s

const statusTagType = (s) => {
  if (s === 'PUBLISHED') return 'success'
  if (s === 'REVIEW') return 'warning'
  if (s === 'DEPRECATED') return 'info'
  return 'primary'
}

// 每个状态给出下一动作；发布/停用是评审决定，仅评审员/管理员可见（与后端门禁同口径，不再点了必败）
const REVIEW_ONLY = ['PUBLISHED', 'DEPRECATED']
const nextActions = (status) => {
  const all = []
  if (status === 'DRAFT') all.push({ target: 'REVIEW', label: '提交审核', kind: 'primary' })
  if (status === 'REVIEW') {
    all.push({ target: 'PUBLISHED', label: '发布', kind: 'success' })
    all.push({ target: 'DRAFT', label: '驳回', kind: 'warning' })
  }
  if (status === 'PUBLISHED') all.push({ target: 'DEPRECATED', label: '停用', kind: 'warning' })
  if (status === 'DEPRECATED') all.push({ target: 'DRAFT', label: '恢复草稿', kind: 'info' })
  return all.filter((a) => !REVIEW_ONLY.includes(a.target) || userStore.canReview)
}

const doTransition = async (row, target, label) => {
  await transitionDocument(row.id, target)
  ElMessage.success(`已${label}`)
  await loadDocs()
}

const removeDoc = (row) => {
  ElMessageBox.confirm(`确认删除「${row.title}」？`, '删除', { type: 'warning' })
    .then(async () => {
      await deleteDocument(row.id)
      ElMessage.success('已删除')
      await loadDocs()
    })
    .catch(() => {})
}

const doTransitionEntry = async (row, target, label) => {
  await transitionEntry(row.id, target)
  ElMessage.success(`已${label}`)
  await loadEntries()
}

// ---------- 知识条目 ----------
const entries = ref([])
const loadingEntries = ref(false)

const loadEntries = async () => {
  loadingEntries.value = true
  try {
    entries.value = await listEntries()
  } finally {
    loadingEntries.value = false
  }
}

const entryVisible = ref(false)
const savingEntry = ref(false)
const entryForm = ref({ id: null, code: '', title: '', content: '', appliesTo: '' })

const openEntryDialog = (row) => {
  entryForm.value = row
    ? { id: row.id, code: row.code, title: row.title, content: row.content, appliesTo: row.appliesTo }
    : { id: null, code: '', title: '', content: '', appliesTo: '' }
  entryVisible.value = true
}

const saveEntry = async () => {
  const f = entryForm.value
  if (!f.code || !f.title || !f.content) {
    ElMessage.warning('编码、标题、内容都要填')
    return
  }
  savingEntry.value = true
  try {
    if (f.id) {
      await updateEntry(f.id, { code: f.code, title: f.title, content: f.content, appliesTo: f.appliesTo })
    } else {
      await createEntry({ code: f.code, title: f.title, content: f.content, appliesTo: f.appliesTo })
    }
    ElMessage.success('已保存')
    entryVisible.value = false
    await loadEntries()
  } finally {
    savingEntry.value = false
  }
}

onMounted(() => {
  loadDocs()
  loadEntries()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.hint {
  color: #909399;
  font-size: 12px;
}
</style>
