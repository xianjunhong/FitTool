# Fit Tool 

Fit Tool 是一个用于生成跑步 FIT 文件的工具，支持在地图上绘制轨迹、预览运动数据，并导出 `run.fit` 后导入到运动平台或设备中使用。

## 项目能做什么

- 在地图上点击绘制跑步轨迹点。
- 根据配速、心率、体重、圈数等参数生成采样数据。
- 预览速度/心率曲线和预计时长、距离、卡路里。
- 导出标准 FIT 文件（`包含平均步频、平均步幅、爬升高度`）。

## 技术栈与架构

### 前端

- Vue 3 + TypeScript + Vite
- Element Plus（UI）
- Leaflet（地图与轨迹绘制）
- Chart.js（预览图表）

代码目录：
- `frontend/src/App.vue`：地图交互、预览与导出主逻辑
- `frontend/src/style.css`：全局样式

### 后端

- Spring Boot 3 (Java 17)
- Garmin FIT SDK (`com.garmin:fit`)

代码目录：
- `backend/src/main/java/com/fittool/controller/FitController.java`：API 入口
- `backend/src/main/java/com/fittool/service/FitService.java`：预览计算与 FIT 写出
- `backend/src/main/java/com/fittool/service/FitUtils.java`：轨迹与指标计算工具

### 部署层

- Docker + Docker Compose
- Nginx（静态资源 + `/api` 反向代理）
- Certbot（HTTPS 证书签发与续期）
- GitHub Actions 自动部署（见 `.github/workflows/deploy.yml`）

## 目录结构

```text
backend/                 Spring Boot 后端
frontend/                Vue 前端
nginx/                   Nginx 配置
docker-compose.yml       线上服务编排
.github/workflows/       GitHub CI/CD
FitCSVTool.jar           FIT 调试工具
```

## API 说明

后端 API 前缀为 `/api`：

- `POST /api/preview`
  - 入参：轨迹点、配速、心率、体重、圈数、开始时间
  - 出参：采样列表、总时长、总距离、卡路里等预览数据

- `POST /api/generate-fit`
  - 入参：同上
  - 出参：`application/vnd.ant.fit` 二进制文件

## 本地开发

### 1) 启动后端

```bash
cd backend
mvn spring-boot:run
```

后端默认监听：`http://localhost:8080`

### 2) 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认监听：`http://localhost:5173`，并通过 Vite 代理 `/api` 到 `8080`。

## 导出与调试 FIT 文件

项目根目录包含 `FitCSVTool.jar`，用于调试和检查生成的 `run.fit` 文件。

典型用途：
- 将 `run.fit` 内容解析为可读格式（如 CSV）进行校验。
- 辅助检查时间戳、速度、心率、步频、海拔等字段是否合理。



## 线上部署（简述）

- 推送 `main` 分支后触发 GitHub Actions 部署。
- Workflow 通过 SSH 登录服务器，更新代码并构建前后端。
- 首次部署会自动申请 `www.phenoseed.com` 的 HTTPS 证书。
- 最终由 Docker Compose 拉起 `backend + nginx + certbot`。

## 注意事项

- 首次构建时 Maven 依赖下载较多，日志较长属正常现象。
- 若服务器网络波动，部署脚本内已包含镜像拉取与关键步骤重试。
- 若定位图标线上不显示，已在前端中通过显式导入 Leaflet marker 资源修复。

## 如何导入Keep
## 生成run.fit文件以后，打开keep
![alt text](.assert/p1.png)