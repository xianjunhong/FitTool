<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import Chart from 'chart.js/auto';
import L from 'leaflet';
import type { PreviewResponse, RoutePoint } from './types';
import 'leaflet/dist/leaflet.css';
import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png';
import markerIcon from 'leaflet/dist/images/marker-icon.png';
import markerShadow from 'leaflet/dist/images/marker-shadow.png';

const chinaBounds = L.latLngBounds(L.latLng(3.86, 73.66), L.latLng(53.55, 135.05));

const defaultIconProto = L.Icon.Default.prototype as L.Icon.Default & { _getIconUrl?: unknown };
delete defaultIconProto._getIconUrl;
L.Icon.Default.mergeOptions({
  iconRetinaUrl: markerIcon2x,
  iconUrl: markerIcon,
  shadowUrl: markerShadow
});

const mapRef = ref<HTMLElement | null>(null);
const paceChartRef = ref<HTMLCanvasElement | null>(null);
const hrChartRef = ref<HTMLCanvasElement | null>(null);

const isPreviewLoading = ref(false);
const isDownloadLoading = ref(false);

const hrRest = ref(60);
const hrMax = ref(180);
const lapCount = ref(1);
const weightKg = ref(65);
const startDateInput = ref('');
const pacePerKm = ref(6.0);
const panelCollapsed = ref(false);

const coordinateBOS = ref<RoutePoint[]>([]);
const preview = ref<PreviewResponse | null>(null);

let map: L.Map | null = null;
let polyline: L.Polyline | null = null;
let clickMarkers: L.CircleMarker[] = [];
let clickLabels: L.Marker[] = [];
let currentLocationMarker: L.Marker | null = null;
let previewMarker: L.CircleMarker | null = null;
let previewTimer: number | null = null;
let previewIndex = 0;

let paceChart: Chart | null = null;
let hrChart: Chart | null = null;

const distanceKm = computed(() => {
  if (coordinateBOS.value.length < 2) {
    return 0;
  }
  return (computeDistanceMeters(coordinateBOS.value) / 1000) * Math.max(1, lapCount.value);
});

const durationMin = computed(() => {
  const sec = preview.value?.totalDurationSec ?? 0;
  return sec > 0 ? (sec / 60).toFixed(1) : '0.0';
});

function dateToLocalInputValue(date: Date): string {
  const tzOffset = date.getTimezoneOffset();
  const local = new Date(date.getTime() - tzOffset * 60000);
  return local.toISOString().slice(0, 16);
}

function parsePaceSeconds(pace: number): number {
  const value = Number.isFinite(pace) ? Math.max(0, pace) : 0;
  return Math.round(value * 60);
}

function haversineDistance(a: RoutePoint, b: RoutePoint): number {
  const r = 6371000;
  const dLat = ((b.lat - a.lat) * Math.PI) / 180;
  const dLon = ((b.lng - a.lng) * Math.PI) / 180;
  const s1 = Math.sin(dLat / 2);
  const s2 = Math.sin(dLon / 2);
  const aa =
    s1 * s1 +
    Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * s2 * s2;
  return r * 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa));
}

function computeDistanceMeters(route: RoutePoint[]): number {
  if (route.length < 2) {
    return 0;
  }

  let total = 0;
  for (let i = 1; i < route.length; i++) {
    total += haversineDistance(route[i - 1], route[i]);
  }
  return total;
}

function initMap() {
  if (!mapRef.value) {
    return;
  }

  map = L.map(mapRef.value, {
    maxBounds: chinaBounds,
    maxBoundsViscosity: 1,
    attributionControl: true
  }).setView([31.2304, 121.4737], 13);

  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; OpenStreetMap contributors'
  }).addTo(map);

  map.on('click', (event: L.LeafletMouseEvent) => {
    const point = { lat: event.latlng.lat, lng: event.latlng.lng };
    coordinateBOS.value.push(point);
    const pointIndex = coordinateBOS.value.length;

    const clickMarker = L.circleMarker([point.lat, point.lng], {
      radius: 4,
      color: '#f97316',
      fillColor: '#fb923c',
      fillOpacity: 0.95,
      weight: 1
    }).addTo(map!);
    clickMarkers.push(clickMarker);

    const clickLabel = L.marker([point.lat, point.lng], {
      interactive: false,
      keyboard: false,
      icon: L.divIcon({
        className: 'click-point-label',
        html: String(pointIndex),
        iconSize: [20, 20],
        iconAnchor: [10, -8]
      })
    }).addTo(map!);
    clickLabels.push(clickLabel);

    if (polyline) {
      polyline.setLatLngs(coordinateBOS.value as L.LatLngExpression[]);
    } else {
      polyline = L.polyline(coordinateBOS.value as L.LatLngExpression[], {
        color: '#f97316',
        weight: 4,
        lineCap: 'round'
      }).addTo(map!);
    }
  });
}

