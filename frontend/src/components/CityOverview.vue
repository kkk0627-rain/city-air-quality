<template>
  <!--
    任务 14：城市概览卡。
    纯展示组件：数据由 App.vue 传入，本组件不自己发请求，
    便于单测/复用，也避免三个组件各自重复请求同一份数据。
  -->
  <div class="overview-card">
    <div v-if="loading" class="placeholder">加载中…</div>

    <div v-else-if="!overview" class="placeholder">暂无数据</div>

    <div v-else class="overview-body" :style="{ borderLeftColor: meta.color }">
      <div class="left">
        <div class="city">{{ overview.cityName }}</div>
        <div class="date">{{ overview.monitorDate }} 监测日</div>
      </div>

      <div class="center">
        <div class="aqi" :style="{ color: meta.color }">{{ overview.aqi }}</div>
        <div class="aqi-label">AQI</div>
      </div>

      <div class="right">
        <span class="badge" :style="{ background: meta.color, color: meta.textColor }">
          {{ meta.label }}
        </span>
        <div class="metric">
          <span class="k">首要污染物</span>
          <span class="v">{{ primary }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { getLevelMeta, formatPrimary } from '../api/air.js'

const props = defineProps({
  overview: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

// 等级配色统一从 air.js 取，保证与趋势图、排行榜三处完全一致
const meta = computed(() => getLevelMeta(props.overview ? props.overview.aqi : null))

// 后端在 AQI<=50 时不返回 primaryPollutant 字段（non_null 策略），这里补“无”
const primary = computed(() => formatPrimary(props.overview && props.overview.primaryPollutant))
</script>

<style scoped>
.overview-card {
  background: var(--card-bg);
  border: 1px solid var(--border);
  border-radius: 12px;
  min-height: 132px;
  display: flex;
  align-items: stretch;
}

.placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-muted);
  font-size: 14px;
}

.overview-body {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 20px 24px;
  border-left: 6px solid transparent;
  border-radius: 12px;
}

.left .city {
  font-size: 22px;
  font-weight: 600;
}

.left .date {
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 13px;
}

.center {
  text-align: center;
  margin-left: auto;
}

.center .aqi {
  font-size: 52px;
  font-weight: 700;
  line-height: 1.1;
}

.center .aqi-label {
  font-size: 12px;
  color: var(--text-muted);
  letter-spacing: 2px;
}

.right {
  text-align: right;
  min-width: 140px;
}

.badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
}

.metric {
  margin-top: 10px;
  font-size: 13px;
  color: var(--text-muted);
}

.metric .v {
  margin-left: 6px;
  font-weight: 600;
  color: var(--text);
}
</style>
