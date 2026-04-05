import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import Chart from 'chart.js/auto';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
const chinaBounds = L.latLngBounds(L.latLng(3.86, 73.66), L.latLng(53.55, 135.05));
const mapRef = ref(null);
const paceChartRef = ref(null);
const hrChartRef = ref(null);
const isPreviewLoading = ref(false);
const isDownloadLoading = ref(false);
const hrRest = ref(60);
const hrMax = ref(180);
const lapCount = ref(1);
const weightKg = ref(65);
const startDateInput = ref('');
const pacePerKm = ref(6.0);
const panelCollapsed = ref(false);
const points = ref([]);
const preview = ref(null);
let map = null;
let polyline = null;
let currentLocationMarker = null;
let previewMarker = null;
let previewTimer = null;
let previewIndex = 0;
let paceChart = null;
let hrChart = null;
const distanceKm = computed(() => {
    if (points.value.length < 2) {
        return 0;
    }
    return (computeDistanceMeters(points.value) / 1000) * Math.max(1, lapCount.value);
});
const durationMin = computed(() => {
    const sec = preview.value?.totalDurationSec ?? 0;
    return sec > 0 ? (sec / 60).toFixed(1) : '0.0';
});
function dateToLocalInputValue(date) {
    const tzOffset = date.getTimezoneOffset();
    const local = new Date(date.getTime() - tzOffset * 60000);
    return local.toISOString().slice(0, 16);
}
function parsePaceSeconds(pace) {
    const value = Number.isFinite(pace) ? Math.max(0, pace) : 0;
    return Math.round(value * 60);
}
function haversineDistance(a, b) {
    const r = 6371000;
    const dLat = ((b.lat - a.lat) * Math.PI) / 180;
    const dLon = ((b.lng - a.lng) * Math.PI) / 180;
    const s1 = Math.sin(dLat / 2);
    const s2 = Math.sin(dLon / 2);
    const aa = s1 * s1 +
        Math.cos((a.lat * Math.PI) / 180) * Math.cos((b.lat * Math.PI) / 180) * s2 * s2;
    return r * 2 * Math.atan2(Math.sqrt(aa), Math.sqrt(1 - aa));
}
function computeDistanceMeters(route) {
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
    map.on('click', (event) => {
        const point = { lat: event.latlng.lat, lng: event.latlng.lng };
        points.value.push(point);
        if (polyline) {
            polyline.setLatLngs(points.value);
        }
        else {
            polyline = L.polyline(points.value, {
                color: '#f97316',
                weight: 4,
                lineCap: 'round'
            }).addTo(map);
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
    points.value = [];
    preview.value = null;
    if (polyline && map) {
        map.removeLayer(polyline);
        polyline = null;
    }
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
function renderPreviewCharts(data) {
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
        points: points.value,
        paceSecondsPerKm: parsePaceSeconds(pacePerKm.value),
        hrRest: Math.round(hrRest.value),
        hrMax: Math.round(hrMax.value),
        lapCount: Math.max(1, Math.round(lapCount.value)),
        weightKg: Number(weightKg.value)
    };
}
async function runPreview() {
    if (points.value.length < 2) {
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
        const data = (await response.json());
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
    }
    catch {
        ElMessage.error('预览请求失败，请稍后重试');
    }
    finally {
        isPreviewLoading.value = false;
    }
}
async function downloadFit() {
    if (points.value.length < 2) {
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
    }
    catch {
        ElMessage.error('导出失败，请稍后重试');
    }
    finally {
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
    navigator.geolocation.getCurrentPosition((position) => {
        const lat = position.coords.latitude;
        const lng = position.coords.longitude;
        const accuracy = Math.round(position.coords.accuracy);
        if (currentLocationMarker && map) {
            map.removeLayer(currentLocationMarker);
        }
        currentLocationMarker = L.marker([lat, lng]).addTo(map);
        currentLocationMarker
            .bindPopup(`当前位置: ${lat.toFixed(6)}, ${lng.toFixed(6)}<br>精度: ±${accuracy} 米`)
            .openPopup();
        map.setView([lat, lng], 16);
        ElMessage.success(`定位成功，精度 ±${accuracy} 米`);
    }, (error) => {
        if (error.code === error.PERMISSION_DENIED) {
            ElMessage.error('定位失败：未授予位置权限');
            return;
        }
        if (error.code === error.TIMEOUT) {
            ElMessage.error('定位超时，请重试');
            return;
        }
        ElMessage.error('定位失败，请检查定位服务');
    }, {
        enableHighAccuracy: true,
        timeout: 10000,
        maximumAge: 0
    });
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
debugger; /* PartiallyEnd: #3632/scriptSetup.vue */
const __VLS_ctx = {};
let __VLS_components;
let __VLS_directives;
/** @type {__VLS_StyleScopedClasses['panel-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['card-subtitle']} */ ;
/** @type {__VLS_StyleScopedClasses['card-subtitle']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['action-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['action-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['el-button']} */ ;
/** @type {__VLS_StyleScopedClasses['el-button']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-handle']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['top-metrics']} */ ;
// CSS variable injection 
// CSS variable injection end 
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "app-shell" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ref: "mapRef",
    ...{ class: "map-background" },
});
/** @type {typeof __VLS_ctx.mapRef} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "workspace-overlay" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: (['panel-wrap', { collapsed: __VLS_ctx.panelCollapsed }]) },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.aside, __VLS_intrinsicElements.aside)({
    ...{ class: "control-panel" },
});
const __VLS_0 = {}.ElCard;
/** @type {[typeof __VLS_components.ElCard, typeof __VLS_components.elCard, typeof __VLS_components.ElCard, typeof __VLS_components.elCard, ]} */ ;
// @ts-ignore
const __VLS_1 = __VLS_asFunctionalComponent(__VLS_0, new __VLS_0({
    ...{ class: "glass-card" },
}));
const __VLS_2 = __VLS_1({
    ...{ class: "glass-card" },
}, ...__VLS_functionalComponentArgsRest(__VLS_1));
__VLS_3.slots.default;
{
    const { header: __VLS_thisSlot } = __VLS_3.slots;
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "card-header-text" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "card-title" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
        ...{ class: "card-subtitle" },
    });
    __VLS_asFunctionalElement(__VLS_intrinsicElements.a, __VLS_intrinsicElements.a)({
        href: "https://space.bilibili.com/439315192",
        target: "_blank",
        rel: "noopener noreferrer",
    });
}
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "top-metrics" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "metric-pill" },
});
(__VLS_ctx.durationMin);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "metric-pill" },
});
(__VLS_ctx.points.length);
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "metric-pill" },
});
(__VLS_ctx.distanceKm.toFixed(2));
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-list" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-line" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "field-label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ class: "field-input" },
    type: "number",
    min: "30",
    max: "120",
    step: "1",
});
(__VLS_ctx.hrRest);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-line" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "field-label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ class: "field-input" },
    type: "number",
    min: "100",
    max: "220",
    step: "1",
});
(__VLS_ctx.hrMax);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-line" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "field-label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ class: "field-input" },
    type: "number",
    min: "30",
    max: "150",
    step: "1",
});
(__VLS_ctx.weightKg);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-line" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "field-label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ class: "field-input" },
    type: "number",
    min: "1",
    step: "1",
});
(__VLS_ctx.lapCount);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-line" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "field-label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ class: "field-input" },
    type: "datetime-local",
});
(__VLS_ctx.startDateInput);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "field-line" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.span, __VLS_intrinsicElements.span)({
    ...{ class: "field-label" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.input)({
    ...{ class: "field-input" },
    type: "number",
    min: "1",
    max: "20",
    step: "0.1",
});
(__VLS_ctx.pacePerKm);
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "action-grid" },
});
const __VLS_4 = {}.ElButton;
/** @type {[typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ]} */ ;
// @ts-ignore
const __VLS_5 = __VLS_asFunctionalComponent(__VLS_4, new __VLS_4({
    ...{ 'onClick': {} },
    type: "info",
    plain: true,
}));
const __VLS_6 = __VLS_5({
    ...{ 'onClick': {} },
    type: "info",
    plain: true,
}, ...__VLS_functionalComponentArgsRest(__VLS_5));
let __VLS_8;
let __VLS_9;
let __VLS_10;
const __VLS_11 = {
    onClick: (__VLS_ctx.locateCurrentPosition)
};
__VLS_7.slots.default;
var __VLS_7;
const __VLS_12 = {}.ElButton;
/** @type {[typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ]} */ ;
// @ts-ignore
const __VLS_13 = __VLS_asFunctionalComponent(__VLS_12, new __VLS_12({
    ...{ 'onClick': {} },
}));
const __VLS_14 = __VLS_13({
    ...{ 'onClick': {} },
}, ...__VLS_functionalComponentArgsRest(__VLS_13));
let __VLS_16;
let __VLS_17;
let __VLS_18;
const __VLS_19 = {
    onClick: (__VLS_ctx.clearRoute)
};
__VLS_15.slots.default;
var __VLS_15;
const __VLS_20 = {}.ElButton;
/** @type {[typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ]} */ ;
// @ts-ignore
const __VLS_21 = __VLS_asFunctionalComponent(__VLS_20, new __VLS_20({
    ...{ 'onClick': {} },
    type: "primary",
    loading: (__VLS_ctx.isPreviewLoading),
}));
const __VLS_22 = __VLS_21({
    ...{ 'onClick': {} },
    type: "primary",
    loading: (__VLS_ctx.isPreviewLoading),
}, ...__VLS_functionalComponentArgsRest(__VLS_21));
let __VLS_24;
let __VLS_25;
let __VLS_26;
const __VLS_27 = {
    onClick: (__VLS_ctx.runPreview)
};
__VLS_23.slots.default;
var __VLS_23;
const __VLS_28 = {}.ElButton;
/** @type {[typeof __VLS_components.ElButton, typeof __VLS_components.elButton, typeof __VLS_components.ElButton, typeof __VLS_components.elButton, ]} */ ;
// @ts-ignore
const __VLS_29 = __VLS_asFunctionalComponent(__VLS_28, new __VLS_28({
    ...{ 'onClick': {} },
    type: "success",
    loading: (__VLS_ctx.isDownloadLoading),
}));
const __VLS_30 = __VLS_29({
    ...{ 'onClick': {} },
    type: "success",
    loading: (__VLS_ctx.isDownloadLoading),
}, ...__VLS_functionalComponentArgsRest(__VLS_29));
let __VLS_32;
let __VLS_33;
let __VLS_34;
const __VLS_35 = {
    onClick: (__VLS_ctx.downloadFit)
};
__VLS_31.slots.default;
var __VLS_31;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-list" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-item" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-title" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-wrap" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.canvas, __VLS_intrinsicElements.canvas)({
    ref: "paceChartRef",
});
/** @type {typeof __VLS_ctx.paceChartRef} */ ;
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-item" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-title" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.div, __VLS_intrinsicElements.div)({
    ...{ class: "mini-chart-wrap" },
});
__VLS_asFunctionalElement(__VLS_intrinsicElements.canvas, __VLS_intrinsicElements.canvas)({
    ref: "hrChartRef",
});
/** @type {typeof __VLS_ctx.hrChartRef} */ ;
var __VLS_3;
__VLS_asFunctionalElement(__VLS_intrinsicElements.button, __VLS_intrinsicElements.button)({
    ...{ onClick: (__VLS_ctx.togglePanel) },
    ...{ class: "panel-handle" },
    type: "button",
});
(__VLS_ctx.panelCollapsed ? '▶' : '◀');
/** @type {__VLS_StyleScopedClasses['app-shell']} */ ;
/** @type {__VLS_StyleScopedClasses['map-background']} */ ;
/** @type {__VLS_StyleScopedClasses['workspace-overlay']} */ ;
/** @type {__VLS_StyleScopedClasses['control-panel']} */ ;
/** @type {__VLS_StyleScopedClasses['glass-card']} */ ;
/** @type {__VLS_StyleScopedClasses['card-header-text']} */ ;
/** @type {__VLS_StyleScopedClasses['card-title']} */ ;
/** @type {__VLS_StyleScopedClasses['card-subtitle']} */ ;
/** @type {__VLS_StyleScopedClasses['top-metrics']} */ ;
/** @type {__VLS_StyleScopedClasses['metric-pill']} */ ;
/** @type {__VLS_StyleScopedClasses['metric-pill']} */ ;
/** @type {__VLS_StyleScopedClasses['metric-pill']} */ ;
/** @type {__VLS_StyleScopedClasses['field-list']} */ ;
/** @type {__VLS_StyleScopedClasses['field-line']} */ ;
/** @type {__VLS_StyleScopedClasses['field-label']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['field-line']} */ ;
/** @type {__VLS_StyleScopedClasses['field-label']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['field-line']} */ ;
/** @type {__VLS_StyleScopedClasses['field-label']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['field-line']} */ ;
/** @type {__VLS_StyleScopedClasses['field-label']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['field-line']} */ ;
/** @type {__VLS_StyleScopedClasses['field-label']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['field-line']} */ ;
/** @type {__VLS_StyleScopedClasses['field-label']} */ ;
/** @type {__VLS_StyleScopedClasses['field-input']} */ ;
/** @type {__VLS_StyleScopedClasses['action-grid']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-list']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-item']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-title']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-item']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-title']} */ ;
/** @type {__VLS_StyleScopedClasses['mini-chart-wrap']} */ ;
/** @type {__VLS_StyleScopedClasses['panel-handle']} */ ;
var __VLS_dollars;
const __VLS_self = (await import('vue')).defineComponent({
    setup() {
        return {
            mapRef: mapRef,
            paceChartRef: paceChartRef,
            hrChartRef: hrChartRef,
            isPreviewLoading: isPreviewLoading,
            isDownloadLoading: isDownloadLoading,
            hrRest: hrRest,
            hrMax: hrMax,
            lapCount: lapCount,
            weightKg: weightKg,
            startDateInput: startDateInput,
            pacePerKm: pacePerKm,
            panelCollapsed: panelCollapsed,
            points: points,
            distanceKm: distanceKm,
            durationMin: durationMin,
            clearRoute: clearRoute,
            runPreview: runPreview,
            downloadFit: downloadFit,
            locateCurrentPosition: locateCurrentPosition,
            togglePanel: togglePanel,
        };
    },
});
export default (await import('vue')).defineComponent({
    setup() {
        return {};
    },
});
; /* PartiallyEnd: #4569/main.vue */
