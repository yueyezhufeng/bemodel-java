<template>
  <div ref="el" class="graph-canvas" :data-node-count="nodes.length" :data-edge-count="edges.length" :style="{ height, width }"></div>
</template>

<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent, GraphicComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([GraphChart, TooltipComponent, LegendComponent, GraphicComponent, CanvasRenderer])

const props = defineProps({
  // echarts graph 节点对象数组：{ id, name, category, x?, y?, itemStyle?, symbolSize?, label?, ... }
  nodes: { type: Array, default: () => [] },
  // 边对象数组：{ source, target, label?, lineStyle?, ... }
  edges: { type: Array, default: () => [] },
  // 分类（用于着色与图例开关）：{ name, itemStyle? }
  categories: { type: Array, default: () => [] },
  // 'none'（节点自带 x/y 固定布局）| 'force'（力导向）
  layout: { type: String, default: 'none' },
  height: { type: String, default: '600px' },
  initialZoom: { type: Number, default: 1 },
  width: { type: String, default: '100%' },
  loading: { type: Boolean, default: false },
  showLegend: { type: Boolean, default: false },
  // 分层色带等底层图形元素（echarts graphic）
  graphic: { type: Array, default: () => [] },
  // 自定义 tooltip：接收 echarts params，返回 html 字符串
  tooltipFormatter: { type: Function, default: null }
})

const emit = defineEmits(['node-click'])

const el = ref(null)
let chart = null
let resizeObserver = null

const render = () => {
  if (!chart) return
  const isForce = props.layout === 'force'
  // 固定布局：显式铺满容器，避免 graph 默认的 0.8 aspect 收缩破坏数据坐标与 graphic 的像素对齐
  const fixedLayout = isForce
    ? {}
    : { left: 0, top: 0, width: el.value?.clientWidth, height: el.value?.clientHeight }
  chart.setOption(
    {
      textStyle: { fontFamily: '"Times New Roman", "Source Han Serif SC", serif' },
      tooltip: {
        trigger: 'item',
        formatter: (p) =>
          props.tooltipFormatter ? props.tooltipFormatter(p) : p.data?.name ?? ''
      },
      legend: props.showLegend
        ? {
            data: props.categories.map((c) => c.name),
            top: 0,
            icon: 'circle',
            itemWidth: 10,
            itemHeight: 10,
            textStyle: { fontSize: 12, fontFamily: '"Times New Roman", "Source Han Serif SC", serif' }
          }
        : undefined,
      graphic: props.graphic,
      series: [
        {
          type: 'graph',
          layout: props.layout,
          ...fixedLayout,
          data: props.nodes,
          links: props.edges,
          categories: props.categories,
          roam: isForce,
          zoom: isForce ? props.initialZoom : 1,
          edgeSymbol: ['none', 'arrow'],
          edgeSymbolSize: 7,
          label: { show: true, position: 'right', fontSize: 12, color: '#303133' },
          lineStyle: { color: '#c0c4cc', curveness: 0.08, width: 1.2 },
          emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
          ...(isForce
            ? {
                force: { repulsion: 220, edgeLength: [60, 140], gravity: 0.08 },
                scaleLimit: { min: 0.3, max: 3 }
              }
            : {})
        }
      ]
    },
    { notMerge: true }
  )
}

watch(
  () => [props.nodes, props.edges, props.categories, props.layout, props.graphic],
  render,
  { deep: true }
)

watch(
  () => props.loading,
  (v) => {
    if (!chart) return
    v ? chart.showLoading('default', { text: '加载中…', color: '#409eff' }) : chart.hideLoading()
  }
)

onMounted(() => {
  chart = echarts.init(el.value)
  chart.on('click', (p) => {
    if (p.dataType === 'node') emit('node-click', p.data)
  })
  render()
  if (props.loading) chart.showLoading('default', { text: '加载中…', color: '#409eff' })
  resizeObserver = new ResizeObserver(() => {
    if (!chart) return
    chart.resize()
    render()
  })
  resizeObserver.observe(el.value)
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
  chart = null
})

defineExpose({ resize: () => chart?.resize() })
</script>

<style scoped>
.graph-canvas {
  width: 100%;
}
</style>
