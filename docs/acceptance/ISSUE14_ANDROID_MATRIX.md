# #14 Android 独立验收矩阵

唯一任务 authority：`tevriqorg/Signal#14`；工作 PR：#15。用户本次明确限定 Android 与独立 Signal Server，
不开发 iOS、不接 AIBOX／P14、不使用 Hermes、不接触私人数据、不切换生产。

**当前结论：NOT ACCEPTED。** `c183c03` 是已通过的阶段基线；后续独立审查发现的状态、配对和缓存问题
修复后必须重新验证。此前 PASS 不作为当前提交最终验收。Issue 保持打开。

| #14 需求／审计项 | 实现位置 | 测试与证据 | 当前判定 |
|---|---|---|---|
| 1、7 混合时间轴、具体来源、缓存与来源正交 | GalleryActivity、GallerySource、MediaIndexCoordinator | 双机 A/B 截图；本机清理后中心副本仍可读；新增 UI 断言 | 阶段通过，当前重测 |
| 2 三态圆点，无点／黄／红，不因短时离线报红 | GalleryTransferBadge、GallerySyncEngine、Activity | 圆点语义测试；真实离线下载与目录状态断言 | 审查发现误报，修复重测 |
| 3 全局健康云、二级容量／设备进度／重试 | GalleryActivity、GallerySyncSnapshot | UI Automator、错误隔离、分析／存储状态 | AI 全局 degraded 补入，重测 |
| 4 前台立即浏览、增量同步、后台允许时续传 | GalleryActivity、GalleryBackgroundWorker | 双机断网／重启、真实 Doze IDLE、JobScheduler 后续执行 | 阶段通过，当前重测 |
| 5 视频封面先行、前台高效 Wi-Fi 上传 | SignalMediaClient、GallerySyncEngine | 双机有效 H264 MP4 143,614,122 bytes；上传／下载中断，实际播放 | 137 MiB 阶段通过；>2 GiB 缓存软预算修复重测 |
| 5 克制通知、不自动前台 | GalleryVideoNotification | 权限与 24h 节流测试，immutable PendingIntent | 阶段通过，当前重测 |
| 6 稳定 ID、版本、恢复、去重、完整原件、保留手机 | server、GallerySyncStore、Engine | 来源修改／旧会话、SHA、Range、重复上传；双机离线修改后新原件和封面 | 阶段通过；配对凭据丢失／换中心隔离修复中 |
| 6 数据损坏、异常恢复、备份 | MediaService | 11 项核心回归、损坏原件拒读／修复、备份 manifest、隔离重启与恢复 | 服务端阶段通过，最终代码重测 |
| 7 原件懒加载、缓存校验与清理 | SignalMediaClient、GalleryImageDecoder | 双机缓存字节损坏自愈；方向与像素预算；手动清缓存不删手机／中心 | 预览损坏自愈及大原件预算修复重测 |
| 8 真实 OCR、中英语义、时间／位置／上下文解释 | search_index.py | 真实模型 float/int8；TOKYO OCR、红蓝排序、GPS 邻近与 EXIF 描述、坏媒体退避 | 实际 AI 阶段通过，当前代码重测 |
| 9 正常 HTTPS、认证、无 Tailscale 必经依赖 | PairingStore、SignalMediaClient、server | HTTPS CA／主机名负例；目标服务双模拟器公网 HTTPS 路径 | 目标双模拟器验收补做中 |
| 完整／部分／拒绝权限与迁移 | MediaPermissionPolicy、SQLite stores | 双机真实 pm grant/revoke；媒体库与同步库升级；旧证明不冒充完成 | 阶段通过，当前重测 |
| 全量库不截断、游标不倒退 | MediaIndexCoordinator、SyncStore、server catalog | 2501 项索引、分页当前版本／游标独立、旧状态回放 | 阶段通过；不是 2501 张 UI 性能证明 |
| 独立安全与恢复 | server、Keystore | owner/auth、撤销／轮换、限流／配额、损坏数据、持久化 | 配对恢复专项补测中 |
| 广州隔离环境，合成数据，非生产 | private companion | 真实 AI、服务重启、认证／原件持久化、备份恢复 SHA | 已通过目标 API／AI；不能替代目标双模拟器 |
| review → main merge → merged result 验证 | PR #15 | 独立审查、当前 head 全部 CI、主线集成 | 尚未完成 |
| 10 私人分享／其他后续能力 | 无本次实现 | 用户明确不扩大范围 | 排除，不伪装已交付 |

## 已核验的阶段证据

- Android `c183c03`：[run 39820356891](https://github.com/tevriqorg/Signal/actions/runs/39820356891)：release、24 项标准 instrumentation、双模拟器 27 阶段。
- 实际 AI：[run 39820357091](https://github.com/tevriqorg/Signal/actions/runs/39820357091)，float/int8。
- 核心服务端：[run 39820356695](https://github.com/tevriqorg/Signal/actions/runs/39820356695)。
- 两台真实 serial：`emulator-5554`、`emulator-5556`，各自 userdata／APK／MediaStore。
- 上传注入故障前已传 1,310,720 bytes；下载注入故障前已收 1,187,840 bytes，随后断连接并 force-stop，恢复后比对完整 SHA。
- 测试通过 APK 中的真实 client/engine 发起传输，UI Automator 验证浏览、来源、搜索和播放；不声称所有传输都由 UI 手势发起。
- `cca318a` 后台恢复失败与 `520e327` accessibility 窗口竞态均保留失败记录，修复必须重测，不能用其他 head 的成功覆盖。

最终报告必须在当前代码、目标 HTTPS、独立审查和 main 集成全部完成后更新；未通过项不修改标准或标为通过。
