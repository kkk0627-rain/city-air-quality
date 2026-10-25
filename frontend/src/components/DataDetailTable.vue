<template>
  <!--
    任务 27：数据详情表格（F8）。
    - 纯展示组件，分页由父组件驱动：父层持有 page 并调 A2 接口，本组件只 emit 页码变化；
    - 每页条数固定 10（需求指定），翻页控件显示总条数与总页数；
    - 行可点击，emit('detail', id)，由父组件用 A4 接口拉单条详情。
  -->
  <div class="table-wrap">
    <table class="detail-table">
      <thead>
        <tr>
          <th>城市</th>
          <th>日期</th>
          <th class="num">{{ meta.label }}<span v-if="meta.unit" class="unit">{{ meta.unit }}</span></th>
          <th class="num">AQI</th>
          <th>等级</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="loading">
          <td colspan="5" class="placeholder">加载中…</td>
        </tr>
        <tr v-else-if="rows.length === 0">
          <td colspan="5" class="placeholder">当前筛选条件下没有数据</td>
        </tr>
        <tr
          v-for="row in rows"
          v-else
          :key="row.id"
          class="clickable"
          @click="emit('detail', row.id)"
        >
          <td>{{ row.city }}</td>
          <td>{{ row.date }}</td>
          <td class="num">{{ fmt(row.value) }}</td>
          <td class="num">{{ row.aqi }}</td>
          <td>
            <span class="badge" :style="badgeStyle(row.level)">{{ row.levelLabel }}</span>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="pager">
      <button type="button" class="page-btn" :disabled="loading || page <= 1" @click="go(page - 1)">
        上一页
      </button>
      <span class="page-info">
        第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条
      </span>
      <button type="button" class="page-btn" :disabled="loading || page >= totalPages" @click="go(page + 1)">
        下一页
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { AQI_LEVELS, getMetricMeta } from '../api/airQuality.js'

const props = defineProps({
  /** A2 返回的分页结果 { total, page, size, list } */
  pageData: { type: Object, default: () => ({ total: 0, page: 1, size: 10, list: [] }) },
  metric: { type: String, default: 'aqi' },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['page-change', 'detail'])

const rows = computed(() => props.pageData.list || [])
const total = computed(() => Number(props.pageData.total) || 0)
const page = computed(() => Number(props.pageData.page) || 1)
const size = computed(() => Number(props.pageData.size) || 10)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))
const meta = computed(() => getMetricMeta(props.metric))

function fmt(value) {
  return value === null || value === undefined || value === '' ? '—' : value
}

/**
 * 等级徽章配色：按等级编号直接查表。
 *
 * 注意不能传等级编号给 getLevelMeta——它的入参是 AQI 数值
 * （例如 level=3 会被当成 AQI=3，错判成"优"）。
 */
function badgeStyle(level) {
  const found = AQI_LEVELS.find((item) => item.level === level)
  return {
    background: found ? found.color : '#9ca3af',
    color: found ? found.textColor : '#ffffff'
  }
}

function go(next) {
  if (next < 1 || next > totalPages.value || next === page.value) return
  emit('page-change', next)
}
</script>

<style scoped>
.table-wrap {
  width: 100%;
}

.detail-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.detail-table th,
.detail-table td {
  padding: 9px 10px;
  text-align: left;
  border-bottom: 1px solid #f1f0ec;
  color: #1f2937;
}

.detail-table th {
  font-weight: 500;
  color: #6b7280;
  background: #faf9f6;
}

.detail-table .num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.unit {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 400;
  color: #9ca3af;
}

.clickable {
  cursor: pointer;
}

.clickable:hover {
  background: #f7f9fc;
}

.placeholder {
  padding: 36px 0;
  text-align: center;
  color: #9ca3af;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 12px 2px 4px;
}

.page-btn {
  padding: 6px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #374151;
  font-size: 12px;
  cursor: pointer;
}

.page-btn:disabled {
  color: #b4b2a9;
  cursor: not-allowed;
}

.page-info {
  font-size: 12px;
  color: #6b7280;
}
</style>
