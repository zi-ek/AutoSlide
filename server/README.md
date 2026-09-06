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
- `GET /api/license?deviceId=..`：返回该设备的试用/奖励时长、到期时间与邀请码，首次访问即建档。
- `POST /api/license/bind`：新设备填写好友邀请码，body 为 `{"deviceId":"..","code":"ABC123"}`。
- `GET /invite?code=..`：分享落地页，好友从这里下载并抄走邀请码。
- `GET /invites`：邀请与时长一览（排查刷量用）。
- `GET /`：统计看板页面。

数据保存在 `data/stats.json`，邀请关系与试用起点在 `data/license.json`，都可随时备份。

> ⚠️ `data/license.json` 里存着每台设备的试用起点和邀请关系，**删掉它等于给所有人重置 30 天试用**，备份时别漏。

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

   > ⚠️ `server.js` 已不再是单文件，实现拆在 `src/` 下。**只传 `server.js` 会启动失败**，
   > 必须连 `src/` 整个目录一起传。`test/` 和 `data/` 不用传。

   需要传的：`server.js`、`src/`（含 `src/views/`）、`package.json`、`autoslide-stats.service`
   ```bash
   mkdir -p /opt/autoslide-stats
   # 用 scp 递归传整个目录，或 rsync 排除掉不需要的
   rsync -av --exclude data --exclude test ./ root@目标机:/opt/autoslide-stats/
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

   ⚠️ 这个 `cp` 是**覆盖**。`/opt` 下那份是随代码传上来的模板，`/etc` 下那份才是
   systemd 真正加载的（`systemctl show autoslide-stats -p FragmentPath` 可确认）。
   两份不是链接，拷完就各走各的——所以**任何只改 `/etc` 那份的改动，下次重新部署都会被冲掉**。
   要长期生效的东西，改仓库里的模板；口令一律走下面的 drop-in。

6. **配置口令（drop-in，不写进 unit 文件）**
   ```bash
   mkdir -p /etc/systemd/system/autoslide-stats.service.d
   cat > /etc/systemd/system/autoslide-stats.service.d/token.conf <<'EOF'
   [Service]
   Environment=AUTOSLIDE_ADMIN_TOKEN=换成你的管理口令
   Environment=AUTOSLIDE_REPORT_TOKEN=换成你的上报口令
   EOF
   systemctl daemon-reload && systemctl restart autoslide-stats
   ```

   | 变量 | 管什么 | 谁持有 |
   |---|---|---|
   | `AUTOSLIDE_ADMIN_TOKEN` | 发版页、删脚本、查刷新结果 | 只有你 |
   | `AUTOSLIDE_REPORT_TOKEN` | `POST /api/report/network` 写设备地址 | 编译进 AutoSlide 与 ADB 助手的 APK，等于半公开 |

   两个都**没配就一律拒绝**（fail closed），不会退化成人人可写。上报口令为空时
   上报接口回 503，设备会静默停止上报——服务看着一切正常，只是地址永远不再更新，
   所以换机器部署后务必确认这一项配上了。

   读那一侧（看板首页、`GET /api/device/address`）没有口令，是有意的：看板本来就是
   公开的，读出去最多是「知道某台设备此刻在哪」；写却能把某台设备的地址改成任意值，
   Easycontrol 下次就会去连那个地址。

   放 drop-in 而不是 unit 文件的原因：仓库是公开的，口令写进模板等于公开发布；
   而且模板会被上面那步 `cp` 覆盖，drop-in 不会。

7. **防火墙/网络**
   - 容器内：`apt install -y ufw && ufw allow 8080`（或 iptables）。
   - 只在局域网用，手机直接访问 `http://容器IP:8080`。
   - 如果需要外网访问：建议用 Nginx/Caddy 反代并加 HTTPS，不要把 8080 裸暴露到公网。

## App 端配置

服务地址不在 Kotlin 代码里，改根目录 `gradle.properties` 的这一行即可：

```properties
autoslide.serverBaseUrl=http://你的PVE容器IP:8080，https://pve.8450696.shop/
```

构建时经 `buildConfigField` 注入 `BuildConfig.SERVER_BASE_URL`，由
`app/src/main/java/com/ziek/autoslide/Constants.kt` 里的 `SERVER_BASE_URL` 统一对外提供，
统计上报（`/api/report`）、录制脚本备份（`/api/upload`）、聊天室（`/api/chat/*`）三者共用。

也可以不改文件，构建时临时覆盖：

```
./gradlew assembleRelease -Pautoslide.serverBaseUrl=http://192.168.100.254:8080
```

重新编译安装即可。App 会在首次安装和版本升级时上报一次设备信息。




项目里（源码，可随时修改/重新部署）
C:\Users\Administrator\Desktop\AutoSlide-master\server\
server.js — 入口，只负责装配路由与监听（约 40 行）
src/config.js — 端口、数据目录、各类上限
src/store.js — JsonStore：原子写 + 串行化写队列，三份 JSON 数据共用
src/http.js — 请求体读取、响应封装、客户端 IP、路由表
src/paths.js — 文件名清洗与路径越界校验（防目录穿越）
src/ip.js — IP 归属地查询（带缓存上限）
src/util.js — 时间戳 / HTML 转义 / 字节格式化
src/stats.js — 统计域：/api/report、/api/stats、统计看板
src/chat.js — 聊天域：/api/chat/*
src/uploads.js — 上传域：/api/upload、/api/download、/view
src/views/styles.js、src/views/pages.js — 页面样式与模板
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

systemctl restart autoslide-stats 重启服务

cat /etc/systemd/system/autoslide-stats.service.d/token.conf
systemctl show autoslide-stats -p Environment 查看口令