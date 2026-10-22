# Signal Server：Android 增强相册中心（#14）

Signal Server 是相册目录、设备来源、预览、完整原件、同步进度与 AI 搜索索引的唯一中心。
原件经过 SHA-256 校验并长期保存，不自动过期。运行时不依赖 AIBOX、P14 或 Hermes。
本次接管只实现 Android；Issue 中的 iOS 范围继续保留，不据此关闭整个 Issue。

## 隔离运行

```sh
export SIGNAL_ENROLL_TOKEN='replace-with-a-long-random-secret'
export SIGNAL_DATA_DIR='/private/location/for/persistent/photos'
export SIGNAL_BIND='127.0.0.1'
python3 server/signal_server.py
```

原始 HTTP listener 仅用于 loopback 集成或可信 HTTPS 反向代理的后端，不应直接公开。
Android release 只允许 HTTPS；HTTP loopback 是 debug 专用例外，不关闭 CA 或主机名校验。
具体主机、私有配置与部署事实只记录在 private companion，凭据与运行数据库不进入 Git。

当前是单个私人图库。持有 enrollment secret 可注册设备；各设备得到独立 bearer token，
Android 使用 Keystore 保护。显式重新配对发送 `recover_existing=true`，可在响应丢失或 Keystore 失效后
为同一 deviceId 重发凭据、撤销旧 token 并保留资产。普通重复注册仍返回 409。
该 secret 同时承担管理员身份，不能当普通设备 token 分发。
撤销设备使用管理员 enrollment key；轮换设备 token 后旧 token 失效。私人共享相册属于后续范围。

协议入口包括：

- `POST /v1/devices`：设备配对；
- `POST /v1/media`、`PUT /v1/media/{id}/thumbnail`：元数据、封面；
- `POST /v1/uploads`、`PUT /v1/uploads/{id}`：可恢复原件上传；
- `GET /v1/media?since=...`、`GET /v1/media/{id}/thumbnail`、`GET /v1/media/{id}/original`：目录与读取（原件支持 Range）；
- `GET /v1/search?q=...`、`GET /v1/status`、`POST /v1/analysis/retry`：搜索、状态与分析重试；
- `POST /v1/devices/rotate`：轮换当前设备凭据；
- `POST /v1/devices/{id}/revoke`：凭 `X-Enrollment-Key` 撤销设备；
- `DELETE /v1/media/{id}`：原设备写入目录 tombstone，保留已存原件。

除注册与使用独立管理员校验的撤销操作外，`/v1` 请求需要设备 bearer 凭据。

## 完整性、恢复与容量

同一来源设备与本机媒体 ID 对应稳定资产 ID。来源版本变化会使旧原件证明、封面与上传会话失效，
但不删除历史字节。上传会话绑定设备、来源版本、大小、偏移与 SHA；文件 fsync 后才能提交。
服务端在去重、读取时核验实际原件；损坏对象使相关资产转为待修复，不能继续报告校验成功。
目录分页游标与资产当前版本分别传递，客户端不允许旧回放覆盖新状态。

`SIGNAL_STORAGE_QUOTA_BYTES` 限制存储与会话预留容量。服务端保留磁盘余量，限制 HTTP 并发、
连接读取时间与每 IP 请求频率。正式部署还需配置反向代理 body/timeout 限制及运行监控；
隔离验收不等于生产批准。

备份先停止该服务，再调用 `MediaService.backup(destination)`，得到 SQLite、媒体目录和 SHA manifest。
恢复时先逐项核验 manifest，在独立空位置放置备份的数据库与 objects/thumbs/staging 目录，
以该位置重新创建 `MediaService` 或启动进程，核验认证、目录与原件读取后再另行决定切换。
目前没有专用 restore API。损坏原件会阻止生成有效备份 manifest；任何恢复都不自动覆盖生产。

## 中心侧真实 AI／OCR

在独立 virtualenv 安装 CPU 二进制 wheels（`server/requirements-ai.txt` 和 CPU torch/torchvision），
以及 Tesseract 的英文、日文、简体中文语言数据。设置 `SIGNAL_AI_ENABLED=1` 启用。
默认使用 SentenceTransformers `clip-ViT-B-32` 图像模型和 `clip-ViT-B-32-multilingual-v1` 文本模型。
可用 `SIGNAL_IMAGE_MODEL`、`SIGNAL_TEXT_MODEL` 指向完整本地快照；离线部署设置
`HF_HUB_OFFLINE=1`、`TRANSFORMERS_OFFLINE=1`。不调用外部推理 API。
`SIGNAL_AI_INT8=1` 顺序量化线性层以降低内存；float 与 int8 均有真实模型 CI。

索引持久化实际 OCR、EXIF 上下文、位置和视觉向量，绑定原件 SHA。搜索综合中英语义相似度、
文字／文件名／已有上下文、日期（Asia/Tokyo）及 GPS 邻近关系，并返回匹配依据。
未知地名依赖已有描述或上下文，不调用外部地理编码服务。无法读取的媒体进入有退避的异常状态，
不阻塞其他资产；认证状态接口区分待分析、分析失败及存储异常。

## 验证

```sh
python3 -m unittest discover -s server -p 'test_*.py' -v
SIGNAL_TEST_AI=1 python3 -m unittest discover -s server -p test_search_index.py -v
```

第一条命令默认跳过真实模型测试；`search.yml` 单独安装真实 OCR／模型测试。
`android.yml` 使用两套独立可写 Android 35 模拟器和真实 APK／MediaStore，执行互传、ADB 断网强杀、
UI Automator、视频解码播放、权限、迁移与 OS worker。服务端虚拟设备测试不能替代双模拟器验收。
完整状态、失败记录与证据见 `docs/acceptance/ISSUE14_ANDROID_MATRIX.md`。

真机／厂商后台限制、生产公网入口切换和 iOS 均不能从模拟器或 loopback PASS 推定通过。
