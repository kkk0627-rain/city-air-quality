<template>
  <!--
    任务 24：左侧筛选区（F1/F2/F3/F4）。
    职责：只负责收集筛选条件，不碰任何数据请求。
    父组件通过 v-model 拿到 filter 对象，再自行调接口。
  -->
  <section class="filter-panel">
    <div class="block">
      <div class="block-head">
        <span class="label">城市</span>
        <span class="count">已选 {{ modelValue.cityIds.length }} / {{ cities.length }}</span>
      </div>
      <div class="chips">
        <button
          v-for="c in cities"
          :key="c.code"
          type="button"
          :class="['chip', { active: selectedSet.has(c.code) }]"
          @click="toggleCity(c.code)"
        >
          {{ c.name }}
        </button>
        <span v-if="cities.length === 0" class="empty">加载中…</span>
      </div>
      <div class="ops">
        <button type="button" class="link" @click="selectAll">全选</button>
        <button type="button" class="link" @click="clearCities">清空</button>
      </div>
    </div>

    <div class="block">
      <div class="block-head">
        <span class="label">指标</span>
      </div>
      <div class="chips">
        <button
          v-for="m in metrics"
          :key="m.key"
          type="button"
          :class="['chip', { active: modelValue.metric === m.key }]"
          @click="patch({ metric: m.key })"
        >
          {{ m.label }}
        </button>
      </div>
    </div>

    <div class="block">
      <div class="block-head">
        <span class="label">时间范围</span>
        <span class="count">{{ range.min || '—' }} ~ {{ range.max || '—' }}</span>
      </div>
      <div class="dates">
        <input v-model="startLocal" type="date" :min="range.min" :max="endLocal || range.max" />
        <span class="sep">至</span>
        <input v-model="endLocal" type="date" :min="startLocal || range.min" :max="range.max" />
      </div>
      <div class="ops">
        <button v-for="q in quickRanges" :key="q.label" type="button" class="link" @click="applyQuick(q)">
          {{ q.label }}
        </button>
      </div>
    </div>

    <button class="primary" type="button" :disabled="loading || disabled" @click="emit('search')">
      {{ loading ? '查询中…' : '查询' }}
    </button>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </section>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 筛选条件 { cityIds: number[], metric: string, start: string, end: string } */
  modelValue: { type: Object, required: true },
  /** 城市列表 [{ code, name, province, region }] */
  cities: { type: Array, default: () => [] },
  /** 可选指标 [{ key, label, unit }] */
  metrics: { type: Array, default: () => [] },
  /** 数据集日期边界 { min, max } */
  range: { type: Object, default: () => ({ min: '', max: '' }) },
  loading: { type: Boolean, default: false },
  /** 辅助提示，如"请至少选择一个城市" */
  hint: { type: String, default: '' },
  /** 是否禁用查询按钮 */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'search'])

const quickRanges = [
  { label: '近 7 天', days: 7 },
  { label: '近 30 天', days: 30 },
  { label: '近 90 天', days: 90 },
  { label: '全年', days: 365 }
]

const selectedSet = computed(() => new Set(props.modelValue.cityIds))

const startLocal = computed({
  get: () => props.modelValue.start,
  set: (value) => patch({ start: value })
})

const endLocal = computed({
  get: () => props.modelValue.end,
  set: (value) => patch({ end: value })
})

function patch(part) {
  emit('update:modelValue', { ...props.modelValue, ...part })
}

function toggleCity(code) {
  const ids = selectedSet.value.has(code)
    ? props.modelValue.cityIds.filter((item) => item !== code)
    : [...props.modelValue.cityIds, code]
  patch({ cityIds: ids })
}

function selectAll() {
  patch({ cityIds: props.cities.map((c) => c.code) })
}

function clearCities() {
  patch({ cityIds: [] })
}

/**
 * 快捷区间：以数据集末日为终点，向前推 days-1 天（含端点）。
 *
 * <b>不能用 toISOString()</b>：它按 UTC 输出，在 UTC+8 环境下会把本地
 * 00:00 推回前一天，导致整个区间偏移一天。这里手工按本地字段拼 yyyy-MM-dd。
 */
function applyQuick({ days }) {
  const max = props.range.max
  if (!max) return
  const cursor = new Date(`${max}T00:00:00`)
  cursor.setDate(cursor.getDate() - (days - 1))
  const start = toISODate(cursor)
  patch({ start: start < props.range.min ? props.range.min : start, end: max })
}

function toISODate(date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}
</script>

<style scoped>
.filter-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 18px;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.block-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.label {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
}

.count,
.hint {
  font-size: 12px;
  color: #6b7280;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 5px 11px;
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  background: #ffffff;
  color: #374151;
  font-size: 12px;
  cursor: pointer;
}

.chip:hover {
  border-color: #9ca3af;
}

.chip.active {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 500;
}

.ops {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.link {
  padding: 0;
  border: none;
  background: none;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
}

.dates {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dates input {
  flex: 1;
  min-width: 0;
  padding: 5px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #1f2937;
  font-size: 12px;
}

.sep {
  font-size: 12px;
  color: #6b7280;
}

.primary {
  padding: 9px 16px;
  border: none;
  border-radius: 8px;
  background: #2563eb;
  color: #ffffff;
  font-size: 13px;
  cursor: pointer;
}

.primary:disabled {
  background: #93b4f0;
  cursor: not-allowed;
}

.empty {
  font-size: 12px;
  color: #9ca3af;
}
</style>
