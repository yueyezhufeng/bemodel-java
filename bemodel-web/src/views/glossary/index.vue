<template>
  <div class="page">
    <!-- 语义搜索 -->
    <el-card>
      <div class="search-bar">
        <el-input
          v-model="q"
          size="large"
          placeholder="用自然语言查询业务口径，如：出院人数怎么算 / 病员是什么"
          clearable
          @keyup.enter="doSearch"
        >
          <template #append>
            <el-button type="primary" :loading="searching" @click="doSearch">搜索</el-button>
          </template>
        </el-input>
      </div>
      <div v-if="searched" class="search-result">
        <el-alert v-if="answer" type="success" :closable="false" class="answer-alert">
          <template #title>
            <div class="answer-title">
              <span>{{ answer }}</span>
              <el-tag size="small" effect="plain" type="success">AI 生成</el-tag>
            </div>
          </template>
        </el-alert>
        <el-table :data="hits" style="margin-top: 12px" size="small">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag :type="hitTagType(row.type)">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" width="180" />
          <el-table-column prop="conceptCode" label="概念" width="140" />
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
        </el-table>
        <el-empty v-if="!hits.length && !answer" description="未找到相关口径" :image-size="60" />
      </div>
    </el-card>

    <!-- 术语库 / 指标库 -->
    <el-card style="margin-top: 16px">
      <el-tabs v-model="tab">
        <el-tab-pane label="术语库" name="terms">
          <div class="filter-bar">
            <el-select
              v-model="termConcept"
              placeholder="按概念筛选"
              clearable
              filterable
              style="width: 240px"
              @change="loadTerms"
            >
              <el-option
                v-for="c in conceptStore.concepts"
                :key="c.code"
                :label="`${c.name}（${c.code}）`"
                :value="c.code"
              />
            </el-select>
            <el-select v-model="codeSystemFilter" style="width: 160px" @change="termPage = 1">
              <el-option label="全部体系" value="" />
              <el-option label="SNOMED CT" value="SNOMED CT" />
              <el-option label="ICD-10" value="ICD-10" />
              <el-option label="平台标准" value="平台标准" />
              <el-option label="产品方言" value="产品方言" />
            </el-select>
            <el-button type="primary" plain @click="openTermDialog">新增术语</el-button>
          </div>
          <el-table :data="pagedTerms" v-loading="loadingTerms">
            <el-table-column prop="term" label="术语" width="150" />
            <el-table-column prop="sourceProduct" label="来源产品" width="130" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="row.termType === 'STANDARD' ? 'success' : 'info'">
                  {{ row.termType === 'STANDARD' ? '标准' : '别名' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="编码体系" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="codeSystemTag(row)">{{ row.codeSystem || row.sourceProduct }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="标准编码" width="130">
              <template #default="{ row }">
                <span class="std-code">{{ row.standardCode || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="conceptCode" label="所属概念" />
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button size="small" type="danger" link @click="removeTerm(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="filteredTerms.length > 0"
            class="term-pager"
            small
            layout="total, prev, pager, next"
            v-model:current-page="termPage"
            :page-size="termPageSize"
            :total="filteredTerms.length"
          />
        </el-tab-pane>
        <el-tab-pane label="指标库" name="metrics">
          <div class="metric-header">
            <span class="metric-header-title">指标库</span>
            <el-button type="primary" :loading="inspecting" @click="inspectAll">全量巡检</el-button>
          </div>

          <!-- 指标统计头（全部来自真实台账与巡检结果） -->
          <div class="metric-stats">
            <div class="mstat">
              <div class="mstat-value">{{ metricStats.total }}</div>
              <div class="mstat-label">已定义指标</div>
            </div>
            <div class="mstat">
              <div class="mstat-value" style="color: var(--el-color-success)">
                {{ metricStats.monitored }}
              </div>
              <div class="mstat-label">已接入巡检</div>
            </div>
            <div class="mstat">
              <div class="mstat-value" :class="{ 'mstat-warn': metricStats.alarmed }">
                {{ metricStats.alarmed }}
              </div>
              <div class="mstat-label">巡检告警中</div>
            </div>
            <div class="mstat">
              <div class="mstat-value" style="color: #8b5cf6">{{ metricStats.newThisMonth }}</div>
              <div class="mstat-label">本月新增</div>
            </div>
          </div>

          <!-- 筛选工具栏 -->
          <div class="metric-toolbar">
            <el-input
              v-model="metricKw"
              placeholder="搜索指标名称 / 编码"
              clearable
              :prefix-icon="Search"
              style="width: 220px"
            />
            <el-select v-model="metricDomain" placeholder="所有业务域" clearable style="width: 160px">
              <el-option v-for="d in metricDomains" :key="d" :label="d" :value="d" />
            </el-select>
            <el-select
              v-model="metricStatus"
              placeholder="所有监控状态"
              clearable
              style="width: 150px"
            >
              <el-option label="告警中" value="alarm" />
              <el-option label="正常" value="ok" />
              <el-option label="未接入监控" value="none" />
            </el-select>
            <span class="metric-toolbar-tip">
              {{ filteredMetrics.length }} / {{ metrics.length }} 项 · 状态来自巡检实测，非人工标注
            </span>
          </div>

          <el-alert
            v-if="inspected && alarmedMetrics.length"
            type="error"
            :closable="false"
            class="alarm-alert"
            :title="`存在 ${alarmedMetrics.length} 项指标告警：${alarmedMetrics.map((x) => x.name).join('、')}`"
          />
          <el-row :gutter="16" v-loading="loadingMetrics">
            <el-col v-for="m in pagedMetrics" :key="m.id" :span="8">
              <el-card
                :id="`metric-${m.metricCode}`"
                class="metric-card"
                :class="{ 'metric-highlight': m.metricCode === highlightCode }"
                shadow="hover"
              >
                <div class="metric-name">
                  {{ m.name }}
                  <span class="metric-code">{{ m.metricCode }}</span>
                  <el-tag v-if="metricDomainOf(m)" size="small" effect="plain" style="margin-left: 6px">
                    {{ metricDomainOf(m) }}
                  </el-tag>
                  <el-tag
                    v-if="!hasProbe(m)"
                    size="small"
                    type="info"
                    effect="plain"
                    style="margin-left: 6px"
                  >未接入监控</el-tag>
                </div>
                <div class="metric-def">{{ m.definition }}</div>
                <pre class="metric-formula">{{ m.formula }}</pre>
                <div v-if="hasProbe(m)" class="metric-monitor">
                  <template v-if="displayValue(m) !== null">
                    <span class="metric-value" :class="{ alarm: displayAlarm(m) }">
                      {{ displayValue(m) }}
                    </span>
                    <el-tag size="small" :type="displayAlarm(m) ? 'danger' : 'success'">
                      {{ displayAlarm(m) ? '告警' : '正常' }}
                    </el-tag>
                    <span class="metric-time">{{ displayTime(m) }}</span>
                  </template>
                  <span v-else class="metric-no-val">暂无实测值</span>
                </div>
                <div class="metric-owner">
                  <span>
                    负责人：{{ m.owner || '-' }}
                    <el-tag size="small" effect="plain" style="margin-left: 8px">{{ m.conceptCode }}</el-tag>
                    <span v-if="m.warnThreshold != null" class="metric-threshold">
                      阈值 {{ m.warnThreshold }}
                    </span>
                  </span>
                  <el-button
                    v-if="!userStore.isViewer"
                    size="small"
                    plain
                    @click="openMetricEdit(m)"
                  >编辑</el-button>
                  <el-button
                    v-if="hasProbe(m)"
                    size="small"
                    type="primary"
                    plain
                    :loading="evaluatingCode === m.metricCode"
                    @click="runEvaluate(m)"
                  >执行检测</el-button>
                </div>
              </el-card>
            </el-col>
          </el-row>
          <el-pagination
            v-if="filteredMetrics.length > metricPageSize"
            class="metric-pager"
            small
            layout="total, prev, pager, next"
            v-model:current-page="metricPage"
            :page-size="metricPageSize"
            :total="filteredMetrics.length"
          />
        </el-tab-pane>
        <el-tab-pane label="对账" name="reconcile">
          <div class="metric-header">
            <span class="metric-header-title">对账分歧</span>
            <span class="rec-tip">跑一次 = 逐口径实测 + 算差额 + 可选下钻明细,每次运行落历史</span>
            <el-button size="small" type="primary" plain @click="openRecDialog">新建对账组</el-button>
          </div>
          <el-empty
            v-if="!loadingRec && !recGroups.length"
            description="还没有对账组:先在指标库配好同一业务的多口径指标,再点右上「新建对账组」"
          />
          <el-row :gutter="16" v-loading="loadingRec">
            <el-col v-for="g in recGroups" :key="g.groupCode" :span="12">
              <el-card class="metric-card rec-card">
                <div class="metric-name">
                  {{ g.name }}
                  <span class="metric-code">{{ g.groupCode }}</span>
                  <el-tag v-if="g.owner" size="small" effect="plain" style="margin-left: 6px">
                    牵头:{{ g.owner }}
                  </el-tag>
                  <el-tag size="small" :type="disputeTag(g).type" style="margin-left: 6px">
                    {{ disputeTag(g).text }}
                  </el-tag>
                </div>
                <div class="metric-def rec-def">{{ g.definition }}</div>
                <div v-if="g.latest" class="rec-values">
                  <div v-for="(v, code) in g.latest.values" :key="code" class="rec-value-row">
                    <span class="rec-value-label">
                      {{ metricNameOf(code) }}<span class="metric-code">{{ code }}</span>
                    </span>
                    <span class="rec-value-num">{{ v ?? '实测失败' }}</span>
                  </div>
                  <div class="rec-value-row rec-diff-row">
                    <span class="rec-value-label">口径间差额(最大-最小)</span>
                    <span
                      class="rec-value-num"
                      :class="g.latest.diffValue === 0 ? 'rec-diff-ok' : (g.latest.diffValue == null ? 'rec-diff-none' : 'rec-diff-bad')"
                    >
                      {{ g.latest.diffValue ?? '—' }}
                    </span>
                  </div>
                </div>
                <el-alert
                  v-if="g.latest && g.latest.errorMsg"
                  type="warning"
                  :closable="false"
                  class="rec-err"
                  :title="`部分口径实测失败:${g.latest.errorMsg}`"
                />
                <div v-if="g.disputeStatus === 'RESOLVED' && g.verdict" class="rec-verdict">
                  裁决:{{ g.verdict }}
                  <span v-if="g.verdictNote" class="rec-verdict-note">(依据:{{ g.verdictNote }})</span>
                  <span class="rec-verdict-meta">
                    · 认领人 {{ g.claimedBy || g.disputeOwner || '未记录' }}<template v-if="g.resolvedBy"> · 裁决人 {{ g.resolvedBy }}</template> 裁于 {{ (g.verdictAt || '').slice(0, 16).replace('T', ' ') || '—' }}
                  </span>
                </div>
                <div v-if="!g.latest" class="rec-no-run">还没跑过,点右下角「跑一次对账」</div>
                <el-collapse v-if="g.latest && g.latest.drillCount >= 0 && g.latest.drillRows" class="rec-drill">
                  <el-collapse-item :title="`差额明细(${g.latest.drillCount} 行,来自下钻SQL)`">
                    <el-table :data="g.latest.drillRows" size="small" max-height="260">
                      <el-table-column v-for="col in drillCols(g.latest.drillRows)" :key="col" :prop="col" :label="col" />
                    </el-table>
                  </el-collapse-item>
                </el-collapse>
                <el-collapse v-if="g.latest && g.latest.buckets" class="rec-drill">
                  <el-collapse-item :title="`归因拆堆(${(g.latest.buckets.buckets || []).length} 桶)`">
                    <el-table :data="g.latest.buckets.buckets || []" size="small" max-height="200">
                      <el-table-column label="方向" width="70">
                        <template #default="{ row }">
                          <span :class="row.sign < 0 ? 'rec-bucket-minus' : 'rec-bucket-plus'">
                            {{ row.sign < 0 ? '−' : '+' }}
                          </span>
                        </template>
                      </el-table-column>
                      <el-table-column prop="label" label="归因" />
                      <el-table-column label="笔数" width="120">
                        <template #default="{ row }">
                          <span v-if="row.count != null">{{ row.count }}</span>
                          <span v-else class="rec-drill-fail">失败:{{ row.error }}</span>
                        </template>
                      </el-table-column>
                    </el-table>
                    <div class="rec-bucket-sum">
                      合计 {{ g.latest.buckets.sum ?? '—' }} ·
                      <span v-if="g.latest.buckets.matchesDiff" class="rec-diff-ok">账对上</span>
                      <span
                        v-else-if="g.latest.diffValue != null && g.latest.buckets.sum != null"
                        class="rec-diff-bad"
                      >剩 {{ g.latest.diffValue - g.latest.buckets.sum }} 笔无归因</span>
                      <span v-else class="rec-diff-none">差额算不出,合计仅供参考</span>
                    </div>
                  </el-collapse-item>
                </el-collapse>
                <div v-if="g.latest && g.latest.drillCount === -1" class="rec-drill-fail">
                  下钻SQL执行失败,差额明细本次没取到
                </div>
                <div class="rec-foot">
                  <span class="metric-time">
                    最近运行:{{ g.latest?.ranAt || '—' }} · 历史共 {{ (g.runs || []).length }} 次
                    <el-link v-if="(g.runs || []).length > 1" type="primary" @click="showHistory(g)">历史</el-link>
                  </span>
                  <span class="rec-actions">
                    <el-button size="small" plain @click="openCompare(g)">直连对照</el-button>
                    <el-button v-if="!userStore.isViewer" size="small" plain @click="openRecEdit(g)">编辑</el-button>
                    <el-button
                      v-if="g.disputeStatus === 'OPEN'"
                      size="small"
                      plain
                      @click="doClaim(g)"
                    >认领</el-button>
                    <el-button
                      v-if="g.disputeStatus === 'CLAIMED'"
                      size="small"
                      type="success"
                      plain
                      @click="openResolve(g)"
                    >裁决</el-button>
                    <el-button size="small" type="primary" :loading="runningCode === g.groupCode" @click="runOne(g)">
                      跑一次对账
                    </el-button>
                  </span>
                </div>
              </el-card>
            </el-col>
          </el-row>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 新增术语 -->
    <el-dialog v-model="termDialogVisible" title="新增术语" width="480px">
      <el-form :model="termForm" label-width="90px">
        <el-form-item label="术语" required>
          <el-input v-model="termForm.term" />
        </el-form-item>
        <el-form-item label="所属概念" required>
          <el-select v-model="termForm.conceptCode" filterable style="width: 100%">
            <el-option
              v-for="c in conceptStore.concepts"
              :key="c.code"
              :label="`${c.name}（${c.code}）`"
              :value="c.code"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="来源产品">
          <el-input v-model="termForm.sourceProduct" placeholder="如 HIS / LIS / 平台标准" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="termForm.termType">
            <el-radio value="STANDARD">标准</el-radio>
            <el-radio value="ALIAS">别名</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="termDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTerm" @click="submitTerm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 对账运行历史 -->
    <el-dialog v-model="historyVisible" :title="`运行历史:${historyGroup?.name || ''}`" width="640px">
      <el-table :data="historyRuns" size="small" max-height="420">
        <el-table-column prop="ranAt" label="运行时间" width="170" />
        <el-table-column label="各口径实测值">
          <template #default="{ row }">
            <span v-for="(v, code) in row.values" :key="code" class="rec-hist-val">
              {{ code }}={{ v ?? '失败' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="diffValue" label="差额" width="80">
          <template #default="{ row }">
            <span :class="row.diffValue === 0 ? 'rec-diff-ok' : (row.diffValue == null ? 'rec-diff-none' : 'rec-diff-bad')">
              {{ row.diffValue ?? '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="失败说明" min-width="120" />
      </el-table>
    </el-dialog>

    <!-- 新建/编辑对账组(弹窗双用) -->
    <el-dialog v-model="recDialogVisible" :title="recDialogTitle" width="560px">
      <el-form :model="recForm" label-width="90px">
        <el-form-item label="组名" required>
          <el-input v-model="recForm.name" placeholder="如:9月出院人数:病案口径 vs 结算口径" />
        </el-form-item>
        <el-form-item label="编码" required>
          <el-input v-model="recForm.groupCode" :disabled="!!recForm.id" placeholder="大写下划线,如 PILOT_SEP_INCOME" />
        </el-form-item>
        <el-form-item label="业务背景">
          <el-input v-model="recForm.definition" type="textarea" :rows="2" placeholder="这个指标为什么会有口径分歧,给对账会看" />
        </el-form-item>
        <el-form-item label="牵头科室">
          <el-input v-model="recForm.owner" placeholder="如:医保办" />
        </el-form-item>
        <el-form-item label="口径指标" required>
          <el-select v-model="recForm.metricCodes" multiple filterable style="width: 100%" placeholder="同一指标的多口径,至少选 2 个">
            <el-option
              v-for="m in metrics"
              :key="m.metricCode"
              :label="`${m.name}(${m.metricCode})`"
              :value="m.metricCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="下钻数据源">
          <el-select v-model="recForm.drillDsCode" clearable style="width: 100%" placeholder="差额明细在哪个库查(可选)">
            <el-option v-for="d in dsOptions" :key="d.dsCode" :label="`${d.dsName || d.dsCode}(${d.dsCode})`" :value="d.dsCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="下钻SQL">
          <el-input v-model="recForm.drillSql" type="textarea" :rows="3" placeholder="只读 SELECT,返回差额明细行(可选;行数自动限 200)" />
        </el-form-item>
        <el-form-item label="归因桶">
          <div style="width: 100%">
            <div v-for="(b, i) in recForm.buckets" :key="i" class="bucket-row">
              <div class="bucket-line">
                <el-select v-model="b.sign" style="width: 92px">
                  <el-option :value="1" label="＋加" />
                  <el-option :value="-1" label="－减" />
                </el-select>
                <el-input v-model="b.label" placeholder="桶名,如 跨月结算" style="width: 170px" />
                <el-select v-model="b.dsCode" filterable style="width: 160px" placeholder="数据源">
                  <el-option v-for="d in dsOptions" :key="d.dsCode" :label="`${d.dsName || d.dsCode}(${d.dsCode})`" :value="d.dsCode" />
                </el-select>
                <el-button size="small" plain type="danger" @click="recForm.buckets.splice(i, 1)">删</el-button>
              </div>
              <el-input v-model="b.sql" type="textarea" :rows="2" placeholder="只读 SELECT,返回单数值(笔数)" />
              <el-button size="small" plain @click="openMonth(b.sql, (v) => (b.sql = v))">换月</el-button>
            </div>
            <el-button size="small" plain @click="recForm.buckets.push({ label: '', dsCode: '', sign: 1, sql: '' })">加一桶</el-button>
            <div class="bucket-tip">差额拆堆:各桶笔数按 ＋/－ 加总,应正好等于口径差额;某桶跑失败会如实标错,不否决差额</div>
          </div>
        </el-form-item>
        <el-form-item label="挂巡检">
          <el-switch v-model="recForm.scheduled" />
          <span class="bucket-tip" style="margin-left: 8px">每日 03:17 自动跑,出分歧(非零差额或算不出)自动告警</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingRec" @click="submitRec">保存</el-button>
      </template>
    </el-dialog>

    <!-- 裁决 -->
    <el-dialog v-model="resolveVisible" :title="`裁决:${resolvingGroup?.name || ''}`" width="520px">
      <el-form :model="resolveForm" label-width="90px">
        <el-form-item label="裁决结论" required>
          <el-input v-model="resolveForm.verdict" placeholder="以哪侧口径为准,如:以结算口径 173 为准" />
        </el-form-item>
        <el-form-item label="依据备注">
          <el-input v-model="resolveForm.note" type="textarea" :rows="2" placeholder="对账会上怎么定的(可选)" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveVisible = false">取消</el-button>
        <el-button type="primary" :loading="resolving" @click="submitResolve">入档</el-button>
      </template>
    </el-dialog>

    <!-- 直连数据库 vs 本体平台 对照 -->
    <el-dialog
      v-model="compareVisible"
      :title="`直连数据库 vs 本体平台:${compareGroup?.name || ''}`"
      width="920px"
      top="6vh"
    >
      <div v-loading="compareLoading">
        <el-row :gutter="14">
          <el-col :span="12">
            <div class="cmp-col">
              <div class="cmp-head">
                <span class="cmp-name">直连数据库方式</span>
              </div>
              <pre v-if="compareData" class="cmp-term">{{ compareTermLines }}</pre>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="cmp-col">
              <div class="cmp-head">
                <span class="cmp-name">本体平台方式</span>
                <span class="cmp-sub">口径卡 + 对账组对象,全程页面可操作</span>
              </div>
              <template v-if="compareData">
                <div v-for="c in compareData.platform.metricCards" :key="c.code" class="cmp-card">
                  <div class="cmp-card-name">{{ c.name }} <span class="cmp-card-code">{{ c.code }}</span></div>
                  <div class="cmp-card-def">{{ c.definition }}(负责人:{{ c.owner || '—' }})</div>
                </div>
                <div v-if="compareData.platform.latest" class="cmp-run">
                  <div class="cmp-run-diff">
                    差额 <b :class="compareData.platform.latest.diffValue === 0 ? 'ok' : 'bad'">{{ compareData.platform.latest.diffValue ?? '—' }}</b>
                  </div>
                  <div class="cmp-run-meta">
                    明细 {{ compareData.platform.latest.drillCount ?? '—' }} 行 ·
                    历史共 {{ compareData.platform.runCount }} 次 ·
                    最近 {{ (compareData.platform.latest.ranAt || '').slice(0, 16).replace('T', ' ') }}
                  </div>
                </div>
                <div v-if="compareData.platform.latest?.buckets" class="cmp-buckets">
                  <div
                    v-for="b in compareData.platform.latest.buckets.buckets || []"
                    :key="b.label"
                    class="cmp-bucket-row"
                  >
                    <span>{{ b.sign < 0 ? '−' : '+' }} {{ b.label }}</span>
                    <b>{{ b.count ?? '失败' }}</b>
                  </div>
                  <div class="cmp-bucket-sum">
                    合计 {{ compareData.platform.latest.buckets.sum ?? '—' }} ·
                    {{ compareData.platform.latest.buckets.matchesDiff ? '账对上' : '与差额对不上' }}
                  </div>
                </div>
                <div class="cmp-dispute">
                  <el-tag :type="disputeTag(compareData.platform).type" size="small">{{ disputeTag(compareData.platform).text }}</el-tag>
                  <span v-if="compareData.platform.verdict" class="cmp-verdict">
                    裁决:{{ compareData.platform.verdict }}
                    <span v-if="compareData.platform.verdictAt">({{ compareData.platform.verdictAt.slice(0, 16).replace('T', ' ') }})</span>
                  </span>
                </div>
                <div class="cmp-reuse">
                  <div class="cmp-leftover-title">同一张口径卡,谁在读:</div>
                  <div v-for="t in compareData.platform.reusedBy" :key="t" class="cmp-reuse-item">· {{ t }}</div>
                </div>
              </template>
            </div>
          </el-col>
        </el-row>
      </div>
    </el-dialog>

    <!-- 指标口径卡编辑:编码只读;换月只写回编辑框,不直接保存 -->
    <el-dialog v-model="metricEditVisible" title="编辑口径卡" width="640px">
      <el-form :model="metricForm" label-width="90px">
        <el-form-item label="编码">
          <el-input :model-value="metricForm.metricCode" disabled />
        </el-form-item>
        <el-form-item label="指标名" required>
          <el-input v-model="metricForm.name" />
        </el-form-item>
        <el-form-item label="口径定义">
          <el-input v-model="metricForm.definition" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="计算公式">
          <el-input v-model="metricForm.formula" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="metricForm.owner" />
        </el-form-item>
        <el-form-item label="挂接概念">
          <el-select v-model="metricForm.conceptCode" clearable filterable style="width: 100%">
            <el-option v-for="c in conceptStore.concepts" :key="c.code" :label="`${c.name}(${c.code})`" :value="c.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据源">
          <el-select v-model="metricForm.dsCode" clearable style="width: 100%">
            <el-option v-for="d in dsOptions" :key="d.dsCode" :label="`${d.dsName || d.dsCode}(${d.dsCode})`" :value="d.dsCode" />
          </el-select>
        </el-form-item>
        <el-form-item label="探针SQL">
          <el-input v-model="metricForm.probeSql" type="textarea" :rows="4" placeholder="只读 SELECT,返回单数值" />
          <el-button size="small" style="margin-top: 4px" @click="openMonth(metricForm.probeSql, (v) => (metricForm.probeSql = v))">换月</el-button>
        </el-form-item>
        <el-form-item label="告警阈值">
          <el-input-number v-model="metricForm.warnThreshold" :min="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="metricEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingMetric" @click="submitMetricEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 换月:预览旧→新,确认后只写回编辑框 -->
    <el-dialog v-model="monthVisible" title="换月" width="480px" append-to-body>
      <p class="month-tip">把文本里的年月整体平移到目标月份(以文本里最早的年月为基准,窗口两侧一起走)</p>
      <el-input v-model="monthTarget" placeholder="目标月份,如 2026-10" style="width: 220px" />
      <div v-if="monthPreview && monthPreview.changes.length" class="month-preview">
        <div v-for="c in monthPreview.changes" :key="c.from">{{ c.from }} → {{ c.to }}</div>
      </div>
      <div v-else-if="monthPreview" class="month-preview">文本里没有识别到年月</div>
      <template #footer>
        <el-button @click="monthVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!monthPreview || !monthPreview.changes.length" @click="applyMonth">替换</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import {
  listTerms,
  createTerm,
  deleteTerm,
  listMetrics,
  searchGlossary,
  evaluateMetric,
  evaluateAllMetrics,
  updateMetric,
  listReconcile,
  runReconcile,
  createReconcile,
  updateReconcile,
  claimReconcile,
  resolveReconcile,
  compareReconcile
} from '../../api/glossary'
import { listDatasources } from '../../api/datasource'
import { useConceptStore } from '../../store/concept'
import { useUserStore } from '../../store/user'

const conceptStore = useConceptStore()
const userStore = useUserStore()
const route = useRoute()

// ---------- 语义搜索 ----------
const q = ref('')
const searching = ref(false)
const searched = ref(false)
const answer = ref('')
const hits = ref([])

const hitTagType = (type) =>
  ({ 术语: 'info', 概念: 'primary', 指标: 'warning', 文档: 'success' }[type] || 'info')

const doSearch = async () => {
  if (!q.value.trim()) {
    ElMessage.warning('请输入查询内容')
    return
  }
  searching.value = true
  try {
    const res = await searchGlossary(q.value.trim())
    searched.value = true
    answer.value = res.answer || ''
    hits.value = res.hits || []
  } finally {
    searching.value = false
  }
}

// ---------- 术语库 ----------
const tab = ref('terms')
const terms = ref([])
const termConcept = ref('')
const loadingTerms = ref(false)
const termPage = ref(1)
const termPageSize = 20

const loadTerms = async () => {
  loadingTerms.value = true
  try {
    terms.value = await listTerms(termConcept.value || undefined)
    termPage.value = 1
  } finally {
    loadingTerms.value = false
  }
}

// ---------- 编码体系筛选（本地过滤） ----------
const STANDARD_SYSTEMS = ['平台标准', 'SNOMED CT', 'ICD-10']
const codeSystemFilter = ref('')

const codeSystemTag = (row) => {
  const sys = row.codeSystem || row.sourceProduct
  if (sys === 'SNOMED CT') return 'primary'
  if (sys === 'ICD-10') return 'success'
  if (sys === '平台标准') return 'info'
  return 'warning'
}

// 产品方言：sourceProduct 不属于三大标准体系
const filteredTerms = computed(() => {
  if (!codeSystemFilter.value) return terms.value
  if (codeSystemFilter.value === '产品方言') {
    return terms.value.filter((t) => !STANDARD_SYSTEMS.includes(t.sourceProduct))
  }
  return terms.value.filter((t) => t.sourceProduct === codeSystemFilter.value)
})

const pagedTerms = computed(() => {
  const start = (termPage.value - 1) * termPageSize
  return filteredTerms.value.slice(start, start + termPageSize)
})

const termDialogVisible = ref(false)
const savingTerm = ref(false)
const termForm = reactive({ term: '', conceptCode: '', sourceProduct: '', termType: 'ALIAS' })

const openTermDialog = () => {
  Object.assign(termForm, {
    term: '',
    conceptCode: termConcept.value || '',
    sourceProduct: '',
    termType: 'ALIAS'
  })
  termDialogVisible.value = true
}

const submitTerm = async () => {
  if (!termForm.term || !termForm.conceptCode) {
    ElMessage.warning('请填写术语和所属概念')
    return
  }
  savingTerm.value = true
  try {
    await createTerm({ ...termForm })
    ElMessage.success('术语已保存')
    termDialogVisible.value = false
    loadTerms()
  } finally {
    savingTerm.value = false
  }
}

const removeTerm = async (row) => {
  await ElMessageBox.confirm(`确认删除术语「${row.term}」？`, '提示', { type: 'warning' })
  await deleteTerm(row.id)
  ElMessage.success('已删除')
  loadTerms()
}

// ---------- 指标库 ----------
const metrics = ref([])
const loadingMetrics = ref(false)
const metricPage = ref(1)
const metricPageSize = 12
const highlightCode = ref('') // 深链定位的指标（口径卡「去统一口径页」跳入时高亮）

// 筛选：关键字 / 业务域（来自绑定概念）/ 监控状态（来自巡检实测）
const metricKw = ref('')
const metricDomain = ref('')
const metricStatus = ref('')

// 指标 → 绑定概念 → 业务域；概念未加载或未绑定时返回空
const metricDomainOf = (m) => {
  const c = conceptStore.concepts.find((x) => x.code === m.conceptCode)
  return c?.domainCode || ''
}

const metricDomains = computed(() => {
  const set = new Set(metrics.value.map(metricDomainOf).filter(Boolean))
  return [...set]
})

const metricStats = computed(() => {
  const monthPrefix = new Date().toISOString().slice(0, 7)
  return {
    total: metrics.value.length,
    monitored: metrics.value.filter(hasProbe).length,
    alarmed: metrics.value.filter((m) => displayAlarm(m)).length,
    newThisMonth: metrics.value.filter(
      (m) => (m.createdAt || '').startsWith(monthPrefix)
    ).length
  }
})

const filteredMetrics = computed(() => {
  let list = metrics.value
  const kw = metricKw.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(
      (m) => m.name?.toLowerCase().includes(kw) || m.metricCode?.toLowerCase().includes(kw)
    )
  }
  if (metricDomain.value) {
    list = list.filter((m) => metricDomainOf(m) === metricDomain.value)
  }
  if (metricStatus.value === 'alarm') {
    list = list.filter((m) => displayAlarm(m))
  } else if (metricStatus.value === 'ok') {
    list = list.filter((m) => hasProbe(m) && !displayAlarm(m))
  } else if (metricStatus.value === 'none') {
    list = list.filter((m) => !hasProbe(m))
  }
  return list
})

const pagedMetrics = computed(() => {
  const start = (metricPage.value - 1) * metricPageSize
  return filteredMetrics.value.slice(start, start + metricPageSize)
})

watch([metricKw, metricDomain, metricStatus], () => {
  metricPage.value = 1
})

// ---------- 指标口径卡编辑 ----------
const metricEditVisible = ref(false)
const savingMetric = ref(false)
const metricForm = reactive({
  id: null, metricCode: '', name: '', definition: '', formula: '',
  owner: '', conceptCode: '', dsCode: '', probeSql: '', warnThreshold: null
})

const openMetricEdit = async (m) => {
  Object.assign(metricForm, {
    id: m.id,
    metricCode: m.metricCode,
    name: m.name || '',
    definition: m.definition || '',
    formula: m.formula || '',
    owner: m.owner || '',
    conceptCode: m.conceptCode || '',
    dsCode: m.dsCode || '',
    probeSql: m.probeSql || '',
    warnThreshold: m.warnThreshold ?? null
  })
  metricEditVisible.value = true
  if (!dsOptions.value.length) {
    try {
      dsOptions.value = await listDatasources()
    } catch (e) {
      // 下拉留空不影响其余字段
    }
  }
}

const submitMetricEdit = async () => {
  if (!metricForm.name.trim()) {
    ElMessage.warning('指标名必填')
    return
  }
  savingMetric.value = true
  try {
    await updateMetric({ ...metricForm, name: metricForm.name.trim() })
    ElMessage.success('口径卡已保存')
    metricEditVisible.value = false
    loadMetrics()
  } finally {
    savingMetric.value = false
  }
}

// ---------- 换月(编辑弹窗通用) ----------
// 锚月平移:以文本中最早的年月为基准整体平移,窗口式 SQL(>= '2026-09-01' AND < '2026-10-01')
// 两侧一起走;不做字面替换——把上界 '2026-10-01' 替换成目标月同日会把开区间收进同月,窗口清零。
const shiftMonth = (text, target) => {
  const found = [...new Set(text.match(/20\d{2}-\d{2}/g) || [])]
  if (!found.length) return { text, changes: [] }
  const anchor = [...found].sort()[0]
  const [ay, am] = anchor.split('-').map(Number)
  const [ty, tm] = target.split('-').map(Number)
  const delta = ty * 12 + tm - (ay * 12 + am)
  const shifts = {}
  found.forEach((u) => {
    const [y, mm] = u.split('-').map(Number)
    const n = y * 12 + mm + delta
    shifts[u] = `${Math.floor((n - 1) / 12)}-${String(((n - 1) % 12) + 1).padStart(2, '0')}`
  })
  return {
    text: text.replace(/20\d{2}-\d{2}/g, (s) => shifts[s]),
    changes: [...found].sort().map((u) => ({ from: u, to: shifts[u] }))
  }
}

const monthVisible = ref(false)
const monthTarget = ref('')
const monthText = ref('')
const monthSetter = ref(null)
const monthPreview = computed(() => {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(monthTarget.value)) return null
  return shiftMonth(monthText.value, monthTarget.value)
})

const openMonth = (text, setter) => {
  monthText.value = text || ''
  monthSetter.value = setter
  monthTarget.value = ''
  monthVisible.value = true
}

const applyMonth = () => {
  const p = monthPreview.value
  if (!p || !p.changes.length) return
  monthSetter.value(p.text)
  monthVisible.value = false
  ElMessage.success(`已替换 ${p.changes.length} 处年月,请检查后保存`)
}

const loadMetrics = async () => {
  loadingMetrics.value = true
  try {
    metrics.value = await listMetrics()
    metricPage.value = 1
  } finally {
    loadingMetrics.value = false
  }
}

// ---------- 指标监控 ----------
const inspecting = ref(false)
const inspected = ref(false)
const evaluatingCode = ref('')
const evalMap = ref({})

const hasProbe = (m) => !!(m.dsCode && m.probeSql)

const displayValue = (m) => {
  const ev = evalMap.value[m.metricCode]
  if (ev) return ev.value
  return m.lastVal ?? null
}

const displayTime = (m) => {
  const ev = evalMap.value[m.metricCode]
  if (ev) return ev.evaluatedAt
  return m.lastEvalAt || ''
}

const displayAlarm = (m) => {
  const ev = evalMap.value[m.metricCode]
  if (ev) return ev.alarm
  return m.warnThreshold != null && m.lastVal != null && m.lastVal > m.warnThreshold
}

const alarmedMetrics = computed(() => metrics.value.filter((m) => displayAlarm(m)))

const inspectAll = async () => {
  inspecting.value = true
  const loadingInstance = ElLoading.service({
    text: '正在执行实测探针...',
    background: 'rgba(255, 255, 255, 0.7)'
  })
  try {
    const results = await evaluateAllMetrics()
    const map = { ...evalMap.value }
    for (const r of results || []) {
      map[r.metricCode] = r
    }
    evalMap.value = map
    inspected.value = true
    const alarms = (results || []).filter((r) => r.alarm)
    if (alarms.length) {
      ElMessage.warning(`巡检完成：${alarms.length} 项指标告警`)
    } else {
      ElMessage.success('巡检完成：全部指标正常')
    }
  } finally {
    loadingInstance.close()
    inspecting.value = false
  }
}

const runEvaluate = async (m) => {
  evaluatingCode.value = m.metricCode
  try {
    const res = await evaluateMetric(m.metricCode)
    evalMap.value = { ...evalMap.value, [res.metricCode]: res }
    if (res.alarm) {
      ElMessage.warning(`${m.name} 实测值 ${res.value}，触发告警（阈值 ${res.warnThreshold}）`)
    } else {
      ElMessage.success(`${m.name} 实测值 ${res.value}，正常`)
    }
  } finally {
    evaluatingCode.value = ''
  }
}

// ---------- 对账 ----------
const recGroups = ref([])
const loadingRec = ref(false)
const runningCode = ref('')

const metricNameOf = (code) =>
  metrics.value.find((m) => m.metricCode === code)?.name || code

// 下钻行是任意 SQL 的结果,列不固定:按出现顺序取前几列展示
const drillCols = (rows) => {
  const keys = []
  for (const r of rows || []) {
    for (const k of Object.keys(r || {})) {
      if (!keys.includes(k)) keys.push(k)
    }
    if (keys.length >= 6) break
  }
  return keys
}

const loadReconcile = async () => {
  loadingRec.value = true
  try {
    recGroups.value = await listReconcile()
  } finally {
    loadingRec.value = false
  }
}

// 跑完把返回(含 drillRows)就地塞进卡片,历史再异步刷新
const runOne = async (g) => {
  runningCode.value = g.groupCode
  try {
    const r = await runReconcile(g.groupCode)
    g.latest = { ...r }
    loadReconcile()
    if (r.errorMsg) {
      ElMessage.warning(`对账完成,但有口径实测失败(${r.errorMsg}),差额未计算`)
    } else if (r.diffValue === 0) {
      ElMessage.success(`${r.name}:口径一致(${Object.values(r.values || {}).join(' / ')})`)
    } else if (r.diffValue == null) {
      ElMessage.info(`${r.name}:差额算不出(口径值缺失或非数值),请检查各口径探针`)
    } else {
      const ticket = r.disputeStatus === 'OPEN' ? ',工单已打开待认领' : ''
      ElMessage.warning(`${r.name}:口径间差额 ${r.diffValue}${ticket},可展开差额明细定位`)
    }
  } finally {
    runningCode.value = ''
  }
}

const historyVisible = ref(false)
const historyGroup = ref(null)
const historyRuns = ref([])

const showHistory = (g) => {
  historyGroup.value = g
  historyRuns.value = g.runs || []
  historyVisible.value = true
}

// ---------- 分歧状态:待认领/对账中/已裁决 ----------
const disputeTag = (g) => {
  if (g.disputeStatus === 'RESOLVED') return { text: '已裁决', type: 'success' }
  if (g.disputeStatus === 'CLAIMED') return { text: `对账中·${g.disputeOwner || '?'}`, type: 'primary' }
  return { text: '待认领', type: 'warning' }
}

// ---------- 直连数据库 vs 本体平台 对照 ----------
const compareVisible = ref(false)
const compareLoading = ref(false)
const compareGroup = ref(null)
const compareData = ref(null)

const openCompare = async (g) => {
  compareGroup.value = g
  compareData.value = null
  compareVisible.value = true
  compareLoading.value = true
  try {
    compareData.value = await compareReconcile(g.groupCode)
  } finally {
    compareLoading.value = false
  }
}

// 左列终端风输出:直接用后端实时代跑的结果拼
const compareTermLines = computed(() => {
  const d = compareData.value
  if (!d) return ''
  const v = d.script.values || {}
  const b = d.script.buckets || {}
  const L = []
  L.push(`病案口径(首页已交)  ${v['病案口径(首页已交)'] ?? '—'}`)
  L.push(`结算口径(已结算)    ${v['结算口径(已结算)'] ?? '—'}`)
  L.push(`HIS 出院(含未交首页) ${v['HIS 出院(含首页未交)'] ?? '—'}`)
  L.push(`住院费用  病案 ${v['病案口径费用'] ?? '—'} / 结算 ${v['结算口径费用'] ?? '—'}`)
  L.push(`两口径差额            ${d.script.diff ?? '算不出'}`)
  L.push('')
  L.push(`跨月结算   ${b['跨月结算'] ?? '—'}`)
  L.push(`出院未结算 ${b['出院未结算'] ?? '—'}`)
  L.push(`病案未回收 ${b['病案未回收'] ?? '—'}`)
  if (d.script.check) L.push(d.script.check)
  return L.join('\n')
})

const doClaim = async (g) => {
  await ElMessageBox.confirm(
    '认领后页面上会公开谁在牵头对这笔分歧(以当前登录账号为准)',
    '认领分歧',
    { confirmButtonText: '认领', cancelButtonText: '取消', type: 'info' }
  )
  await claimReconcile(g.groupCode)
  ElMessage.success('已认领')
  loadReconcile()
}

const resolveVisible = ref(false)
const resolvingGroup = ref(null)
const resolving = ref(false)
const resolveForm = reactive({ verdict: '', note: '' })

const openResolve = (g) => {
  resolvingGroup.value = g
  Object.assign(resolveForm, { verdict: '', note: '' })
  resolveVisible.value = true
}

const submitResolve = async () => {
  if (!resolveForm.verdict.trim()) {
    ElMessage.warning('裁决结论必填:以哪侧口径为准')
    return
  }
  resolving.value = true
  try {
    await resolveReconcile(resolvingGroup.value.groupCode, resolveForm.verdict.trim(), resolveForm.note.trim())
    ElMessage.success('裁决已入档')
    resolveVisible.value = false
    loadReconcile()
  } finally {
    resolving.value = false
  }
}

// ---------- 新建/编辑对账组(弹窗双用) ----------
const recDialogVisible = ref(false)
const savingRec = ref(false)
const dsOptions = ref([])
const recForm = reactive({
  id: null,
  name: '',
  groupCode: '',
  definition: '',
  owner: '',
  metricCodes: [],
  drillDsCode: '',
  drillSql: '',
  buckets: [],
  scheduled: false
})
const recDialogTitle = computed(() => (recForm.id ? '编辑对账组' : '新建对账组'))

const openRecDialog = async () => {
  Object.assign(recForm, {
    id: null,
    name: '',
    groupCode: '',
    definition: '',
    owner: '',
    metricCodes: [],
    drillDsCode: '',
    drillSql: '',
    buckets: [],
    scheduled: false
  })
  recDialogVisible.value = true
  if (!dsOptions.value.length) {
    try {
      dsOptions.value = await listDatasources()
    } catch (e) {
      // 下拉留空不影响其余字段;下钻数据源本就是可选项
    }
  }
}

const openRecEdit = (g) => {
  let buckets = []
  try {
    buckets = (g.bucketsJson ? JSON.parse(g.bucketsJson) : []).map((b) => ({
      label: b.label || '',
      dsCode: b.dsCode || '',
      sign: b.sign === -1 ? -1 : 1,
      sql: b.sql || ''
    }))
  } catch (e) {
    buckets = []
  }
  Object.assign(recForm, {
    id: g.id,
    name: g.name || '',
    groupCode: g.groupCode || '',
    definition: g.definition || '',
    owner: g.owner || '',
    metricCodes: Array.isArray(g.metricCodes) ? g.metricCodes : (g.metricCodes || '').split(',').filter(Boolean),
    drillDsCode: g.drillDsCode || '',
    drillSql: g.drillSql || '',
    buckets,
    scheduled: g.scheduled === 1
  })
  recDialogVisible.value = true
  if (!dsOptions.value.length) {
    listDatasources().then((l) => (dsOptions.value = l)).catch(() => {})
  }
}

const submitRec = async () => {
  if (!recForm.name.trim() || !recForm.groupCode.trim()) {
    ElMessage.warning('组名和编码必填')
    return
  }
  if (recForm.metricCodes.length < 2) {
    ElMessage.warning('至少选 2 个口径指标——单一口径没有对账对象')
    return
  }
  savingRec.value = true
  try {
    const payload = {
      id: recForm.id,
      name: recForm.name.trim(),
      groupCode: recForm.groupCode.trim(),
      definition: recForm.definition,
      owner: recForm.owner,
      metricCodes: recForm.metricCodes.join(','),
      drillDsCode: recForm.drillDsCode,
      drillSql: recForm.drillSql,
      bucketsJson: JSON.stringify(recForm.buckets.filter((b) => b.label.trim() && b.dsCode && b.sql.trim())),
      scheduled: recForm.scheduled ? 1 : 0
    }
    if (recForm.id) {
      await updateReconcile(payload)
      ElMessage.success('对账组已保存')
    } else {
      await createReconcile(payload)
      ElMessage.success('对账组已创建')
    }
    recDialogVisible.value = false
    loadReconcile()
  } finally {
    savingRec.value = false
  }
}

// 承接问数页口径卡深链（/glossary?metric=XXX）：切到指标库、清筛选、翻页并高亮定位；
// 找不到时如实告知，不静默失败
const locateMetric = (code) => {
  if (!metrics.value.length) {
    ElMessage.info('指标列表未加载，无法定位指标')
    return
  }
  if (!metrics.value.some((m) => m.metricCode === code)) {
    ElMessage.info(`未在指标库找到指标 ${code}，可能已被删除`)
    return
  }
  highlightCode.value = code
  tab.value = 'metrics'
  metricKw.value = ''
  metricDomain.value = ''
  metricStatus.value = ''
  const idx = filteredMetrics.value.findIndex((m) => m.metricCode === code)
  if (idx >= 0) metricPage.value = Math.floor(idx / metricPageSize) + 1
  nextTick(() => {
    document.getElementById(`metric-${code}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })
}

onMounted(async () => {
  conceptStore.fetchAll()
  loadTerms()
  try {
    await loadMetrics()
  } catch (e) {
    // 列表加载失败保持页面空态；深链定位会提示「未加载」
  }
  loadReconcile()
  const code = route.query.metric
  if (code) locateMetric(String(code))
})
</script>

<style scoped>
.search-bar {
  max-width: 760px;
}

.answer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.filter-bar {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
}

.term-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.metric-pager {
  justify-content: flex-end;
}

.std-code {
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
}

.metric-card {
  margin-bottom: 16px;
}

.metric-card.metric-highlight {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-light, rgba(45, 138, 126, 0.25));
}

.metric-name {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 8px;
}

.metric-code {
  font-size: 12px;
  color: #909399;
  font-weight: 400;
  margin-left: 6px;
}

.metric-def {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
  min-height: 40px;
}

.metric-formula {
  margin: 0 0 8px;
  padding: 8px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  color: #606266;
}

.metric-owner {
  font-size: 12px;
  color: #909399;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.metric-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.metric-header-title {
  font-size: 14px;
  font-weight: 600;
}

.alarm-alert {
  margin-bottom: 12px;
}

.metric-monitor {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
  min-height: 30px;
}

.metric-value {
  font-size: 26px;
  font-weight: 700;
  color: #303133;
}

.metric-value.alarm {
  color: #f56c6c;
}

.metric-time {
  font-size: 12px;
  color: #909399;
}

.metric-no-val {
  font-size: 12px;
  color: #c0c4cc;
}

.metric-threshold {
  margin-left: 8px;
  color: #e6a23c;
}

/* ---------- 指标统计头与筛选工具栏 ---------- */
.metric-stats {
  display: flex;
  gap: 40px;
  padding: 14px 18px;
  margin-bottom: 12px;
  background: var(--el-bg-color-page, #f7f8fa);
  border-radius: 8px;
}

.mstat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-primary);
}

.mstat-value.mstat-warn {
  color: var(--el-color-danger);
}

.mstat-label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 2px;
}

.metric-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.metric-toolbar-tip {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
}

/* ---------- 对账卡 ---------- */
.rec-tip {
  font-size: 12px;
  color: var(--text-muted);
}

.rec-def {
  min-height: 0;
  margin-bottom: 10px;
}

.rec-values {
  background: var(--el-bg-color-page, #f7f8fa);
  border-radius: 8px;
  padding: 10px 14px;
  margin-bottom: 10px;
}

.rec-value-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 0;
}

.rec-value-label {
  font-size: 13px;
  color: #606266;
}

.rec-value-num {
  font-size: 22px;
  font-weight: 700;
  color: #303133;
}

.rec-diff-row {
  border-top: 1px dashed #dcdfe6;
  margin-top: 4px;
  padding-top: 8px;
}

.rec-diff-ok {
  color: var(--el-color-success);
}

.rec-diff-none {
  color: #909399;
}

.rec-diff-bad {
  color: var(--el-color-danger);
}

.rec-err {
  margin-bottom: 10px;
}

.rec-no-run {
  font-size: 13px;
  color: #c0c4cc;
  margin-bottom: 10px;
}

.rec-drill {
  margin-bottom: 10px;
}

.rec-drill-fail {
  font-size: 12px;
  color: #e6a23c;
  margin-bottom: 10px;
}

.rec-bucket-plus {
  color: var(--el-color-success);
  font-weight: 700;
}

.rec-bucket-minus {
  color: var(--el-color-danger);
  font-weight: 700;
}

.rec-bucket-sum {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}

.bucket-row { display: flex; flex-direction: column; gap: 4px; padding: 8px; border: 1px dashed #dcdfe6; border-radius: 4px; margin-bottom: 6px; }
.bucket-line { display: flex; gap: 6px; align-items: center; }
.bucket-tip { color: #909399; font-size: 12px; }

.cmp-buckets {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #f5f7fa;
  border-radius: 6px;
}

.cmp-bucket-row {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #606266;
  line-height: 1.9;
}

.cmp-bucket-sum {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  border-top: 1px dashed #ebeef5;
  padding-top: 6px;
}

.rec-verdict {
  font-size: 13px;
  color: var(--el-color-success);
  background: var(--el-color-success-light-9, #f0f9eb);
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 10px;
}

.rec-verdict-note {
  color: #909399;
  font-size: 12px;
}

.rec-verdict-meta {
  color: #909399;
  font-size: 12px;
}

.rec-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.rec-actions .el-button + .el-button {
  margin-left: 0;
}

.rec-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
}

.rec-hist-val {
  margin-right: 12px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
}

/* ---------- 直连数据库 vs 本体平台 对照 ---------- */
.cmp-col {
  min-height: 420px;
}

.cmp-head {
  padding-bottom: 8px;
  margin-bottom: 12px;
  border-bottom: 1px solid #ebeef5;
}

.cmp-name {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.cmp-sub {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.cmp-term {
  margin: 0 0 12px;
  padding: 12px 14px;
  background: #1e1e2e;
  color: #cdd6f4;
  border-radius: 6px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-x: auto;
}

.cmp-leftover-title {
  font-size: 12px;
  font-weight: 600;
  color: #606266;
  margin-bottom: 6px;
}

.cmp-reuse-item {
  font-size: 12px;
  color: #909399;
  line-height: 1.9;
}

.cmp-card {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #f5f7fa;
  border-radius: 6px;
}

.cmp-card-name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.cmp-card-code {
  margin-left: 8px;
  font-family: 'SFMono-Regular', Consolas, Menlo, monospace;
  font-size: 12px;
  color: #909399;
}

.cmp-card-def {
  margin-top: 2px;
  font-size: 12px;
  color: #606266;
}

.cmp-run {
  padding: 10px 12px;
  margin-bottom: 8px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
}

.cmp-run-diff {
  font-size: 14px;
  color: #606266;
}

.cmp-run-diff b {
  font-size: 20px;
  margin-left: 4px;
}

.cmp-run-diff b.ok {
  color: #67c23a;
}

.cmp-run-diff b.bad {
  color: #f56c6c;
}

.cmp-run-meta {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.cmp-dispute {
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
}

.cmp-verdict {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.cmp-reuse {
  margin-top: 4px;
}

/* ---------- 换月弹窗 ---------- */
.month-tip { margin: 0 0 10px; color: #909399; font-size: 12px; }
.month-preview { margin-top: 10px; padding: 8px 12px; background: #f5f7fa; border-radius: 4px; font-family: monospace; font-size: 13px; color: #303133; }
</style>