function clearCharts() {
  if (paceChart) {
    paceChart.destroy();
    paceChart = null;
  }

  if (hrChart) {
    hrChart.destroy();
    hrChart = null;
  }
}

function stopPreviewPlayback() {
  if (previewTimer !== null) {
    window.clearInterval(previewTimer);
    previewTimer = null;
  }
}

function clearPreviewMarker() {
  if (previewMarker && map) {
    map.removeLayer(previewMarker);
    previewMarker = null;
  }
}

function clearRoute() {
  coordinateBOS.value = [];
  preview.value = null;

  if (polyline && map) {
    map.removeLayer(polyline);
    polyline = null;
  }

  if (map) {
    clickMarkers.forEach((marker) => map!.removeLayer(marker));
    clickLabels.forEach((label) => map!.removeLayer(label));
  }
  clickMarkers = [];
  clickLabels = [];

  stopPreviewPlayback();
  clearPreviewMarker();
  clearCharts();

  ElMessage.success('轨迹与预览已清除');
}

function startPreviewPlayback() {
  const samples = preview.value?.samples || [];
  if (!samples.length || !map) {
    return;
  }

  stopPreviewPlayback();
  previewIndex = 0;

  previewTimer = window.setInterval(() => {
    if (previewIndex >= samples.length) {
      stopPreviewPlayback();
      return;
    }

    const sample = samples[previewIndex];
    if (previewMarker && sample.lat != null && sample.lng != null) {
      previewMarker.setLatLng([sample.lat, sample.lng]);
    }

    previewIndex += 1;
  }, 100);
}

function renderPreviewCharts(data: PreviewResponse) {
  if (!paceChartRef.value || !hrChartRef.value) {
    return;
  }

  const samples = data.samples || [];
  if (!samples.length) {
    clearCharts();
    return;
  }

  const labels = samples.map((s) => (s.timeSec / 60).toFixed(1));
  const paceData = samples.map((s) => {
    const speed = s.speed > 0 ? s.speed : 0.01;
    return 1000 / speed / 60;
  });
  const hrData = samples.map((s) => s.heartRate);

  clearCharts();

  paceChart = new Chart(paceChartRef.value, {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: '配速 (min/km)',
          data: paceData,
          borderColor: '#2563eb',
          backgroundColor: 'rgba(37, 99, 235, 0.18)',
          borderWidth: 2,
          tension: 0.24,
          pointRadius: 0,
          fill: true
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false }
      },
      scales: {
        x: {
          title: { display: true, text: '时间 (分钟)' }
        },
        y: {
          title: { display: true, text: 'min/km' }
        }
      }
    }
  });

  hrChart = new Chart(hrChartRef.value, {
    type: 'line',
    data: {
      labels,
      datasets: [
        {
          label: '心率 (bpm)',
          data: hrData,
          borderColor: '#dc2626',
          backgroundColor: 'rgba(220, 38, 38, 0.16)',
          borderWidth: 2,
          tension: 0.24,
          pointRadius: 0,
          fill: true
        }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false }
      },
      scales: {
        x: {
          title: { display: true, text: '时间 (分钟)' }
        },
        y: {
          title: { display: true, text: 'bpm' }
        }
      }
    }
  });
}

