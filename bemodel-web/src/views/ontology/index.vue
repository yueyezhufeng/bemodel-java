<template>
  <div class="page">
    <el-tabs v-model="activeTab" class="workbench-tabs">
      <!-- ============ 概念建模 ============ -->
      <el-tab-pane label="概念建模" name="modeling">
        <el-row :gutter="16">
          <el-col :span="6">
            <el-card>
              <template #header>
                <div class="card-header">
                  <span>业务域</span>
                  <el-tooltip content="只读角色无写权限" :disabled="!userStore.isViewer" placement="top">
                    <span>
                      <el-button size="small" :disabled="userStore.isViewer" @click="openDomainDialog">新建域</el-button>
                    </span>
                  </el-tooltip>
                </div>
              </template>
              <el-tree
                ref="domainTreeRef"
                :data="domainTree"
                :props="{ label: 'name', children: 'children' }"
                node-key="code"
                default-expand-all
                highlight-current
                :expand-on-click-node="false"
                @node-click="onDomainClick"
              />
            </el-card>
          </el-col>
          <el-col :span="18">
            <el-card>
              <template #header>
                <div class="card-header">
                  <span>概念列表{{ selectedDomain ? `（${selectedDomain}）` : '' }}</span>
                  <div>
                    <el-badge
                      :value="missData.pendingCount"
                      :hidden="!missData.pendingCount"
                      class="proposal-badge"
                    >
                      <el-button @click="openProposalDrawer">扩展提案</el-button>
                    </el-badge>
                    <template v-if="!userStore.isViewer">
                      <el-button @click="openImportDialog">导入 OWL</el-button>
                      <el-button type="primary" @click="openConceptDialog('create')">新建概念</el-button>
                    </template>
                    <el-tooltip v-else content="只读角色无写权限" placement="top">
                      <span>
                        <el-button type="primary" disabled>新建概念</el-button>
                      </span>
                    </el-tooltip>
                  </div>
                </div>
              </template>
              <el-table
                :data="pagedConcepts"
                v-loading="loadingConcepts"
                highlight-current-row
                @row-click="openDetail"
              >
                <el-table-column prop="code" label="编码" width="160" />
                <el-table-column prop="name" label="名称" width="130" />
                <el-table-column prop="owner" label="负责人" width="100" />
                <el-table-column label="状态" width="100">
                  <template #default="{ row }">
                    <el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="version" label="版本" width="70" />
                <el-table-column prop="definition" label="定义" show-overflow-tooltip />
              </el-table>
              <el-pagination
                v-if="concepts.length > 0"
                class="concept-pager"
                small
                layout="total, prev, pager, next"
                v-model:current-page="conceptPage"
                :page-size="conceptPageSize"
                :total="concepts.length"
              />
            </el-card>
          </el-col>
        </el-row>

        <!-- 概念详情抽屉 -->
        <el-drawer
          v-model="drawerVisible"
          :title="`概念详情：${detail.concept?.name || ''}（${detail.concept?.code || ''}）`"
          size="55%"
        >
          <el-tabs v-model="activeInnerTab">
            <el-tab-pane label="属性" name="attrs">
              <div class="pane-toolbar" v-if="!userStore.isViewer">
                <el-button size="small" type="primary" @click="openAttrDialog">新增属性</el-button>
              </div>
              <el-table :data="detail.attributes" size="small">
                <el-table-column prop="attrCode" label="属性编码" width="130" />
                <el-table-column prop="attrName" label="属性名称" width="120" />
                <el-table-column prop="dataType" label="类型" width="90" />
                <el-table-column label="主键" width="70">
                  <template #default="{ row }">
                    <el-tag v-if="row.isKey === 1" type="danger" size="small">主键</el-tag>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
                <el-table-column prop="definition" label="定义" show-overflow-tooltip />
                <el-table-column label="操作" width="70">
                  <template #default="{ row }">
                    <el-button v-if="!userStore.isViewer" size="small" type="danger" link @click="removeAttribute(row)">
                      删除
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="关系" name="relations">
              <div class="pane-toolbar" v-if="!userStore.isViewer">
                <el-button size="small" type="primary" @click="openRelationDialog('create')">新建关系</el-button>
              </div>
              <el-table :data="detail.relations" size="small">
                <el-table-column label="关系" min-width="240">
                  <template #default="{ row }">
                    <span>
                      <el-tag size="small" effect="plain">{{ row.fromConcept }}</el-tag>
                      <span class="relation-arrow">— {{ row.relationName }} →</span>
                      <el-tag size="small" effect="plain" type="success">{{ row.toConcept }}</el-tag>
                    </span>
                  </template>
                </el-table-column>
                <el-table-column prop="description" label="说明" show-overflow-tooltip />
                <el-table-column label="操作" width="110">
                  <template #default="{ row }">
                    <el-button v-if="!userStore.isViewer" size="small" link type="primary" @click="openRelationDialog('edit', row)">
                      编辑
                    </el-button>
                    <el-button v-if="!userStore.isViewer" size="small" type="danger" link @click="removeRelation(row)">
                      删除
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!detail.relations.length" description="暂无关系" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane label="继承" name="inherit">
              <div class="pane-toolbar" v-if="!userStore.isViewer">
                <el-button size="small" type="primary" @click="openParentDialog">添加父概念</el-button>
                <span class="inherit-tip">多父继承用于「属于」类层级建模；主父为默认归属</span>
              </div>
              <el-table :data="detail.parents || []" size="small">
                <el-table-column label="父概念" min-width="200">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain">{{ row.parentCode }}</el-tag>
                    <span class="parent-name">{{ conceptNameOf(row.parentCode) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="主父" width="80" align="center">
                  <template #default="{ row }">
                    <el-tag v-if="row.isPrimary === 1" size="small" type="warning" effect="dark">
                      ★ 主父
                    </el-tag>
                    <span v-else>-</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="130">
                  <template #default="{ row }">
                    <el-button
                      v-if="row.isPrimary !== 1 && !userStore.isViewer"
                      size="small"
                      link
                      type="primary"
                      @click="doSetPrimaryParent(row)"
                    >设为主父</el-button>
                    <el-button v-if="!userStore.isViewer" size="small" type="danger" link @click="doRemoveParent(row)">
                      删除
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!(detail.parents || []).length" description="暂无父概念" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane label="图" name="graph">
              <div v-if="graphNodes.length" class="graph-pane">
                <GraphCanvas
                  :nodes="graphNodes"
                  :edges="graphEdges"
                  :categories="graphCategories"
                  layout="force"
                  height="460px"
                  :loading="loadingGraph"
                  :tooltip-formatter="graphTooltip"
                  @node-click="onGraphNodeClick"
                />
                <div class="graph-legend-tip">
                  <span class="gl-item"><i class="gl-dot gl-center" />当前概念</span>
                  <span class="gl-item"><i class="gl-dot gl-neighbor" />相邻概念</span>
                  <span class="gl-item"><i class="gl-line gl-relation" />关系（绿实线）</span>
                  <span class="gl-item"><i class="gl-line gl-transitive" />可传递（橙色虚线）</span>
                  <span class="gl-item"><i class="gl-arrow">⇄</i>对称（双向箭头）</span>
                  <span class="gl-item">· 尾缀＝约束公理，悬停关系边看含义</span>
                  <span class="gl-item"><i class="gl-line gl-disjoint" />互斥（红色虚线）</span>
                  <span class="gl-item">点击相邻节点切换概念</span>
                </div>
              </div>
              <el-empty v-else description="暂无相邻关系或互斥公理" :image-size="60" />

              <el-divider content-position="left">传递闭包</el-divider>
              <div class="closure-tool">
                <el-select
                  v-model="closureRelation"
                  placeholder="选择传递关系"
                  style="width: 220px"
                  size="small"
                >
                  <el-option
                    v-for="name in transitiveRelations"
                    :key="name"
                    :label="name"
                    :value="name"
                  />
                </el-select>
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :disabled="!closureRelation"
                  :loading="loadingClosure"
                  @click="runClosure"
                >查询可达概念</el-button>
                <span v-if="!transitiveRelations.length" class="closure-tip">
                  当前概念没有声明「传递」公理的关系
                </span>
              </div>
              <template v-if="closureResult">
                <div v-if="closureResult.count > 0" class="closure-result">
                  <div
                    v-for="depth in Object.keys(closureLayers).sort()"
                    :key="depth"
                    class="closure-layer"
                  >
                    <span class="closure-depth">第 {{ depth }} 跳</span>
                    <el-tag
                      v-for="c in closureLayers[depth]"
                      :key="c"
                      size="small"
                      effect="plain"
                      class="closure-tag"
                    >{{ conceptNameOf(c) }}（{{ c }}）</el-tag>
                  </div>
                </div>
                <el-alert
                  v-else
                  type="info"
                  :closable="false"
                  title="该概念在此关系上无传递可达"
                />
              </template>
            </el-tab-pane>
            <el-tab-pane label="互斥" name="disjoint">
              <div class="pane-toolbar" v-if="!userStore.isViewer">
                <el-button size="small" type="primary" @click="openDisjointDialog">新增互斥</el-button>
              </div>
              <el-table :data="disjointPairs" v-loading="loadingDisjoint" size="small">
                <el-table-column label="互斥概念对" min-width="220">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain">{{ row.conceptACode }}</el-tag>
                    <span class="disjoint-cross">✕</span>
                    <el-tag size="small" effect="plain" type="danger">{{ row.conceptBCode }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="definition" label="理由" show-overflow-tooltip />
                <el-table-column label="操作" width="70">
                  <template #default="{ row }">
                    <el-button v-if="!userStore.isViewer" size="small" type="danger" link @click="removeDisjoint(row)">
                      删除
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!disjointPairs.length" description="暂无互斥公理" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane label="术语" name="terms">
              <div class="term-list">
                <el-tag v-for="t in detail.terms" :key="t.id" class="term-tag" type="info">
                  {{ t.term }}（{{ t.sourceProduct }}）
                </el-tag>
              </div>
              <el-empty v-if="!detail.terms.length" description="暂无术语" :image-size="60" />
            </el-tab-pane>
            <el-tab-pane label="操作" name="ops">
              <div class="ops-pane">
                <p>
                  当前状态：
                  <el-tag :type="statusTagType(detail.concept?.status)">
                    {{ statusText(detail.concept?.status) }}
                  </el-tag>
                  <span class="version-text">版本 v{{ detail.concept?.version }}</span>
                </p>
                <p class="iri-line">
                  IRI：
                  <span>{{ detail.concept?.iri || '未设置' }}</span>
                </p>
                <template v-if="!userStore.isViewer">
                  <el-button-group>
                  <el-button
                    :disabled="!canTransition('REVIEW')"
                    :loading="transitioning"
                    @click="doTransition('REVIEW')"
                  >提交评审</el-button>
                  <el-button
                    v-if="userStore.canReview"
                    type="success"
                    :disabled="!canTransition('PUBLISHED')"
                    :loading="transitioning"
                    @click="doTransition('PUBLISHED')"
                  >发布</el-button>
                  <el-button
                    type="warning"
                    :disabled="!canTransition('DRAFT')"
                    :loading="transitioning"
                    @click="doTransition('DRAFT')"
                  >退回草稿</el-button>
                  <el-button
                    v-if="userStore.canReview"
                    type="danger"
                    :disabled="!canTransition('DEPRECATED')"
                    :loading="transitioning"
                    @click="doTransition('DEPRECATED')"
                  >废弃</el-button>
                </el-button-group>
                  <el-divider />
                  <el-button @click="openConceptDialog('edit')">编辑概念信息</el-button>
                  <el-tooltip
                    content="仅草稿状态可删除"
                    :disabled="detail.concept?.status === 'DRAFT'"
                    placement="top"
                  >
                    <span>
                      <el-button
                        type="danger"
                        plain
                        :disabled="detail.concept?.status !== 'DRAFT'"
                        :loading="deletingConcept"
                        @click="removeConcept"
                      >删除概念</el-button>
                    </span>
                  </el-tooltip>
                </template>
                <el-alert
                  v-else
                  type="info"
                  :closable="false"
                  title="只读角色无写权限"
                  style="margin-top: 8px"
                />
              </div>
            </el-tab-pane>
          </el-tabs>
        </el-drawer>

        <!-- 新建/编辑概念 -->
        <el-dialog
          v-model="conceptDialogVisible"
          :title="conceptDialogMode === 'create' ? '新建概念' : '编辑概念'"
          width="520px"
        >
          <el-form :model="conceptForm" label-width="90px">
            <el-form-item label="编码" required>
              <el-input
                v-model="conceptForm.code"
                placeholder="如 FEE_DETAIL"
                :disabled="conceptDialogMode === 'edit'"
              />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="conceptForm.name" />
            </el-form-item>
            <el-form-item label="所属域" required>
              <el-select v-model="conceptForm.domainCode" style="width: 100%">
                <el-option
                  v-for="d in domains"
                  :key="d.code"
                  :label="`${d.name}（${d.code}）`"
                  :value="d.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="定义">
              <el-input v-model="conceptForm.definition" type="textarea" :rows="3" />
            </el-form-item>
            <el-form-item label="负责人">
              <!-- 编辑态禁用：服务端白名单已剥离 owner（负责人移交是独立动作，本版不开放），
                   保留可编辑输入会造成「提示成功但永不生效」的静默丢弃 -->
              <el-input v-model="conceptForm.owner" :disabled="conceptDialogMode === 'edit'" />
            </el-form-item>
            <el-form-item label="IRI">
              <el-input
                v-model="conceptForm.iri"
                placeholder="如 https://example.com/onto#FeeDetail（可选）"
              />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="conceptDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingConcept" @click="submitConcept">保存</el-button>
          </template>
        </el-dialog>

        <!-- 新建业务域 -->
        <el-dialog v-model="domainDialogVisible" title="新建业务域" width="480px">
          <el-form :model="domainForm" label-width="90px">
            <el-form-item label="编码" required>
              <el-input v-model="domainForm.code" placeholder="如 FEE" />
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="domainForm.name" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="domainForm.description" type="textarea" :rows="2" />
            </el-form-item>
            <el-form-item label="排序">
              <el-input-number v-model="domainForm.sort" :min="1" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="domainDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingDomain" @click="submitDomain">保存</el-button>
          </template>
        </el-dialog>

        <!-- 新增属性（抽屉内弹窗，需 append-to-body） -->
        <el-dialog v-model="attrDialogVisible" title="新增属性" width="480px" append-to-body>
          <el-form :model="attrForm" label-width="90px">
            <el-form-item label="属性编码" required>
              <el-input v-model="attrForm.attrCode" placeholder="如 fee_id" />
            </el-form-item>
            <el-form-item label="属性名称" required>
              <el-input v-model="attrForm.attrName" placeholder="如 费用流水号" />
            </el-form-item>
            <el-form-item label="数据类型">
              <el-select v-model="attrForm.dataType" style="width: 100%">
                <el-option v-for="t in ['STRING', 'NUMBER', 'DATE', 'ENUM']" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-form-item label="是否主键">
              <el-switch v-model="attrForm.isKey" :active-value="1" :inactive-value="0" />
            </el-form-item>
            <el-form-item label="业务含义">
              <el-input v-model="attrForm.definition" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="attrDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingAttr" @click="submitAttribute">保存</el-button>
          </template>
        </el-dialog>

        <!-- 新建/编辑关系（抽屉内弹窗，需 append-to-body） -->
        <el-dialog
          v-model="relationDialogVisible"
          :title="relationDialogMode === 'create' ? '新建关系' : '编辑关系'"
          width="560px"
          append-to-body
        >
          <el-form :model="relationForm" label-width="90px">
            <el-form-item label="起点概念" required>
              <el-select v-model="relationForm.fromConcept" filterable style="width: 100%">
                <el-option
                  v-for="c in conceptStore.concepts"
                  :key="c.code"
                  :label="`${c.name}（${c.code}）`"
                  :value="c.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="终点概念" required>
              <el-select v-model="relationForm.toConcept" filterable style="width: 100%">
                <el-option
                  v-for="c in conceptStore.concepts"
                  :key="c.code"
                  :label="`${c.name}（${c.code}）`"
                  :value="c.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="关系名" required>
              <el-input v-model="relationForm.relationName" placeholder="动词，如：产生/生成/结算" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="relationForm.description" />
            </el-form-item>
            <el-divider content-position="left">公理</el-divider>
            <el-form-item label="性质">
              <el-checkbox v-model="relationForm.isSymmetric" :true-value="1" :false-value="0">
                对称
              </el-checkbox>
              <el-checkbox v-model="relationForm.isTransitive" :true-value="1" :false-value="0">
                传递
              </el-checkbox>
              <el-checkbox v-model="relationForm.isFunctional" :true-value="1" :false-value="0">
                函数
              </el-checkbox>
              <el-checkbox
                v-model="relationForm.isInverseFunctional"
                :true-value="1"
                :false-value="0"
              >
                反函数
              </el-checkbox>
              <el-checkbox v-model="relationForm.isAsymmetric" :true-value="1" :false-value="0">
                反对称
              </el-checkbox>
            </el-form-item>
            <el-form-item label="互逆关系">
              <el-select
                v-model="relationForm.inverseOf"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="选择或输入互逆关系名"
                style="width: 100%"
              >
                <el-option
                  v-for="name in relationNameOptions"
                  :key="name"
                  :label="name"
                  :value="name"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="IRI">
              <el-input
                v-model="relationForm.iri"
                placeholder="如 https://example.com/onto#produces（可选）"
              />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="relationDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingRelation" @click="submitRelation">保存</el-button>
          </template>
        </el-dialog>

        <!-- 添加父概念（抽屉内弹窗，需 append-to-body） -->
        <el-dialog v-model="parentDialogVisible" title="添加父概念" width="480px" append-to-body>
          <el-form :model="parentForm" label-width="90px">
            <el-form-item label="父概念" required>
              <el-select v-model="parentForm.parentCode" filterable style="width: 100%">
                <el-option
                  v-for="c in parentCandidates"
                  :key="c.code"
                  :label="`${c.name}（${c.code}）`"
                  :value="c.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="设为主父">
              <el-switch v-model="parentForm.isPrimary" :active-value="1" :inactive-value="0" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="parentDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingParent" @click="submitParent">保存</el-button>
          </template>
        </el-dialog>

        <!-- 新增互斥公理（抽屉内弹窗，需 append-to-body） -->
        <el-dialog v-model="disjointDialogVisible" title="新增互斥公理" width="520px" append-to-body>
          <el-form :model="disjointForm" label-width="90px">
            <el-form-item label="概念 A" required>
              <el-select v-model="disjointForm.conceptACode" filterable style="width: 100%">
                <el-option
                  v-for="c in conceptStore.concepts"
                  :key="c.code"
                  :label="`${c.name}（${c.code}）`"
                  :value="c.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="概念 B" required>
              <el-select v-model="disjointForm.conceptBCode" filterable style="width: 100%">
                <el-option
                  v-for="c in conceptStore.concepts"
                  :key="c.code"
                  :label="`${c.name}（${c.code}）`"
                  :value="c.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="理由">
              <el-input
                v-model="disjointForm.definition"
                type="textarea"
                :rows="3"
                placeholder="如：男性患者不允许出现妊娠类诊断"
              />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="disjointDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingDisjoint" @click="submitDisjoint">保存</el-button>
          </template>
        </el-dialog>

        <!-- 导入 OWL -->
        <el-dialog v-model="importVisible" title="导入 OWL 本体" width="780px">
          <el-upload
            v-model:file-list="importFileList"
            drag
            :auto-upload="false"
            :limit="1"
            accept=".owl,.rdf,.ttl"
          >
            <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
            <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em>（.owl / .rdf / .ttl）</div>
          </el-upload>
          <div class="import-actions">
            <el-button
              type="primary"
              plain
              :disabled="!importFileList.length"
              :loading="previewing"
              @click="doImportPreview"
            >解析预览</el-button>
          </div>
          <template v-if="importPreview">
            <div class="import-summary">
              <el-tag type="success" effect="plain">新增 {{ importPreview.summary?.create ?? 0 }}</el-tag>
              <el-tag type="primary" effect="plain">更新 {{ importPreview.summary?.update ?? 0 }}</el-tag>
              <el-tag type="danger" effect="plain">主键占用 {{ importPreview.summary?.keyTaken ?? 0 }}</el-tag>
              <el-tag type="warning" effect="plain">降级 {{ importPreview.summary?.degraded ?? 0 }}</el-tag>
            </div>
            <el-alert
              v-if="(importPreview.unprojected ?? 0) > 0"
              type="warning"
              :closable="false"
              class="import-unprojected"
              :title="`有 ${importPreview.unprojected} 项未投影要素（disjointWith / Restriction / 匿名节点等）将被跳过`"
            />
            <el-table :data="importPreview.items" size="small" max-height="320">
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ importKindText(row.kind) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="code" label="编码" width="150" show-overflow-tooltip />
              <el-table-column prop="name" label="名称" width="120" show-overflow-tooltip />
              <el-table-column label="处置" width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="dispositionTagType(row.disposition)">
                    {{ dispositionText(row.disposition) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reason" label="原因 / 详情" min-width="180" show-overflow-tooltip>
                <template #default="{ row }">
                  {{ importItemDetail(row) }}
                </template>
              </el-table-column>
            </el-table>
          </template>
          <template #footer>
            <el-button @click="importVisible = false">取消</el-button>
            <el-button
              type="primary"
              :disabled="!importPreview"
              :loading="importing"
              @click="doImportExecute"
            >确认导入</el-button>
          </template>
        </el-dialog>

        <!-- 扩展提案（本体增长回路） -->
        <el-drawer v-model="proposalVisible" title="扩展提案" size="700px">
          <el-alert
            type="info"
            class="workflow-alert"
            title="平台把搜索未命中、映射失败、客服问答答不了的说法收集在这里 → 点击「AI 归类」获得建议 → 人工确认后创建为草稿概念 → 走正常发布流程生效"
          />
          <el-tabs v-model="missTab" v-loading="loadingMisses">
            <el-tab-pane :label="`待处理（${missData.pendingCount}）`" name="pending">
              <el-table :data="pendingMisses" size="small">
                <el-table-column prop="term" label="原文" min-width="140" show-overflow-tooltip />
                <el-table-column label="类型" width="70">
                  <template #default="{ row }">
                    <el-tag size="small" :type="missKindTag(row.kind)">{{ missKindText(row.kind) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="来源" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain" :type="missSourceTag(row.source)">
                      {{ missSourceText(row.source) }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="次数" width="70" align="center">
                  <template #default="{ row }">
                    <span class="miss-count" :class="{ hot: row.count >= 3 }">{{ row.count }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="最近出现" width="150">
                  <template #default="{ row }">{{ formatMissTime(row.lastSeen) }}</template>
                </el-table-column>
                <el-table-column v-if="!userStore.isViewer" label="操作" width="170" fixed="right">
                  <template #default="{ row }">
                    <el-button
                      size="small"
                      type="warning"
                      link
                      :loading="classifyingId === row.id"
                      @click="doClassify(row)"
                    >
                      AI 归类
                    </el-button>
                    <el-dropdown trigger="click" @command="(cmd) => onMissAction(cmd, row)">
                      <el-button size="small" type="primary" link>
                        处置<el-icon><ArrowDown /></el-icon>
                      </el-button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="adopt">采纳为新概念</el-dropdown-item>
                          <el-dropdown-item command="term">挂为术语</el-dropdown-item>
                          <el-dropdown-item command="ignore">忽略</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty
                v-if="!pendingMisses.length"
                description="本体暂时没有新的生长信号"
                :image-size="80"
              />
            </el-tab-pane>
            <el-tab-pane :label="`已忽略（${dismissedMisses.length}）`" name="dismissed">
              <el-table :data="dismissedMisses" size="small" row-class-name="miss-row-muted">
                <el-table-column prop="term" label="原文" min-width="140" show-overflow-tooltip />
                <el-table-column label="类型" width="70">
                  <template #default="{ row }">
                    <el-tag size="small" type="info" effect="plain">{{ missKindText(row.kind) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="dismissReason" label="忽略理由" min-width="120" show-overflow-tooltip />
                <el-table-column label="次数" width="70" align="center">
                  <template #default="{ row }">
                    <span class="miss-count" :class="{ hot: row.count >= 3 }">{{ row.count }}</span>
                  </template>
                </el-table-column>
                <el-table-column v-if="!userStore.isViewer" label="操作" width="90" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" type="primary" link @click="doUndismiss(row)">
                      撤销忽略
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!dismissedMisses.length" description="暂无已忽略项" :image-size="80" />
            </el-tab-pane>
            <el-tab-pane :label="`已采纳（${adoptedMisses.length}）`" name="adopted">
              <el-table :data="adoptedMisses" size="small">
                <el-table-column prop="term" label="原文" min-width="140" show-overflow-tooltip />
                <el-table-column label="处置形态" width="180">
                  <template #default="{ row }">
                    <el-tag v-if="row.adoptedAs === 'TERM'" size="small" type="primary" effect="plain">
                      术语 → {{ row.adoptedConceptCode }}
                    </el-tag>
                    <el-tag v-else size="small" type="success" effect="plain">
                      新概念 {{ row.adoptedConceptCode }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="次数" width="70" align="center">
                  <template #default="{ row }">{{ row.count }}</template>
                </el-table-column>
                <el-table-column label="最近出现" width="150">
                  <template #default="{ row }">{{ formatMissTime(row.lastSeen) }}</template>
                </el-table-column>
                <el-table-column v-if="!userStore.isViewer" label="操作" width="90" fixed="right">
                  <template #default="{ row }">
                    <el-button size="small" type="danger" link @click="doRevoke(row)">
                      撤销采纳
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!adoptedMisses.length" description="暂无已采纳项" :image-size="80" />
            </el-tab-pane>
          </el-tabs>
        </el-drawer>

        <!-- 采纳 miss（抽屉内弹窗，需 append-to-body） -->
        <el-dialog v-model="adoptDialogVisible" title="采纳为新概念" width="520px" append-to-body>
          <el-alert
            v-if="adoptSuggestion"
            :type="adoptSuggestion.degraded ? 'warning' : 'success'"
            :closable="false"
            :title="adoptSuggestion.degraded ? adoptSuggestion.reason : `AI 建议理由：${adoptSuggestion.reason}`"
            style="margin-bottom: 12px"
          />
          <el-alert
            v-else
            type="info"
            :closable="false"
            :title="`将「${adoptForm.term}」创建为草稿概念，并关闭该 miss`"
            style="margin-bottom: 12px"
          />
          <el-form :model="adoptForm" label-width="90px">
            <el-form-item label="编码" required>
              <el-input v-model="adoptForm.code" placeholder="大写蛇形，如 CREUTZFELDT_JAKOB" />
              <div v-if="codeTakenHint" class="form-hint form-hint-error">该编码已被占用，请修改</div>
              <div v-else class="form-hint">大写字母开头，仅含大写字母 / 数字 / 下划线</div>
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="adoptForm.name" />
            </el-form-item>
            <el-form-item label="所属域" required>
              <el-select v-model="adoptForm.domainCode" filterable style="width: 100%">
                <el-option
                  v-for="d in domains"
                  :key="d.code"
                  :label="`${d.name}（${d.code}）`"
                  :value="d.code"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="定义">
              <el-input v-model="adoptForm.definition" type="textarea" :rows="3" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="adoptDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="adopting" @click="submitAdopt">创建草稿概念</el-button>
          </template>
        </el-dialog>

        <!-- 忽略 miss（抽屉内弹窗，需 append-to-body） -->
        <el-dialog v-model="ignoreDialogVisible" title="忽略该信号" width="480px" append-to-body>
          <el-form :model="ignoreForm" label-width="90px">
            <el-form-item label="原文">
              <span>{{ ignoreForm.term }}</span>
            </el-form-item>
            <el-form-item label="忽略理由" required>
              <el-input
                v-model="ignoreForm.reason"
                type="textarea"
                :rows="3"
                placeholder="必填，如：技术字段 / 罕见病，暂不纳入"
              />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="ignoreDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="ignoring" @click="submitIgnore">确认忽略</el-button>
          </template>
        </el-dialog>

        <!-- 挂为术语（抽屉内弹窗，需 append-to-body） -->
        <el-dialog v-model="termDialogVisible" title="挂为术语" width="480px" append-to-body>
          <el-alert
            type="info"
            :closable="false"
            :title="`将「${termForm.term}」登记为该概念的方言术语，后续搜索与质控判定会命中它`"
            style="margin-bottom: 12px"
          />
          <el-form :model="termForm" label-width="90px">
            <el-form-item label="目标概念" required>
              <el-select v-model="termForm.conceptCode" filterable style="width: 100%">
                <el-option
                  v-for="c in conceptStore.concepts"
                  :key="c.code"
                  :label="`${c.name}（${c.code}）`"
                  :value="c.code"
                />
              </el-select>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="termDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="savingTerm" @click="submitTerm">确认登记</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>

      <!-- ============ 规则 ============ -->
      <el-tab-pane label="规则" name="rules" lazy>
        <RulePanel />
      </el-tab-pane>

      <!-- ============ 动作 ============ -->
      <el-tab-pane label="动作" name="actions" lazy>
        <ActionPanel />
      </el-tab-pane>

      <!-- ============ 实例浏览 ============ -->
      <el-tab-pane label="实例浏览" name="instances" lazy>
        <InstancePanel />
      </el-tab-pane>

      <!-- ============ 版本与LLM ============ -->
      <el-tab-pane label="版本与LLM" name="releases" lazy>
        <ReleasePanel />
      </el-tab-pane>

      <!-- ============ 公理 ============ -->
      <el-tab-pane label="公理" name="axioms" lazy>
        <AxiomPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled, ArrowDown } from '@element-plus/icons-vue'
import {
  listDomains,
  createDomain,
  listConcepts,
  conceptDetail,
  createConcept,
  updateConcept,
  transitionConcept,
  createAttribute,
  deleteAttribute,
  createRelation,
  updateRelation,
  deleteRelation,
  listDisjoint,
  createDisjoint,
  deleteDisjoint,
  previewOwlImport,
  executeOwlImport,
  listOntologyMisses,
  dismissMiss,
  undismissMiss,
  adoptMiss,
  adoptMissAsTerm,
  revokeMiss,
  classifyMiss,
  deleteConcept,
  addParent,
  removeParent,
  setPrimaryParent,
  relationClosure
} from '../../api/ontology'
import { getArchitectureOverview } from '../../api/architecture'
import { statusText, statusTagType } from '../../utils/dict'
import { axiomSuffixOf, axiomStyleOf, axiomTooltipOf } from '../../utils/axiomEdge'
import { useConceptStore } from '../../store/concept'
import { useUserStore } from '../../store/user'
import GraphCanvas from '../../components/GraphCanvas.vue'
import RulePanel from './components/RulePanel.vue'
import ActionPanel from './components/ActionPanel.vue'
import InstancePanel from './components/InstancePanel.vue'
import ReleasePanel from './components/ReleasePanel.vue'
import AxiomPanel from './components/AxiomPanel.vue'

const conceptStore = useConceptStore()
const userStore = useUserStore()
const route = useRoute()

const activeTab = ref('modeling')

// ---------- 概念状态机 ----------
// DRAFT→REVIEW→PUBLISHED→DEPRECATED，REVIEW 可回 DRAFT；
// DRAFT/REVIEW 可直达 DEPRECATED（维护治理加固 H2：未发布概念的废弃=撤销，建模员即可）
const transitions = {
  DRAFT: ['REVIEW', 'DEPRECATED'],
  REVIEW: ['PUBLISHED', 'DRAFT', 'DEPRECATED'],
  PUBLISHED: ['DEPRECATED'],
  DEPRECATED: []
}
const canTransition = (target) =>
  transitions[detail.value.concept?.status]?.includes(target) ?? false

// ---------- 业务域 ----------
const domains = ref([])
const selectedDomain = ref('')
const domainTreeRef = ref(null)
const domainTree = computed(() => [
  { code: '', name: '全部业务域', children: domains.value }
])

const loadDomains = async () => {
  domains.value = await listDomains()
}

const onDomainClick = (data) => {
  selectedDomain.value = data.code
  loadConcepts()
}

const domainDialogVisible = ref(false)
const savingDomain = ref(false)
const domainForm = reactive({ code: '', name: '', description: '', sort: 1 })

const openDomainDialog = () => {
  Object.assign(domainForm, { code: '', name: '', description: '', sort: 1 })
  domainDialogVisible.value = true
}

const submitDomain = async () => {
  if (!domainForm.code || !domainForm.name) {
    ElMessage.warning('请填写编码和名称')
    return
  }
  savingDomain.value = true
  try {
    await createDomain({ ...domainForm })
    ElMessage.success('业务域创建成功')
    domainDialogVisible.value = false
    loadDomains()
  } finally {
    savingDomain.value = false
  }
}

// ---------- 概念列表 ----------
const concepts = ref([])
const loadingConcepts = ref(false)
const conceptPage = ref(1)
const conceptPageSize = 20

const pagedConcepts = computed(() => {
  const start = (conceptPage.value - 1) * conceptPageSize
  return concepts.value.slice(start, start + conceptPageSize)
})

const loadConcepts = async () => {
  loadingConcepts.value = true
  try {
    concepts.value = await listConcepts(selectedDomain.value || undefined)
    conceptPage.value = 1
  } finally {
    loadingConcepts.value = false
  }
}

// ---------- 概念详情 ----------
const drawerVisible = ref(false)
const activeInnerTab = ref('attrs')
const detail = ref({ concept: null, attributes: [], relations: [], terms: [] })
const transitioning = ref(false)

const openDetail = async (row, innerTab = 'attrs') => {
  detail.value = await conceptDetail(row.code)
  activeInnerTab.value = innerTab
  drawerVisible.value = true
  closureRelation.value = ''
  closureResult.value = null
  loadDisjoint()
}

const refreshDetail = async () => {
  detail.value = await conceptDetail(detail.value.concept.code)
}

const doTransition = async (target) => {
  transitioning.value = true
  try {
    await transitionConcept(detail.value.concept.code, target)
    ElMessage.success('状态流转成功')
    await refreshDetail()
    loadConcepts()
  } finally {
    transitioning.value = false
  }
}

// ---------- 删除概念（仅 DRAFT）----------
// 失败时后端 msg（状态限制 / 引用计数）由拦截器原样 ElMessage 展示
const deletingConcept = ref(false)

const removeConcept = async () => {
  const c = detail.value.concept
  await ElMessageBox.confirm(
    `确认删除概念「${c.name}（${c.code}）」？删除后不可恢复。`,
    '删除概念',
    { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  deletingConcept.value = true
  try {
    await deleteConcept(c.code)
    ElMessage.success('概念已删除')
    drawerVisible.value = false
    loadConcepts()
    conceptStore.fetchAll(true)
  } finally {
    deletingConcept.value = false
  }
}

// ---------- 新建/编辑概念 ----------
const conceptDialogVisible = ref(false)
const conceptDialogMode = ref('create')
const savingConcept = ref(false)
const conceptForm = reactive({ id: null, code: '', name: '', domainCode: '', definition: '', owner: '', iri: '' })

const openConceptDialog = (mode) => {
  conceptDialogMode.value = mode
  if (mode === 'create') {
    Object.assign(conceptForm, {
      id: null,
      code: '',
      name: '',
      domainCode: selectedDomain.value || '',
      definition: '',
      owner: '',
      iri: ''
    })
  } else {
    const c = detail.value.concept
    Object.assign(conceptForm, {
      id: c.id,
      code: c.code,
      name: c.name,
      domainCode: c.domainCode,
      definition: c.definition,
      owner: c.owner,
      iri: c.iri || ''
    })
  }
  conceptDialogVisible.value = true
}

const submitConcept = async () => {
  if (!conceptForm.code || !conceptForm.name || !conceptForm.domainCode) {
    ElMessage.warning('请填写编码、名称和所属域')
    return
  }
  savingConcept.value = true
  try {
    if (conceptDialogMode.value === 'create') {
      await createConcept({
        code: conceptForm.code,
        name: conceptForm.name,
        domainCode: conceptForm.domainCode,
        definition: conceptForm.definition,
        owner: conceptForm.owner,
        iri: conceptForm.iri || null
      })
      ElMessage.success('概念创建成功（草稿）')
    } else {
      // PUT 需要完整实体（含 id），用详情中的实体合并表单字段
      await updateConcept({ ...detail.value.concept, ...conceptForm })
      ElMessage.success('概念更新成功')
      await refreshDetail()
    }
    conceptDialogVisible.value = false
    loadConcepts()
  } finally {
    savingConcept.value = false
  }
}

// ---------- 属性管理 ----------
const attrDialogVisible = ref(false)
const savingAttr = ref(false)
const attrForm = reactive({ attrCode: '', attrName: '', dataType: 'STRING', isKey: 0, definition: '' })

const openAttrDialog = () => {
  Object.assign(attrForm, { attrCode: '', attrName: '', dataType: 'STRING', isKey: 0, definition: '' })
  attrDialogVisible.value = true
}

const submitAttribute = async () => {
  if (!attrForm.attrCode || !attrForm.attrName) {
    ElMessage.warning('请填写属性编码和属性名称')
    return
  }
  savingAttr.value = true
  try {
    await createAttribute({
      conceptCode: detail.value.concept.code,
      ...attrForm,
      sort: (detail.value.attributes?.length || 0) + 1
    })
    ElMessage.success('属性已保存')
    attrDialogVisible.value = false
    await refreshDetail()
  } finally {
    savingAttr.value = false
  }
}

const removeAttribute = async (row) => {
  await ElMessageBox.confirm(`确认删除属性「${row.attrName}（${row.attrCode}）」？`, '提示', {
    type: 'warning'
  })
  await deleteAttribute(row.id)
  ElMessage.success('属性已删除')
  await refreshDetail()
}

// ---------- 关系管理 ----------
const relationDialogVisible = ref(false)
const relationDialogMode = ref('create')
const savingRelation = ref(false)
const emptyRelationForm = () => ({
  id: null,
  fromConcept: detail.value.concept?.code || '',
  toConcept: '',
  relationName: '',
  description: '',
  isSymmetric: 0,
  isTransitive: 0,
  isFunctional: 0,
  isInverseFunctional: 0,
  isAsymmetric: 0,
  inverseOf: '',
  iri: ''
})
const relationForm = reactive(emptyRelationForm())

// 互逆关系候选：全量关系名（架构 overview 的 RELATION 边 label），懒加载一次
const relationNameOptions = ref([])
let relationNamesLoaded = false
const loadRelationNames = async () => {
  if (relationNamesLoaded) return
  try {
    const data = await getArchitectureOverview()
    const names = new Set(
      (data.edges || []).filter((e) => e.kind === 'RELATION' && e.label).map((e) => e.label)
    )
    relationNameOptions.value = [...names]
    relationNamesLoaded = true
  } catch {
    // 失败不阻塞，仍可手工输入
  }
}

const openRelationDialog = (mode = 'create', row = null) => {
  relationDialogMode.value = mode
  if (mode === 'create') {
    Object.assign(relationForm, emptyRelationForm())
  } else {
    Object.assign(relationForm, {
      ...emptyRelationForm(),
      ...row,
      inverseOf: row.inverseOf || '',
      iri: row.iri || ''
    })
  }
  loadRelationNames()
  relationDialogVisible.value = true
}

const submitRelation = async () => {
  if (!relationForm.fromConcept || !relationForm.toConcept || !relationForm.relationName) {
    ElMessage.warning('请填写起点概念、终点概念和关系名')
    return
  }
  savingRelation.value = true
  try {
    const payload = { ...relationForm, inverseOf: relationForm.inverseOf || null }
    if (relationDialogMode.value === 'edit') {
      await updateRelation(payload)
    } else {
      await createRelation(payload)
    }
    ElMessage.success('关系已保存')
    relationDialogVisible.value = false
    await refreshDetail()
  } finally {
    savingRelation.value = false
  }
}

const removeRelation = async (row) => {
  await ElMessageBox.confirm(
    `确认删除关系「${row.fromConcept} —${row.relationName}→ ${row.toConcept}」？`,
    '提示',
    { type: 'warning' }
  )
  await deleteRelation(row.id)
  ElMessage.success('关系已删除')
  await refreshDetail()
}

// ---------- 多父继承 ----------
const conceptNameOf = (code) =>
  conceptStore.concepts.find((c) => c.code === code)?.name || ''

const parentDialogVisible = ref(false)
const savingParent = ref(false)
const parentForm = reactive({ parentCode: '', isPrimary: 0 })

// 候选父概念：排除自身与已是父的概念
const parentCandidates = computed(() => {
  const self = detail.value.concept?.code
  const existing = new Set((detail.value.parents || []).map((p) => p.parentCode))
  return conceptStore.concepts.filter((c) => c.code !== self && !existing.has(c.code))
})

const openParentDialog = () => {
  Object.assign(parentForm, { parentCode: '', isPrimary: 0 })
  parentDialogVisible.value = true
}

const submitParent = async () => {
  if (!parentForm.parentCode) {
    ElMessage.warning('请选择父概念')
    return
  }
  savingParent.value = true
  try {
    await addParent(detail.value.concept.code, { ...parentForm })
    ElMessage.success('父概念已添加')
    parentDialogVisible.value = false
    await refreshDetail()
  } finally {
    savingParent.value = false
  }
}

const doSetPrimaryParent = async (row) => {
  await setPrimaryParent(detail.value.concept.code, row.parentCode)
  ElMessage.success('已设为主父')
  await refreshDetail()
}

const doRemoveParent = async (row) => {
  await ElMessageBox.confirm(
    `确认移除父概念「${row.parentCode}」？`,
    '提示',
    { type: 'warning' }
  )
  await removeParent(detail.value.concept.code, row.parentCode)
  ElMessage.success('已删除')
  await refreshDetail()
}

// ---------- 传递闭包（图 tab 小工具） ----------
const closureRelation = ref('')
const loadingClosure = ref(false)
const closureResult = ref(null)

// 可选关系：当前概念声明了传递公理的关系名
const transitiveRelations = computed(() => [
  ...new Set(
    (detail.value.relations || []).filter((r) => r.isTransitive === 1).map((r) => r.relationName)
  )
])

const closureLayers = computed(() => {
  const layers = {}
  for (const r of closureResult.value?.reachable || []) {
    ;(layers[r.depth] ||= []).push(r.concept)
  }
  return layers
})

const runClosure = async () => {
  loadingClosure.value = true
  try {
    closureResult.value = await relationClosure(
      closureRelation.value,
      detail.value.concept.code
    )
  } finally {
    loadingClosure.value = false
  }
}

// ---------- 邻域图（「图」tab）----------
const overviewData = ref(null)
const loadingGraph = ref(false)

const loadOverview = async () => {
  if (overviewData.value) return
  loadingGraph.value = true
  try {
    overviewData.value = await getArchitectureOverview()
  } catch {
    overviewData.value = { nodes: [], edges: [] }
  } finally {
    loadingGraph.value = false
  }
}

const graphCategories = [
  { name: '当前概念', itemStyle: { color: '#409eff' } },
  { name: '相邻概念', itemStyle: { color: '#67c23a' } }
]

// 以当前概念为中心：RELATION 关系邻边 + 互斥（disjoint）红虚线边
const neighborhood = computed(() => {
  const code = detail.value.concept?.code
  const overview = overviewData.value
  if (!code || !overview) return { nodes: [], edges: [] }
  const cid = `C:${code}`
  const nameOf = (c) =>
    (overview.nodes || []).find((n) => n.id === `C:${c}`)?.name || c
  const nodeIds = new Set([cid])
  const edges = []
  for (const e of overview.edges || []) {
    if (e.kind !== 'RELATION') continue
    if (e.source !== cid && e.target !== cid) continue
    nodeIds.add(e.source)
    nodeIds.add(e.target)
    const style = axiomStyleOf(e, '#95d475', { width: 1.5, curveness: 0.12 })
    edges.push({
      source: e.source,
      target: e.target,
      label: { show: true, formatter: (e.label || '') + axiomSuffixOf(e), fontSize: 11, color: '#909399' },
      ...style,
      _raw: e
    })
  }
  for (const d of disjointPairs.value) {
    if (d.conceptACode !== code && d.conceptBCode !== code) continue
    const other = d.conceptACode === code ? d.conceptBCode : d.conceptACode
    const oid = `C:${other}`
    nodeIds.add(oid)
    edges.push({
      source: cid,
      target: oid,
      label: { show: true, formatter: '互斥', fontSize: 11, color: '#f56c6c' },
      lineStyle: { color: '#f56c6c', width: 1.5, type: 'dashed', curveness: 0 },
      symbol: ['none', 'none']
    })
  }
  const nodes = [...nodeIds].map((id) => {
    const c = id.slice(2)
    return {
      id,
      name: nameOf(c),
      category: id === cid ? '当前概念' : '相邻概念',
      symbolSize: id === cid ? 30 : 20,
      _code: c
    }
  })
  return { nodes, edges }
})
const graphNodes = computed(() => neighborhood.value.nodes)
const graphEdges = computed(() => neighborhood.value.edges)

// 边 tooltip：关系名+公理尾缀+逐条人话解释（spec §4）
const graphTooltip = (p) => {
  if (p.dataType === 'edge') {
    const e = p.data._raw
    if (!e) return '互斥' // 互斥边无 _raw，label 是对象不能直接回显
    const lines = [`<b>${e.label || ''}</b>${axiomSuffixOf(e)}`]
    for (const l of axiomTooltipOf(e)) lines.push(`<br/>${l}`)
    return lines.join('')
  }
  return p.data.name || ''
}

const onGraphNodeClick = async (node) => {
  if (!node._code || node._code === detail.value.concept?.code) return
  await openDetail({ code: node._code }, 'graph')
}

watch(activeInnerTab, (tab) => {
  if (tab === 'graph') {
    loadOverview()
    loadDisjoint()
  }
  if (tab === 'disjoint') loadDisjoint()
})

// ---------- 互斥公理 ----------
const disjointPairs = ref([])
const loadingDisjoint = ref(false)
const disjointDialogVisible = ref(false)
const savingDisjoint = ref(false)
const disjointForm = reactive({ conceptACode: '', conceptBCode: '', definition: '' })

const loadDisjoint = async () => {
  loadingDisjoint.value = true
  try {
    disjointPairs.value = await listDisjoint()
  } finally {
    loadingDisjoint.value = false
  }
}

const openDisjointDialog = () => {
  Object.assign(disjointForm, {
    conceptACode: detail.value.concept?.code || '',
    conceptBCode: '',
    definition: ''
  })
  disjointDialogVisible.value = true
}

const submitDisjoint = async () => {
  if (!disjointForm.conceptACode || !disjointForm.conceptBCode) {
    ElMessage.warning('请选择两个概念')
    return
  }
  if (disjointForm.conceptACode === disjointForm.conceptBCode) {
    ElMessage.warning('互斥两端不能是同一概念')
    return
  }
  savingDisjoint.value = true
  try {
    await createDisjoint({ ...disjointForm })
    ElMessage.success('互斥公理已保存')
    disjointDialogVisible.value = false
    loadDisjoint()
  } finally {
    savingDisjoint.value = false
  }
}

const removeDisjoint = async (row) => {
  await ElMessageBox.confirm(
    `确认删除互斥公理「${row.conceptACode} ✕ ${row.conceptBCode}」？`,
    '提示',
    { type: 'warning' }
  )
  await deleteDisjoint(row.id)
  ElMessage.success('已删除')
  loadDisjoint()
}

// ---------- 导入 OWL ----------
const importVisible = ref(false)
const importFileList = ref([])
const previewing = ref(false)
const importing = ref(false)
const importPreview = ref(null)

const dispositionText = (d) =>
  ({ CREATE: '新增', UPDATE: '更新', KEY_TAKEN: '主键占用', DEGRADED_TO_STRING: '降级为字符串' }[d] ||
  d)
const dispositionTagType = (d) =>
  ({ CREATE: 'success', UPDATE: 'primary', KEY_TAKEN: 'danger', DEGRADED_TO_STRING: 'warning' }[
    d
  ] || 'info')
const importKindText = (k) => ({ CLASS: '类', RELATION: '关系', ATTRIBUTE: '属性' }[k] || k)

// detail 为对象：类含 match/comment/subClassOf，关系含 fromConcept/toConcept 等
const importItemDetail = (row) => {
  if (row.reason) return row.reason
  const d = row.detail
  if (!d) return '-'
  const parts = []
  if (d.match) parts.push(d.match)
  if (d.fromConcept && d.toConcept) parts.push(`${d.fromConcept} → ${d.toConcept}`)
  if (d.comment) parts.push(d.comment)
  return parts.join('；') || '-'
}

const openImportDialog = () => {
  importFileList.value = []
  importPreview.value = null
  importVisible.value = true
}

const doImportPreview = async () => {
  const file = importFileList.value[0]?.raw
  if (!file) {
    ElMessage.warning('请先选择文件')
    return
  }
  previewing.value = true
  try {
    importPreview.value = await previewOwlImport(file)
  } finally {
    previewing.value = false
  }
}

const doImportExecute = async () => {
  const file = importFileList.value[0]?.raw
  if (!file) return
  importing.value = true
  try {
    const res = await executeOwlImport(file)
    ElMessage.success(
      `导入完成：新增 ${res.created ?? 0}，更新 ${res.updated ?? 0}，跳过 ${res.skipped ?? 0}，降级 ${res.degraded ?? 0}`
    )
    importVisible.value = false
    conceptStore.fetchAll(true)
    loadConcepts()
    if (drawerVisible.value) refreshDetail()
  } finally {
    importing.value = false
  }
}

// ---------- 扩展提案（本体增长回路） ----------
// 待处理池：dismissed=0 且（未采纳 或 已撤销）；已采纳口径：adoptedAs 非空且 revoked=0
const proposalVisible = ref(false)
const loadingMisses = ref(false)
const missTab = ref('pending')
const missData = ref({ items: [], pendingCount: 0 })

const pendingMisses = computed(() =>
  missData.value.items.filter((m) => m.dismissed === 0 && (!m.adoptedAs || m.revoked === 1))
)
const dismissedMisses = computed(() => missData.value.items.filter((m) => m.dismissed === 1))
const adoptedMisses = computed(() =>
  missData.value.items.filter((m) => m.adoptedAs && m.revoked === 0)
)

const missKindText = (k) => ({ CONCEPT: '概念', ATTRIBUTE: '属性', QUESTION: '问题' }[k] || k)
const missKindTag = (k) => ({ CONCEPT: 'primary', ATTRIBUTE: 'info', QUESTION: 'success' }[k] || 'info')
const missSourceText = (s) => ({ SEARCH: '搜索未命中', MAPPING_AI: '映射失败', CS_ASK: '客服问答未命中', QA_ASK: '问数未命中' }[s] || s)
const missSourceTag = (s) => ({ SEARCH: 'danger', MAPPING_AI: 'warning', CS_ASK: 'success', QA_ASK: 'success' }[s] || 'info')
const formatMissTime = (t) => (t || '').replace('T', ' ')

const loadMisses = async () => {
  loadingMisses.value = true
  try {
    missData.value = await listOntologyMisses()
  } finally {
    loadingMisses.value = false
  }
}

const openProposalDrawer = () => {
  missTab.value = 'pending'
  proposalVisible.value = true
  loadMisses()
}

// 采纳：创建 DRAFT 概念
const adoptDialogVisible = ref(false)
const adopting = ref(false)
const adoptForm = reactive({ id: null, term: '', code: '', name: '', domainCode: '', definition: '' })
// AI 归类建议（null 表示手工采纳）；AI 只建议，所有字段人可改后再提交
const adoptSuggestion = ref(null)
const classifyingId = ref(null)

const codeTakenHint = computed(
  () =>
    adoptSuggestion.value?.codeTaken &&
    adoptForm.code === adoptSuggestion.value.code
)

const openAdoptDialog = (row) => {
  adoptSuggestion.value = null
  Object.assign(adoptForm, {
    id: row.id,
    term: row.term,
    code: '',
    name: row.term,
    domainCode: selectedDomain.value || '',
    definition: ''
  })
  adoptDialogVisible.value = true
}

// AI 归类：拿建议后自动打开采纳对话框并预填（degraded 时 suggestion 仅含 name=term 与说明文案）
const doClassify = async (row) => {
  classifyingId.value = row.id
  try {
    const { suggestion } = await classifyMiss(row.id)
    adoptSuggestion.value = suggestion
    Object.assign(adoptForm, {
      id: row.id,
      term: row.term,
      code: suggestion.code || '',
      name: suggestion.name || row.term,
      domainCode: suggestion.domainCode || selectedDomain.value || '',
      definition: suggestion.definition || ''
    })
    adoptDialogVisible.value = true
  } finally {
    classifyingId.value = null
  }
}

const CODE_PATTERN = /^[A-Z][A-Z0-9_]*$/
const submitAdopt = async () => {
  if (!adoptForm.code || !adoptForm.name || !adoptForm.domainCode) {
    ElMessage.warning('请填写编码、名称和所属域')
    return
  }
  if (!CODE_PATTERN.test(adoptForm.code)) {
    ElMessage.warning('编码需为大写蛇形：大写字母开头，仅含大写字母、数字、下划线')
    return
  }
  adopting.value = true
  try {
    await adoptMiss(adoptForm.id, {
      code: adoptForm.code,
      name: adoptForm.name,
      domainCode: adoptForm.domainCode,
      definition: adoptForm.definition || undefined
    })
    ElMessage.success('已创建草稿概念')
    adoptDialogVisible.value = false
    loadMisses()
    loadConcepts()
    conceptStore.fetchAll(true)
  } finally {
    adopting.value = false
  }
}

// 忽略：理由必填
const ignoreDialogVisible = ref(false)
const ignoring = ref(false)
const ignoreForm = reactive({ id: null, term: '', reason: '' })

const openIgnoreDialog = (row) => {
  Object.assign(ignoreForm, { id: row.id, term: row.term, reason: '' })
  ignoreDialogVisible.value = true
}

const submitIgnore = async () => {
  if (!ignoreForm.reason.trim()) {
    ElMessage.warning('请填写忽略理由')
    return
  }
  ignoring.value = true
  try {
    await dismissMiss(ignoreForm.id, ignoreForm.reason.trim())
    ElMessage.success('已忽略')
    ignoreDialogVisible.value = false
    loadMisses()
  } finally {
    ignoring.value = false
  }
}

const doUndismiss = async (row) => {
  await undismissMiss(row.id)
  ElMessage.success('已撤销忽略，回到待处理池')
  loadMisses()
}

const doRevoke = async (row) => {
  const hint =
    row.adoptedAs === 'TERM'
      ? `撤销采纳将删除「${row.term}」在概念「${row.adoptedConceptCode}」下的术语登记，该信号回到待处理池。确认撤销？`
      : `撤销采纳将把概念「${row.adoptedConceptCode}」置为已废弃，该信号回到待处理池。确认撤销？`
  await ElMessageBox.confirm(hint, '撤销采纳', {
    type: 'warning',
    confirmButtonText: '确认撤销',
    cancelButtonText: '取消'
  })
  await revokeMiss(row.id)
  ElMessage.success('已撤销采纳')
  loadMisses()
  loadConcepts()
  conceptStore.fetchAll(true)
}

// 待处理行内「处置」下拉分发：AI 归类保持独立按钮，其余收纳
const onMissAction = (cmd, row) => {
  if (cmd === 'adopt') openAdoptDialog(row)
  else if (cmd === 'term') openTermDialog(row)
  else if (cmd === 'ignore') openIgnoreDialog(row)
}

// 挂为术语：登记为现有概念的方言术语
const termDialogVisible = ref(false)
const savingTerm = ref(false)
const termForm = reactive({ id: null, term: '', conceptCode: '' })

const openTermDialog = (row) => {
  Object.assign(termForm, { id: row.id, term: row.term, conceptCode: '' })
  termDialogVisible.value = true
}

const submitTerm = async () => {
  if (!termForm.conceptCode) {
    ElMessage.warning('请选择目标概念')
    return
  }
  savingTerm.value = true
  try {
    await adoptMissAsTerm(termForm.id, termForm.conceptCode)
    ElMessage.success('已登记为术语')
    termDialogVisible.value = false
    loadMisses()
  } finally {
    savingTerm.value = false
  }
}

// ---------- 路由 query 联动 ----------
// ?concept=CODE 打开详情抽屉；?domain=CODE 过滤业务域；?tab=rule 打开规则面板
const applyRouteQuery = async (q) => {
  try {
    if (q.tab) {
      const map = { rule: 'rules' }
      activeTab.value = map[q.tab] || q.tab
    }
    if (q.domain !== undefined) {
      selectedDomain.value = q.domain
      await loadConcepts()
      domainTreeRef.value?.setCurrentKey(q.domain || null)
    }
    if (q.concept) {
      await openDetail({ code: q.concept })
    }
  } catch {
    // query 指向的资源不存在时忽略（拦截器已提示）
  }
}

watch(() => route.query, applyRouteQuery)

onMounted(async () => {
  conceptStore.fetchAll()
  loadMisses()
  await loadDomains()
  // 带 domain query 时由 applyRouteQuery 负责加载，避免与初始全量加载竞态
  if (route.query.domain === undefined) loadConcepts()
  applyRouteQuery(route.query)
})
</script>

<style scoped>
.workbench-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.pane-toolbar {
  margin-bottom: 12px;
}

.concept-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.relation-arrow {
  margin: 0 6px;
  color: #909399;
}

.term-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.ops-pane p {
  margin-top: 0;
}

.version-text {
  margin-left: 12px;
  color: #909399;
  font-size: 13px;
}

.iri-line {
  color: #606266;
  font-size: 13px;
  word-break: break-all;
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

.gl-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
}

.gl-dot.gl-center {
  background: #409eff;
}

.gl-dot.gl-neighbor {
  background: #67c23a;
}

.gl-line.gl-disjoint {
  width: 20px;
  border-top: 2px dashed #f56c6c;
  display: inline-block;
}

.gl-line.gl-relation {
  width: 20px;
  border-top: 2px solid #95d475;
  display: inline-block;
}

.gl-line.gl-transitive {
  width: 20px;
  border-top: 2px dashed #e6a23c;
  display: inline-block;
}

.gl-arrow {
  font-style: normal;
  color: #606266;
  font-weight: 700;
}

.disjoint-cross {
  margin: 0 8px;
  color: #f56c6c;
  font-weight: 700;
}

.import-actions {
  margin: 12px 0;
}

.import-summary {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.import-unprojected {
  margin-bottom: 12px;
}

.proposal-badge {
  margin-right: 12px;
}

.proposal-badge :deep(.el-badge__content) {
  margin-top: 2px;
}

.miss-count {
  font-weight: 600;
  color: #606266;
}

.miss-count.hot {
  color: #f56c6c;
  font-size: 15px;
}

.miss-row-muted {
  color: #a8abb2;
}

.form-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.form-hint-error {
  color: #f56c6c;
}

.workflow-alert {
  margin-bottom: 12px;
}

.inherit-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.parent-name {
  margin-left: 8px;
  color: #606266;
}

.closure-tool {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.closure-tip {
  font-size: 12px;
  color: #909399;
}

.closure-layer {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 6px 0;
}

.closure-depth {
  font-size: 12px;
  color: #909399;
  width: 60px;
  flex-shrink: 0;
}
</style>
