# #14 Android 自动化与隔离验收报告（2026-10-23）

**总体 NOT ACCEPTED / PARTIALLY_COMPLETED。** Android 模拟器与独立 Signal Server 自动化、广州隔离验收已通过；
Android 真机独立证据仍缺失，不能宣布 Android 全部交付，也不关闭 Issue #14。PR #15 保持 Draft，尚未合并。

唯一任务 authority 是 [Issue #14](https://github.com/tevriqorg/Signal/issues/14)。当前用户明确不开发 iOS，
不接 AIBOX/P14、不用 Hermes、不扩大产品范围；没有免除 #14「Android 真机操作另留独立证据，不用模拟器替代」的要求。
只读检查现有 Mac mini ADB 为零连接设备，未安装到日常手机、未访问私人媒体。

## 可复核结果

产品与测试源码：`308ae2721b0b7fbdb712d00fecb77b5cfd71e6ec`。

| 检查 | 结果与原始 CI |
|---|---|
| 本地中心 + 两台独立 Android API 35 模拟器 | [39877715665](https://github.com/tevriqorg/Signal/actions/runs/39877715665)：release、29 项标准 instrumentation、27 阶段双模拟器全部通过 |
| 广州公网 HTTPS + 两台独立 Android 模拟器 | [39877778289](https://github.com/tevriqorg/Signal/actions/runs/39877778289)：release、标准 29 项、目标双机 27 阶段全部通过 |
| 实际 OCR / 中英语义模型 | [39877715719](https://github.com/tevriqorg/Signal/actions/runs/39877715719)：float/int8 两种实际模型配置通过 |
| 独立服务端核心回归 | [39877715995](https://github.com/tevriqorg/Signal/actions/runs/39877715995)：14 项通过 |
| 前一提交重复完整回归 | [39876249251](https://github.com/tevriqorg/Signal/actions/runs/39876249251)：29 项标准 + 27 双机阶段通过；仅旁证，不替代最终提交 |
| 广州当前隔离 namespace 的持久化/恢复 | [target-runtime.json](evidence/20261023/target-runtime.json)：实际 GUANGZHOU OCR；服务重启后认证/原件/索引保留；一致备份、独立路径恢复、逐文件 SHA 验证通过 |

两个 workflow 中的标准 29 项使用 runner 本地合成服务；其中 API 合成第二设备测试不当作双 Android 验收。
真正双机阶段使用 `emulator-5554` 与 `emulator-5556`，分别持有 userdata、安装 APK、MediaStore 和任务状态。
真实 APK client/engine 发起传输，UI Automator 验证相册、来源、权限、搜索和 VideoView 播放；不声称所有传输都由手势发起。
目标双机走认证公网 HTTPS、平台默认 CA/主机名校验，没有 TLS 绕过，也不依赖手机进入 Tailscale。

## 双机故障与数据证据

| 项目 | 本地中心 | 广州 HTTPS |
|---|---:|---:|
| 有效 H264/36 秒视频 | 143,614,122 bytes | 143,614,122 bytes |
| 断网与强杀前已上传 | 2,097,152 bytes | 1,310,720 bytes |
| 断网与强杀前已下载 | 1,122,304 bytes | 1,174,400 bytes |
| 原始成功 phase 文件 | 27 / 27 | 27 / 27 |
| 两台 Doze deep 状态 | IDLE / IDLE | IDLE / IDLE |

断点恢复后比对完整 SHA，并验证实际解码/播放。后台视频策略测试确认同步元数据/封面不会推进原件 offset。
完整阶段还包括 A/B 图片互传、双向离线强杀恢复、权限 full/partial/denied、SQLite v1→v2、实际 JobScheduler/Doze、
离线修改后的新原件/封面接收、用户清理本机后中心来源仍正确。权限部分使用实际 OS grant/revoke 与 UI 标签，不冒充全部系统照片选择器交互覆盖。

[需求矩阵](ISSUE14_ANDROID_MATRIX.md)逐项对应代码与测试。
[永久合成证据](evidence/20261023/)包含两组 27 个原始成功结果、网络切断/恢复、Doze、APK/视频哈希、原始 XML、截图及全量原始文件 SHA 清单。
GitHub 原始完整产物保留 90 天；长期保留的精选证据以清单的 `retained_in_git` 标记。没有提交 137 MiB 视频、数据库、私有地址或凭据。

- [广州混合相册](evidence/20261023/target/A.png)
- [广州实际视频播放](evidence/20261023/target/video-playing-B.png)及 [UI hierarchy](evidence/20261023/target/video-playing-B.xml)
- [手机手动清理后的来源/缓存状态](evidence/20261023/target/archive-A.png)

## 修复与独立审查

修复覆盖来源版本失效/去重与断点、分页游标、持久错误和短时离线区分、AI degraded、预览/原件独立状态、损坏缓存恢复、
大文件软预算/磁盘空间、KeyStore 并发与凭据丢失恢复、换中心旧请求/证明隔离、视频机会策略/克制通知、后台调度、
原件损坏拒读与修复、备份完整性、认证撤销/配额/限流。服务器运行源码与当前仓库逐文件 SHA 已核对。

独立审查不仅看绿色 job：检查原始 29 项 XML、两组 27 个 phase、两 serial、真实网络切断/恢复、故障前字节、Doze、
Gallery/视频截图与连续 logcat。后续证据文档提交不改变已测 app/main、androidTest、server 或脚本行为；源码 tree ID 见 source-trees.json。
新增 CI test APK 产物为独立真机验收准备，不是生产签名发布。

## 失败记录与剩余边界

- [39857475106](https://github.com/tevriqorg/Signal/actions/runs/39857475106)：通知投递异步断言失败已改为等待实际通知；resume-video 曾在约 7 秒退出 255，旧现场日志不足，**根因未确认**。后续完整回归未复现，已增加连续日志和失败采集，不写成「255 已修复」。最新若再现仍是阻断。
- `39873755998` 的单机 SDK ZipFile 下载故障未启动测试。当前准备步骤加有界 SDK 重试并固定已审查 Action；Action 内部后续 metadata 调用仍靠整 job 最终时限。
- 旧本地/目标 run `39873693619` / `39873755998` 被取消；依据过早零记录快照判断目标停滞不当。完整日志确认目标实际通过 phase 1–8、进入 phase 9；不计完整 PASS。最新使用新 namespace 重跑全 27，旧原件保留。
- 原始日志确认首次 FFmpeg 安装从 runner Azure mirror 下载依赖耗时很长。现已在 boot 前通过官方签名 HTTPS 源、有界安装并合成相同视频，减少运行中等待，未删视频验收。
- 东京下载证据曾 ENOSPC。先盘点，保存 SHA 清单，仅移除可重新下载的本任务旧成功缓存；随后完整重取并核验，没有清理用户数据、失败现场或广州原件。
- 真机/OEM 电量策略、真实蜂窝切换、物理设备图库选择器、生产签名与发布未验收。3 GiB 仅测缓存策略；2501 项仅测索引不截断，不声称 3 GiB 实传或 2501 张 UI 性能。视频 AI 是封面/帧级；未知地名不自动地理编码，不声称复杂事件理解。
- 早期 PR 分支含临时机器提交身份，修正 config 不会改写历史。若后续满足真机后集成，采用核验过的稳定 noreply 身份 squash，避免把这些祖先作为本次 main 新 ancestry；旧 PR 历史保留，不宣称已清除。

## 下一步与交付状态

需要一台无私人数据的专用 Android 真机接入现有 Mac mini ADB 后，继续独立真机操作与恢复验收；不以降标准代替。
CI 保存 debug/test APK 及 SHA，便于直接安装验证，unsigned release 仅为构建产物。
主线 merge、生产切换均未发生；Issue #14 继续打开，后续事实持续回写 [PR #15](https://github.com/tevriqorg/Signal/pull/15) 与 Issue。

By ChatGPT