function buildPayload() {
  const parsedDate = new Date(startDateInput.value);
  return {
    startDate: parsedDate.toISOString(),
    coordinateBOS: coordinateBOS.value,
    paceSecondsPerKm: parsePaceSeconds(pacePerKm.value),
    hrRest: Math.round(hrRest.value),
    hrMax: Math.round(hrMax.value),
    lapCount: Math.max(1, Math.round(lapCount.value)),
    weightKg: Number(weightKg.value)
  };
}

async function runPreview() {
  if (coordinateBOS.value.length < 2) {
    ElMessage.warning('请先在地图上至少添加两个轨迹点');
    return;
  }

  if (!startDateInput.value) {
    ElMessage.warning('请先设置开始时间');
    return;
  }

  if (Number.isNaN(new Date(startDateInput.value).getTime())) {
    ElMessage.error('开始时间格式无效');
    return;
  }

  const paceSecVal = parsePaceSeconds(pacePerKm.value);
  if (paceSecVal <= 0) {
    ElMessage.error('预览配速无效，请检查配速设置');
    return;
  }

  isPreviewLoading.value = true;

  try {
    const response = await fetch('/api/preview', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(buildPayload())
    });

    if (!response.ok) {
      const errorText = await response.text();
      ElMessage.error(`预览失败: ${errorText}`);
      return;
    }

    const data = (await response.json()) as PreviewResponse;
    preview.value = data;
    renderPreviewCharts(data);

    stopPreviewPlayback();
    clearPreviewMarker();
    previewIndex = 0;

    if (data.samples?.length && map) {
      const first = data.samples[0];
      previewMarker = L.circleMarker([first.lat, first.lng], {
        radius: 6,
        color: '#2563eb',
        fillColor: '#60a5fa',
        fillOpacity: 0.85
      }).addTo(map);
      startPreviewPlayback();
    }

    ElMessage.success('预览完成');
  } catch {
    ElMessage.error('预览请求失败，请稍后重试');
  } finally {
    isPreviewLoading.value = false;
  }
}

async function downloadFit() {
  if (coordinateBOS.value.length < 2) {
    ElMessage.warning('请先绘制轨迹后再导出 FIT');
    return;
  }

  if (!startDateInput.value) {
    ElMessage.error('请先设置开始时间');
    return;
  }

  if (Number.isNaN(new Date(startDateInput.value).getTime())) {
    ElMessage.error('开始时间格式无效');
    return;
  }

  const paceSecVal = parsePaceSeconds(pacePerKm.value);
  if (paceSecVal <= 0) {
    ElMessage.error('配速无效，请检查');
    return;
  }

  isDownloadLoading.value = true;

  try {
    const response = await fetch('/api/generate-fit', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(buildPayload())
    });

    if (!response.ok) {
      const errorText = await response.text();
      ElMessage.error(`导出失败: ${errorText}`);
      return;
    }

    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'run.fit';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);

    ElMessage.success('已生成并下载 FIT 文件');
  } catch {
    ElMessage.error('导出失败，请稍后重试');
  } finally {
    isDownloadLoading.value = false;
  }
}

function locateCurrentPosition() {
  if (!map) {
    return;
  }

  if (!navigator.geolocation) {
    ElMessage.error('当前浏览器不支持定位');
    return;
  }

  navigator.geolocation.getCurrentPosition(
    (position) => {
      const lat = position.coords.latitude;
      const lng = position.coords.longitude;
      const accuracy = Math.round(position.coords.accuracy);

      if (currentLocationMarker && map) {
        map.removeLayer(currentLocationMarker);
      }

      currentLocationMarker = L.marker([lat, lng]).addTo(map!);
      currentLocationMarker
        .bindPopup(`当前位置: ${lat.toFixed(6)}, ${lng.toFixed(6)}<br>精度: ±${accuracy} 米`)
        .openPopup();

      map!.setView([lat, lng], 16);
      ElMessage.success(`定位成功，精度 ±${accuracy} 米`);
    },
    (error) => {
      if (error.code === error.PERMISSION_DENIED) {
        ElMessage.error('定位失败：未授予位置权限');
        return;
      }
      if (error.code === error.TIMEOUT) {
        ElMessage.error('定位超时，请重试');
        return;
      }
      ElMessage.error('定位失败，请检查定位服务');
    },
    {
      enableHighAccuracy: true,
      timeout: 10000,
      maximumAge: 0
    }
  );
}

