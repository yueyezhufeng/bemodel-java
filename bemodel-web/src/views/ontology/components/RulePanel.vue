<template>
  <el-card>
    <el-alert title="工业规则保存在本体契约中；状态发布不等于部署到 ForgePilot 执行器，实际业务仍遵循工作台的版本与权限守卫。" type="info" :closable="false"/>
    <div class="toolbar"><el-select v-model="concept" clearable filterable placeholder="按工业对象筛选" @change="load"><el-option v-for="c in concepts.concepts" :key="c.code" :label="`${c.name}（${c.code}）`" :value="c.code"/></el-select><el-input v-model="keyword" placeholder="搜索规则" clearable/><el-button v-if="canWrite" type="primary" @click="edit()">新建工业规则</el-button></div>
    <el-table :data="filtered" v-loading="loading" @row-click="row=>{detail=row}">
      <el-table-column prop="ruleCode" label="规则编码" width="120"/><el-table-column prop="name" label="名称" min-width="180"/><el-table-column prop="conceptCode" label="工业对象" width="160"/><el-table-column prop="severity" label="严重度" width="100"/><el-table-column label="状态" width="110"><template #default="{row}"><el-tag>{{ labels[row.status]||row.status }}</el-tag></template></el-table-column><el-table-column label="版本" width="80"><template #default="{row}">v{{ row.version }}</template></el-table-column>
      <el-table-column label="操作" min-width="230"><template #default="{row}"><el-button link @click.stop="detail=row">详情</el-button><el-button v-if="canWrite" link @click.stop="edit(row)">编辑</el-button><el-button v-for="next in canWrite?(transitions[row.status]||[]):[]" :key="next" link @click.stop="transition(row,next)">{{ labels[next] }}</el-button><el-button v-if="canWrite&&row.status==='DRAFT'" type="danger" link @click.stop="remove(row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog :model-value="!!detail" title="工业规则契约" width="650px" @close="detail=null"><template v-if="detail"><h3>{{ detail.name }}</h3><p>{{ detail.ruleCode }} · {{ detail.conceptCode }} · {{ detail.engine||'CONTRACT' }} · {{ detail.owner||'待指定责任人' }}</p><pre>{{ detail.expression||'规则约束尚未填写' }}</pre><p v-if="detail.metricCode">关联指标：{{ detail.metricCode }}</p><el-alert title="此处维护定义及版本，不执行库存、质量、采购或链上操作。" :closable="false" type="info"/></template></el-dialog>
    <el-dialog v-model="visible" :title="form.id?'编辑工业规则':'新建工业规则'" width="650px"><el-form label-position="top"><el-row :gutter="16"><el-col :span="12"><el-form-item label="规则编码"><el-input v-model="form.ruleCode" :disabled="!!form.id"/></el-form-item></el-col><el-col :span="12"><el-form-item label="名称"><el-input v-model="form.name"/></el-form-item></el-col></el-row><el-form-item label="工业对象"><el-select v-model="form.conceptCode" filterable><el-option v-for="c in concepts.concepts" :key="c.code" :label="`${c.name}（${c.code}）`" :value="c.code"/></el-select></el-form-item><el-row :gutter="16"><el-col :span="12"><el-form-item label="类型"><el-input v-model="form.ruleType"/></el-form-item></el-col><el-col :span="12"><el-form-item label="严重度"><el-select v-model="form.severity"><el-option v-for="s in ['HIGH','MEDIUM','LOW']" :key="s" :label="s" :value="s"/></el-select></el-form-item></el-col></el-row><el-form-item label="约束描述"><el-input v-model="form.expression" type="textarea" :rows="6" placeholder="描述适用对象、输入条件、版本与禁止条件"/></el-form-item><el-form-item label="关联指标"><el-select v-model="form.metricCode" clearable filterable><el-option v-for="m in metrics" :key="m.metricCode" :label="m.name" :value="m.metricCode"/></el-select></el-form-item><el-form-item label="责任人"><el-input v-model="form.owner"/></el-form-item></el-form><template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存契约</el-button></template></el-dialog>
  </el-card>
</template>
<script setup>
import {ref,reactive,computed,onMounted} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {listRules,createRule,updateRule,transitionRule,deleteRule} from '../../../api/ontology'
import {listMetrics} from '../../../api/glossary'
import {useConceptStore} from '../../../store/concept'
import {useUserStore} from '../../../store/user'
const concepts=useConceptStore(),user=useUserStore(),canWrite=computed(()=>['ADMIN','EDITOR'].includes(user.user?.role))
const rules=ref([]),metrics=ref([]),concept=ref(''),keyword=ref(''),loading=ref(false),saving=ref(false),visible=ref(false),detail=ref(null)
const labels={DRAFT:'草稿',REVIEW:'待审核',PUBLISHED:'已发布',DEPRECATED:'已停用'},transitions={DRAFT:['REVIEW'],REVIEW:['PUBLISHED','DRAFT'],PUBLISHED:['DEPRECATED'],DEPRECATED:[]}
const form=reactive({}),filtered=computed(()=>rules.value.filter(r=>!keyword.value||`${r.ruleCode} ${r.name} ${r.expression}`.toLowerCase().includes(keyword.value.toLowerCase())))
async function load(){loading.value=true;try{rules.value=await listRules(concept.value||undefined)}finally{loading.value=false}}
function edit(row){Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,row?{...row}:{ruleCode:'',name:'',conceptCode:concept.value,ruleType:'CONSTRAINT',severity:'HIGH',expression:'',owner:'',metricCode:null});form.engine='CONTRACT';visible.value=true}
async function save(){if(!form.ruleCode?.trim()||!form.name?.trim()||!form.conceptCode){ElMessage.warning('请填写规则编码、名称与工业对象');return}saving.value=true;try{const data={...form,engine:'CONTRACT',metricCode:form.metricCode||null};if(form.id)await updateRule(data);else await createRule(data);visible.value=false;await load();ElMessage.success('工业规则契约已保存')}finally{saving.value=false}}
async function transition(row,target){await ElMessageBox.confirm(`将规则 ${row.ruleCode} 标记为${labels[target]}？此操作只改变本体定义状态。`,'规则状态');await transitionRule(row.ruleCode,target);await load()}
async function remove(row){await ElMessageBox.confirm(`删除草稿 ${row.ruleCode}？`,'删除规则',{type:'warning'});await deleteRule(row.id);await load()}
onMounted(async()=>{await concepts.fetchAll();await Promise.all([load(),listMetrics().then(x=>{metrics.value=x})])})
</script>
<style scoped>.toolbar{display:flex;gap:12px;margin:18px 0}.toolbar .el-select{width:240px}.toolbar .el-input{max-width:260px}pre{white-space:pre-wrap;line-height:1.9;background:#f4f5ef;padding:18px}p{line-height:1.8;color:#65705e}</style>
