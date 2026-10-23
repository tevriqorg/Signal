# #14 Android 需求—代码—测试—证据矩阵

唯一任务 authority：`tevriqorg/Signal#14`；工作 PR：#15。当前用户明确限定 Android 与独立 Signal Server，
不开发 iOS、不接 AIBOX/P14、不使用 Hermes、不接触私人数据、不切换生产。

**整体结论：NOT ACCEPTED；Android 模拟器自动化及广州隔离服务验收已通过，真机条件仍阻断最终交付。**
#14 明确要求「iOS 真机及 Android 真机操作另留独立证据，不用模拟器替代」。本次没有收到免除 Android 真机的授权，
也不修改此标准。现有 Mac mini ADB 只读盘点为零连接设备；未访问任何私人媒体。Issue 保持打开，PR 保持 Draft。

下表的自动化证据绑定产品/测试源码 `308ae2721b0b7fbdb712d00fecb77b5cfd71e6ec`；
[报告](ISSUE14_ANDROID_REPORT_20261023.md)记录 CI、失败历史及边界。
[N](evidence/20261023/normal/)、[T](evidence/20261023/target/)分别是 runner 本地中心与广州公网 HTTPS 中心的两模拟器产物。
标准 29 项使用本地合成服务，不冒充广州或双 Android 验收。

| #14 需求/审计项 | 代码 | 独立验证及永久证据 | 判定 |
|---|---|---|---|
| 1、7 混合时间轴、具体设备来源、来源与缓存正交 | GalleryActivity、GallerySource、MediaIndexCoordinator | N/T phase 5/7/9/12/26/27；A/B/edited-B/archive-A.png | 模拟器通过 |
| 2 无点/黄/红三态；短时离线正常等待 | GalleryTransferBadge、GallerySyncEngine、Activity | GalleryTransferBadgeInstrumentedTest；N/T phase 8/11 离线不红、错误 SHA/预览失败隔离；archive-A.png | 自动化通过 |
| 3 全局健康云、二级容量/进度/错误/重试 | GallerySyncSnapshot、GalleryActivity | GallerySystemHealthInstrumentedTest：401 跨缓存清理/重开仍红、完整成功同步才恢复；AI degraded；真实 Gallery 绿色云截图 | 自动化通过 |
| 4 前台立即本地浏览、增量交换、系统允许时后台续传 | GalleryActivity、GalleryBackgroundWorker、MediaIndexCoordinator | N/T phase 20–23；A/B-idle-state.txt 均 IDLE、实际 JobScheduler 运行；双机重开与离线恢复 | 自动化通过 |
| 5 图片自动原件、视频封面先行/前台高效 Wi-Fi | SignalMediaClient、GallerySyncEngine | N/T phase 1–3；后台视频原件 offset 不增长；实际断网/验证非计费 Wi-Fi 恢复；143,614,122-byte H264 | 自动化通过 |
| 5 克制通知、不自动前台 | GalleryVideoNotification | videoNotificationIsPermissionAwareAndThrottled：真实 activeNotifications、权限、24h 节流、无 fullScreenIntent | 自动化通过 |
| 6 稳定 ID、版本失效、幂等/去重、完整校验、恢复 | server、GallerySyncStore、SignalMediaClient | N/T 上传/下载至少 1 MiB 后断网+强杀；SHA/Range；phase 24–26 离线修改原件与封面后 B 实收；核心 14 项 | 自动化通过 |
| 6 手机默认保留、用户手动清理后中心仍保管 | MediaIndexCoordinator、GallerySyncEngine、server tombstone | N/T phase 27 实际删除合成手机文件；archive-A.png 中中心副本仍按来源设备显示；保留旧对象不设 TTL | 自动化通过 |
| 6 异常/损坏数据、安全备份恢复 | MediaService | 损坏原件拒读/失效/修复、owner/auth/撤销/轮换/配额/保留空间/限流；target-runtime.json 记录当前 namespace 重启/备份恢复 SHA | 自动化通过 |
| 7 懒加载、预览/原件分别校验、缓存清理/方向/大文件预算 | SignalMediaClient、GalleryImageDecoder、Engine | N/T 缓存字节损坏自愈；GalleryRecoveryInstrumentedTest；实际 VideoView 播放 PNG/XML；3 GiB 软预算策略测试 | 自动化通过；不声称实传 3 GiB |
| 8 中心真实 OCR、中英语义、时间/位置/上下文及解释 | server/search_index.py | 实际 float/int8 CI；OCR/红蓝排序/GPS/EXIF/坏媒体退避；APK 搜索；目标真实 GUANGZHOU OCR 与重启后检索 | 自动化通过 |
| 9 认证公网 HTTPS、平台 CA/主机名校验、非 Tailscale 必经 | SignalPairingStore、SignalMediaClient、server | T profile=isolated-target-https、fixture_run=39877778289；两 serial；真实 HTTPS 原件互传；证据已脱敏，无 TLS bypass | 自动化通过 |
| 凭据丢失/换中心隔离/并发 KeyStore | SignalPairingStore、Engine、SyncStore、server enrollment | PairingRecoveryInstrumentedTest：实际删除密钥后稳定 ID 恢复、旧 token 撤销、中心更换清理旧证明/游标与取消旧请求；并发读写 | 自动化通过 |
| 完整/部分/拒绝权限、数据库迁移 | MediaPermissionPolicy、SQLite stores | N/T phase 10/13 实际 v1→v2、phase 14–19 两台真实 pm grant/revoke 与 UI 标签；旧 verified 不冒充完成 | 自动化通过 |
| 全量索引/分页/游标不可倒退 | MediaIndexCoordinator、SyncStore、server catalog | 2501 项虚拟索引、分页、旧 catalog replay 与 cursor_seq；不是 2501 张 UI 性能证明 | 自动化通过 |
| 广州隔离、合成数据、不切生产 | 私有 companion 与 task-owned runtime | T 完整 27 阶段；target-runtime.json；旧 namespace/原件保留、没有清库覆盖 | 隔离验收通过 |
| Android 真机独立操作证据 | 待连接专用测试机后使用 CI debug/test APK | 2026-10-23 只读 ADB 盘点无设备；没有用模拟器替代 | **未验证，阻断最终 Android 交付** |
| branch→PR→CI→review→merge | PR #15 | 自动化与独立审查证据齐备；真机条件未满足，不提前合并/关闭 | **未完成** |
| iOS、10 私人分享及其他后续能力 | 本次不实现 | 用户明确排除 iOS/扩展范围；不宣称整个双平台 #14 完成 | 本次排除，Issue 仍打开 |

证据通过不抹去失败：历史 exit 255 根因未确认；旧 target 取消判断不当与东京证据下载 ENOSPC 已如实记录。
详见报告。未经单独授权不切换生产。

By ChatGPT
