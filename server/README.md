# AutoSlide 统计后台（PVE 部署）

零依赖的 Node.js 统计服务：接收 App 上报的安装/更新事件和设备信息，提供统计看板。

## 接口

- `POST /api/report`：App 上报，body 为 JSON：
  ```json
  {
    "event": "install",
    "deviceId": "设备唯一ID",
    "device": {
      "model": "厂商 型号",
      "brand": "品牌",
      "android": "13",
      "cpu": "arm64-v8a",
      "appVersion": "2.7.1",
      "appVersionCode": 30
    }
  }
  ```
- `GET /api/stats`：返回统计 JSON。
- `GET /`：统计看板页面。

数据保存在 `data/stats.json`，可随时备份。

## PVE 部署步骤

1. **PVE 网页 → 创建 CT（LXC 容器）**
   - 模板选 **Debian 12**；
   - 非特权容器即可，资源建议 512MB 内存 / 2 核 / 4GB 磁盘；
   - 网络：选 **bridged（vmbr0）**，让容器直接拿局域网 IP（手机和服务器同一网络才能访问）。

2. **进入容器，安装 Node.js**
   ```bash
   apt update && apt install -y nodejs
   node -v   # Debian 12 自带 Node 18，足够
   ```

3. **上传服务代码**
   把本目录（`server.js`、`package.json`）放到容器 `/opt/autoslide-stats/`：
   ```bash
   mkdir -p /opt/autoslide-stats
   # 用 pct push 或 scp 把 server.js 传进去
   ```

4. **手动启动验证**
   ```bash
   cd /opt/autoslide-stats
   node server.js
   ```
   浏览器访问 `http://容器IP:8080`，能看到统计看板即成功。

5. **设为开机自启（systemd）**
   ```bash
   cp /opt/autoslide-stats/autoslide-stats.service /etc/systemd/system/
   systemctl daemon-reload
   systemctl enable --now autoslide-stats
   ```
   > unit 名就是 `autoslide-stats`（中间没有连字符），改完代码重启用：
   > `systemctl restart autoslide-stats`

6. **防火墙/网络**
   - 容器内：`apt install -y ufw && ufw allow 8080`（或 iptables）。
   - 只在局域网用，手机直接访问 `http://容器IP:8080`。
   - 如果需要外网访问：建议用 Nginx/Caddy 反代并加 HTTPS，不要把 8080 裸暴露到公网。

## App 端配置

服务地址不在 Kotlin 代码里，改根目录 `gradle.properties` 的这一行即可：

```properties
autoslide.serverBaseUrl=http://你的PVE容器IP:8080
```

构建时经 `buildConfigField` 注入 `BuildConfig.SERVER_BASE_URL`，由
`app/src/main/java/com/ziek/autoslide/Constants.kt` 里的 `SERVER_BASE_URL` 统一对外提供，
统计上报（`/api/report`）、录制脚本备份（`/api/upload`）、聊天室（`/api/chat/*`）三者共用。

也可以不改文件，构建时临时覆盖：

```
./gradlew assembleRelease -Pautoslide.serverBaseUrl=http://192.168.1.10:8080
```

重新编译安装即可。App 会在首次安装和版本升级时上报一次设备信息。




项目里（源码，可随时修改/重新部署）
C:\Users\Administrator\Desktop\AutoSlide-master\server\
server.js — 后端服务主程序（零依赖）
package.json — 项目说明/启动脚本
autoslide-stats.service — systemd 服务模板
test/smoke.test.js — 冒烟测试（npm test，不需要部署到服务器）
README.md — 部署说明

PVE 宿主机上（正在运行的位置，直接跑在宿主机，不在 LXC 容器里）
服务程序：/opt/autoslide-stats/server.js、package.json
data/stats.json — 统计数据就存在这里（安装数、设备列表等）

隧道配置：/etc/cloudflared/config.yml
隧道凭据：/root/.cloudflared/
系统服务：cloudflared（开机自启）和 autoslide-stats（统计服务，开机自启）