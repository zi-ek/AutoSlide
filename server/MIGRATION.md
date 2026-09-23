# adbboot 联动迁移说明

## 目标与结果
Easycontrol 被控端联动由 adbboot 独立提供，运行不再依赖 AutoSlide。AutoSlide 保留自动滑屏、点击、脚本、聊天、授权等原有业务。

## 模块职责
- `AgentService`：前台通知、网络变化防抖上报、周期心跳、中转连接生命周期。
- `RelayAgent`：WebSocket 控制通道、双向 ADB 数据桥接、重连、即时 TCP/IP 恢复请求。
- `AdbEnableService`：原有密钥和无线调试启动流程，新增并发执行保护。
- `Registry` / `ReportJobService`：共用地址登记，15 分钟任务作为兜底。
- `LocationTracker` / `TrackState`：迁入原有定位、运动门控、静止自动关闭与跨进程状态。
- `TrackRecordService`：独立 `:trackrecord` 进程中的定位前台服务及统计通知。
- `TrackUploadJobService`：在同一轨迹进程中补传缓存，关闭采集后仍可排空队列。
- `TrackCommandActivity` / `TrackStatusReceiver`：仅 shell 可调用的远程开关和执行状态查询。
- `AgentSettingsActivity`：联动开关、轨迹开关、运行状态和权限引导。
- `DebugApprovalService`：用户自行开启的调试授权弹窗辅助，仅处理系统应用中的调试确认。

## 后台兼容
继续使用 AutoSlide 仓库的 Node.js 后台，不需要重建服务器或更换域名。
- 轨迹上传新增 `serial` 寻址，保留旧 `deviceId` 上传用于历史缓存补传。
- 新助手发送 `agent: adbboot`；后台保留设备原有记录和历史轨迹标识。
- 新设备只有序列号也能建档、记录轨迹、通过看板查询。
- 为 `serial:xxx` 记录生成安全的独立轨迹文件 ID，避免冒号被文件名校验拒绝。
- 新旧记录合并保留连接地址、归属信息和轨迹文件关联；读取时合并并去重。
- adbboot 接管后旧版网络上报不能覆盖新地址，旧中转连接不能顶掉新助手。

## 升级顺序
1. 先备份服务器现有 `data/`，然后更新 `server.js`、`src/`、`package.json`、`package-lock.json`；执行 `npm ci --omit=dev` 并重启现有服务。不要覆盖或删除 `data/`，现有环境变量继续使用。
2. 更新 Easycontrol。保持原 ADB 连接可用，执行现有“开机自动开启 tcpip”部署入口，将新版 adbboot 安装到被控端。
3. 部署只向 adbboot 交付密钥和序列号；会尝试停止旧 AutoSlide 的轨迹采集，并打开助手联动设置。
4. 被控端为 adbboot 单独允许通知；需要轨迹时再授予定位及“始终允许”。旧 AutoSlide 的权限不会自动继承。
5. 更新 AutoSlide。新版移除旧联动与轨迹服务，取消原轨迹复活任务，并自动安排旧 `track_queue.jsonl` 的网络补传。
6. 验证新助手直连、中转、网络切换、远程轨迹开关。若旧版尚有未上传缓存，保留其应用数据直至补传完成，再考虑卸载 AutoSlide。

全新设备直接部署新版 adbboot 即可，无需安装 AutoSlide。自动化功能仍需 AutoSlide 时，两个 App 可同时使用。

## 保持的行为和限制
- adbboot 最低 Android 11；release 沿用 arm64-v8a 发布范围。
- 图标控制、密钥导入、现有 ADB 连接与投屏协议不变。
- Easycontrol 亮屏/解锁状态仍通过 ADB 查询，不增加不存在于当前代码的亮屏上报模块。
- 轨迹默认关闭；实时联动前台服务有系统通知。关闭实时联动不会关闭原有周期地址兜底。
- 应用被强制停止、后台启动受系统限制、权限被撤销时，不保证自行恢复；助手显示状态和失败原因，必要时需重新打开。
- 旧 AutoSlide 的私有缓存不能直接由新包读取，因此保留升级补传代码，不进行跨包私有文件访问。
- 本次不改变 AutoSlide 的试用/邀请规则，adbboot 不引入时长限制。

## 回滚
本地完整源码备份见工作区 `backups/verified-before-adbboot-migration-20260917-232648/`，逐文件 SHA-256 和 ZIP CRC 已校验。
线上回滚须恢复部署前的服务端数据备份以及原 App 安装包。新版后台记录的 `agentOwner` 会拒绝旧中转接管，不应只回退助手而保留接管状态；如需这种混合回滚，先有针对性地解除目标序列号的接管标记，并停用新助手，避免两个版本争用。

## 验证
`npm test` 使用临时数据目录和本地 HTTP/WebSocket 服务，覆盖历史轨迹续接、新设备首次上传、影子设备合并、非法请求、旧中转退让和二进制双向转发。不会连接或修改线上服务器数据。
Android 构建结果和待真机验证项目见工作区迁移完成报告。