function togglePanel() {
  panelCollapsed.value = !panelCollapsed.value;
}

watch(weightKg, (val) => {
  localStorage.setItem('fit_weight', String(val));
});

onMounted(async () => {
  startDateInput.value = dateToLocalInputValue(new Date());

  const savedWeight = localStorage.getItem('fit_weight');
  if (savedWeight) {
    const val = Number(savedWeight);
    if (Number.isFinite(val)) {
      weightKg.value = val;
    }
  }

  await nextTick();
  initMap();
});

onBeforeUnmount(() => {
  stopPreviewPlayback();
  clearCharts();
  if (map) {
    map.remove();
    map = null;
  }
});
</script>

<template>
  <div class="app-shell">
    <div ref="mapRef" class="map-background"></div>

    <div class="workspace-overlay">
      <div :class="['panel-wrap', { collapsed: panelCollapsed }]">
        <aside class="control-panel">
        <el-card class="glass-card">
          <a
            class="card-corner-github"
            href="https://github.com/xianjunhong/FitTool"
            target="_blank"
            rel="noopener noreferrer"
            aria-label="Open FitTool GitHub repository"
            title="GitHub"
          >
            <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false">
              <path
                fill="currentColor"
                d="M8 0a8 8 0 0 0-2.53 15.59c.4.07.55-.17.55-.38l-.01-1.33c-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.5-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.58.82-2.14-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82A7.56 7.56 0 0 1 8 4.69c.68 0 1.37.09 2.01.27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.14 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48l-.01 2.2c0 .21.14.46.55.38A8 8 0 0 0 8 0Z"
              />
            </svg>
          </a>

          <template #header>
            <div class="card-header-text">
              <div class="card-title">Fit Tool</div>
              <div class="card-subtitle">
                原开发者为 Ruomu Yunxi。B 站账号
                <a href="https://space.bilibili.com/439315192" target="_blank" rel="noopener noreferrer">
                  @黑心商家瑶瑶
                </a>
              </div>
            </div>
          </template>

          <div class="top-metrics">
            <span class="metric-pill">预估时长 {{ durationMin }} 分钟</span>
            <span class="metric-pill">轨迹点 {{ coordinateBOS.length }}</span>
            <span class="metric-pill">地图估算 {{ distanceKm.toFixed(2) }} km</span>
          </div>

          <div class="field-list">
            <div class="field-line">
              <span class="field-label">静息心率</span>
              <input v-model.number="hrRest" class="field-input" type="number" min="30" max="120" step="1" />
            </div>

            <div class="field-line">
              <span class="field-label">最大心率</span>
              <input v-model.number="hrMax" class="field-input" type="number" min="100" max="220" step="1" />
            </div>

            <div class="field-line">
              <span class="field-label">体重 (kg)</span>
              <input v-model.number="weightKg" class="field-input" type="number" min="30" max="150" step="1" />
            </div>

            <div class="field-line">
              <span class="field-label">圈数</span>
              <input v-model.number="lapCount" class="field-input" type="number" min="1" step="1" />
            </div>

            <div class="field-line">
              <span class="field-label">开始时间</span>
              <input v-model="startDateInput" class="field-input" type="datetime-local" />
            </div>

            <div class="field-line">
              <span class="field-label">配速 (分/千米)</span>
              <input v-model.number="pacePerKm" class="field-input" type="number" min="1" max="20" step="0.1" />
            </div>
          </div>

          <div class="action-grid">
            <el-button type="warning"  @click="locateCurrentPosition">定位当前位置</el-button>
            <el-button type="info" @click="clearRoute">清除轨迹</el-button>
            <el-button type="primary" :loading="isPreviewLoading" @click="runPreview">预览曲线</el-button>
            <el-button type="success" :loading="isDownloadLoading" @click="downloadFit">生成 FIT 文件</el-button>
          </div>

          <div class="mini-chart-list">
            <div class="mini-chart-item">
              <div class="mini-chart-title">配速预览</div>
              <div class="mini-chart-wrap">
                <canvas ref="paceChartRef"></canvas>
              </div>
            </div>
            <div class="mini-chart-item">
              <div class="mini-chart-title">心率预览</div>
              <div class="mini-chart-wrap">
                <canvas ref="hrChartRef"></canvas>
              </div>
            </div>
          </div>
        </el-card>
        </aside>

        <button class="panel-handle" type="button" @click="togglePanel">
          {{ panelCollapsed ? '▶' : '◀' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  position: relative;
  height: 100vh;
  overflow: hidden;
}

.map-background {
  position: absolute;
  inset: 0;
  z-index: 0;
}

.workspace-overlay {
  position: relative;
  z-index: 2;
  height: 100vh;
  padding: 0;
  pointer-events: none;
}

.panel-wrap {
  position: relative;
  width: min(340px, 92vw);
  height: 100vh;
  pointer-events: auto;
  transition: transform 280ms cubic-bezier(0.22, 1, 0.36, 1);
}

.panel-wrap.collapsed {
  transform: translateX(-100%);
}

.control-panel {
  width: 100%;
  height: 100vh;
}

.glass-card {
  position: relative;
  height: 100%;
  border-radius: 0 14px 14px 0;
  border: 1px solid rgba(148, 163, 184, 0.28);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(6px);
  animation: fade-up 420ms ease;
  overflow: hidden;
}

.card-corner-github {
  position: absolute;
  top: 0;
  right: 0;
  width: 64px;
  height: 64px;
  z-index: 5;
  display: block;
  color: #f8fafc;
  background: transparent;
  border-top-right-radius: 14px;
  overflow: hidden;
  transition: filter 160ms ease;
}

.card-corner-github::before {
  content: '';
  position: absolute;
  inset: 0;
  background: #0f172a;
  clip-path: polygon(100% 0, 100% 100%, 0 0);
}

.card-corner-github:hover {
  filter: brightness(1.1);
}

.card-corner-github svg {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 28px;
  height: 23px;
  z-index: 1;
}

.card-title {
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.card-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #475569;
}

.card-subtitle a {
  color: #2563eb;
  text-decoration: none;
}

.card-subtitle a:hover {
  text-decoration: underline;
}

.top-metrics {
  margin-bottom: 8px;
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 6px;
}

.metric-pill {
  padding: 4px 9px;
  border-radius: 999px;
  background: #0f172a;
  color: #f8fafc;
  font-size: 12px;
  text-align: center;
  white-space: nowrap;
}

.field-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.field-line {
  display: grid;
  grid-template-columns: 108px 1fr;
  align-items: center;
  gap: 6px;
}

.field-label {
  font-size: 14px;
  font-weight: 600;
  color: #334155;
  white-space: nowrap;
}

.field-input {
  width: 100%;
  height: 30px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  padding: 0 8px;
  color: #0f172a;
  background: #ffffff;
}

.field-input:focus {
  border-color: #2563eb;
  outline: none;
}

.action-grid {
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.action-grid :deep(.el-button) {
  width: 100%;
}

.action-grid :deep(.el-button + .el-button) {
  margin-left: 0;
}

.panel-handle {
  position: absolute;
  right: -28px;
  top: 50%;
  transform: translateY(-50%);
  width: 28px;
  height: 72px;
  border: 1px solid rgba(148, 163, 184, 0.7);
  border-left: 0;
  border-radius: 0 12px 12px 0;
  background: rgba(255, 255, 255, 0.95);
  color: #0f172a;
  cursor: pointer;
  line-height: 1;
  font-size: 16px;
  transition: background-color 180ms ease, color 180ms ease;
}

.panel-handle:hover {
  background: #ffffff;
  color: #2563eb;
}

.mini-chart-list {
  margin-top: 10px;
  display: grid;
  grid-template-columns: 1fr;
  gap: 10px;
}

.mini-chart-item {
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 8px;
  background: rgba(248, 250, 252, 0.9);
}

.mini-chart-title {
  font-size: 12px;
  color: #334155;
  margin-bottom: 6px;
}

.mini-chart-wrap {
  height: 95px;
}

@keyframes fade-up {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 760px) {
  .panel-wrap {
    width: min(320px, 92vw);
  }

  .top-metrics {
    grid-template-columns: 1fr;
  }
}
</style>
