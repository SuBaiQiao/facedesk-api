# Offline Face API — YuNet + SFace + SQLite

## 默认模型升级：AuraFace-v1

默认识别模型现为 AuraFace-v1（ArcFace / ResNet100，512维），检测仍使用 YuNet；完全本地运行。权重固定为 fal/AuraFace-v1 提交 af6d057c9b0ec4071d4c49c80e3539258798b609 的 glintr100.onnx，本地命名 face_recognition_auraface_v1.onnx。使用112×112五点对齐、RGB、(pixel−127.5)/127.5预处理及L2归一化。发布方模型卡标注 Apache-2.0，许可与出处见 THIRD_PARTY_NOTICES.md。

升级步骤：执行 scripts/download-models.ps1 准备模型，然后 mvn package；停止旧服务后启动新JAR并刷新页面。若使用旧部署目录的外部 application.yml，需同步新配置：

```yaml
face:
  recognition-backend: auraface
  recognizer-model: face_recognition_auraface_v1.onnx
  inference-threads: 4
  workers: 1
```

首次启动遇到旧SFace数据库时，服务持有原有单实例锁，先使用 SQLite VACUUM INTO 在同目录生成 face.db.before-auraface-<UUID>.db 完整快照（包含已提交WAL数据），再从主照片和所有参考原图提取512维特征；最后通过单个事务切换特征与模型指纹。人员编号、姓名、原图、参考照片ID、时间及历史均保留。任何照片缺失或检测失败会停止启动并指出档案，原库不会切换；先使用旧SFace配置修复照片再升级。迁移时间随照片数量增加。

阈值0.55与分差0.08暂保留作为保守起点，尚未通过你的现场同人/异人数据标定；两个模型的分数不可横向比较。模型卡也说明不同人群的效果可能存在差异，更大的模型不保证每一张照片效果更好。原模型的10FPS及并发压测结果不适用于AuraFace。默认单推理实例、4个OpenCV CPU线程，避免4份ResNet100模型占用过大内存。多人画面的耗时随识别人脸数增加；USB使用串行最新帧，不积压旧画面。

回滚：停止服务，将升级前快照恢复到一个新的数据库路径，用 --face.storage-path 指向它，并同时设置 --face.recognition-backend=sface --face.recognizer-model=face_recognition_sface_2021dec.onnx --face.inference-threads=1 --face.workers=4。保留当前升级库，避免覆盖升级后的新增档案/历史；回滚快照不含这些新增记录。禁止对升级库直接使用旧特征模型。


Java 11 / Spring Boot 2.7 离线人脸服务。使用 YuNet 检测、SFace 识别与 SQLite 数据库，保留 `/api/face/v2/*` 接口和 `{code,msg,total,data}` 响应外形。

## 启动与数据位置

```powershell
mvn package
java -Xms512m -Xmx2g -jar target/offline-face-api-1.0.0.jar
```

工作目录须包含 `models/`。默认监听 8080。首次启动自动创建 **`data/face.db` SQLite 数据库**，不再使用 JSON 保存人员数据，无需另装数据库服务或联网激活。更改数据库位置：

```powershell
java -jar target/offline-face-api-1.0.0.jar --face.storage-path=D:/face-data/face.db
```

相对路径按启动工作目录解析。SQLite JDBC 和 OpenCV 原生库已打入 JAR，部署服务器只需 Java 11。状态查询：

```powershell
curl.exe http://127.0.0.1:8080/api/face/v2/status
```

## 网页管理

启动服务后访问 **http://127.0.0.1:8080/api/**，无需前端开发服务器、Node.js、CDN 或外部字体。网页已内置在 JAR 与离线包中。

管理页采用深色科技控制台主题，包含矢量人脸网格、五点特征标记和与真实引擎状态联动的扫描动效。暂停、故障或断开连接时停止扫描；遵循系统“减少动态效果”设置。图形资源全部由本地 SVG/CSS 构成，不上传照片，也不依赖在线素材。

- **人脸档案**：分页浏览、姓名/编号搜索、按角色筛选、上传照片录入、查看照片、更换照片、只修改姓名、删除前确认。列表不一次性传输所有人员照片。
- **识别验证**：选择或拖入照片，调用真实 1:N 识别，展示匹配人员、余弦相似度与耗时；验证照片不会自动录入底库。
- **视频识别**：上传本地视频，服务端顺序解码每一帧，检测同一画面中的多张人脸并逐个匹配底库。支持后台进度、取消、抓拍分页、点击时间点定位原视频与 CSV 导出。重复出现的人脸保留每次抓拍，另外统计匹配到的不同底库人员数；未匹配与过小人脸均保留。
- **识别历史**：照片 1:N 识别与视频识别自动写入 SQLite。按时间、来源、结果、姓名/编号/视频文件名筛选，支持分页、抓拍详情、同一视频任务回溯和筛选结果 CSV 导出。服务重启和视频临时任务清理后，历史仍保留。
- **实时摄像头识别**：USB 摄像头从浏览器所在电脑取流；RTSP 摄像头在页面直接输入地址，由服务器连接并返回最新识别画面。支持多人检测框、相似度、最近抓拍、停止和历史记录。
- **人员档案详情**：档案卡片点击“详情”进入独立页面，可查看主照片、可检测时的人脸区域、角色/编号/创建时间/模型信息、附加参考照片及精确关联的识别历史。可编辑主档案、添加和删除参考照片。
- **引擎控制**：实时显示运行状态、推理实例使用、排队数量、已完成任务、识别策略及模型/数据库信息。状态每 5 秒自动更新。

“暂停识别”会停止接收新任务，等待正在执行的完整任务结束，但保留模型；“恢复识别”继续处理新请求。暂停期间仍可查询、改姓名和删除档案，不能录入新照片或执行识别。“重载模型”等待已有任务完成再重新加载本地模型；暂停时重载后仍保持暂停。先前尚未执行的排队任务会返回 503，需客户端重试。

重载会核对启动时的模型指纹；若模型内容变化，不能继续匹配旧库，引擎进入异常状态。恢复原模型后可重试恢复/重载；换新模型应迁移原图到新库后重启服务。

管理页沿用现有服务的访问方式，未新增用户登录系统。管理修改和控制接口要求 `X-Face-Admin` 请求头，由内置网页自动添加。

| 方法 | 管理接口 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/status` | 引擎、任务和配置状态 |
| GET | `/api/admin/faces?role=&q=&page=1&size=12` | 分页档案，不含原图和特征 |
| GET | `/api/admin/faces/image?role=Guest&code=001` | 按需读取照片 |
| POST | `/api/admin/faces/update` | 修改姓名，可选新照片 |
| DELETE | `/api/admin/faces?role=Guest&code=001` | 删除档案 |
| POST | `/api/admin/engine/pause`、`resume`、`reload` | 引擎控制 |

录入仍使用现有 `/api/face/v2/faceCollection` 接口；同角色、同编号会覆盖已有档案，网页会在录入表单中提示。

## 数据库结构与读写

### 人员详情、多照片匹配与实时稳定确认

每人支持主照片加最多 4 张附加参考照片，建议补充当前摄像头下的正脸、轻微侧脸及不同光照照片，避免模糊、遮挡或过远。详情页添加照片时继续使用单人检测、最小人脸尺寸和原有模型：几乎重复的照片、与该人员现有样本相似度不足，或与其他人员的分差不足，会拒绝写入。参考照片不会由摄像头未知抓拍自动学习。

匹配先取同一人员各样本的最高余弦相似度，再比较不同人员的最高与次高候选；相似度阈值及分差要求不变。同一个人的多张照片不会互相占用“次高候选”。同一模型的原有单照片档案无需额外操作，附加样本保存在同一 SQLite 的 `face_reference_samples` 表中，启动只加载新增特征，参考图片按需读取。只改姓名会保留参考照片；更换/重新采集主照片会清空附加照片，页面会要求确认；删除档案会一起删除附加照片。

实时会话对位置连续的人脸，在最近 5 次检测中至少 3 次通过同一人员匹配后标为确认；短暂漏检时显示“复核中”，当前帧未达到阈值时不套用上一帧姓名，不补造匹配或历史。身份变化、位置不连续或长时间消失后需重新确认。`faces[].status` 和 `match` 保留单帧原始结果，`confirmed` 和 `displayState` 是实时确认状态；确认前不累计已确认匹配次数或写入该人员匹配历史。框的位置仍使用最新检测框，不降低跟框更新频率。摄像头、视频与历史的相似度改为小数显示，它是余弦相似度，不是准确率或身份概率。

| 方法 | 接口 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/faces/detail?role=&code=` | 档案元数据、主图检测框（引擎可用时）、样本列表；不返回特征向量 |
| POST | `/api/admin/faces/references` | multipart `role`、`code`、`img`；需管理请求头，新增参考照片 |
| DELETE | `/api/admin/faces/references?role=&code=&id=` | 删除指定附加照片，需管理请求头 |
| GET | `/api/admin/faces/image?role=&code=&sampleId=` | 按需读取附加照片；不传 sampleId 则读取主照片 |
| GET | `/api/admin/faces/history?role=&code=&page=1` | 按角色与编号精确查询相关匹配历史，每页 5 条 |

独立详情地址为 `#person?role=角色&code=编号`，可刷新或收藏。旧匹配历史保留当时人员信息；不会按当前姓名模糊关联其他人员。多照片与连续确认有助于扩大样本覆盖和抑制单帧跳变，实际漏识别情况需要在目标摄像头、角度和光照下验证。

### 实时 USB / RTSP 摄像头

工作台入口 `/api/#live`。先录入人员，再选择来源、摄像头名称和检索角色，点击“连接并开始识别”。

- **USB**：连接打开网页的电脑上的摄像头，在页面选择视频设备，并允许浏览器摄像头权限。只采集视频，不采集音频；要求 HTTPS 或 localhost/127.0.0.1。通过普通局域网 HTTP 地址访问时，浏览器可能禁用摄像头 API。浏览器预览持续播放，每次识别完成后继续提交最新画面，目标频率为 10 帧/秒；画面压缩到不超过 1280×720。单请求串行、上一帧完成后才采集最新画面，不积压；缩略图和抓拍卡片约每 500ms 更新，历史采样规则保持不变。画面上显示单次请求耗时，实际速度仍受硬件和网络限制。
- **RTSP**：在页面输入例如 `rtsp://用户名:密码@192.168.1.100:554/stream1`，点击连接。账号密码中的 `@`、`:` 等特殊字符应做 URL 编码。地址通过 POST 请求发送到本机服务，不写入历史或返回给其他接口，不通过浏览器直接播放 RTSP。服务器通过 FFmpeg 使用 TCP 拉流，最多每秒输出 2 张识别画面，页面展示最新已识别画面及其检测框。H.264/H.265 支持取决于所安装的 FFmpeg 构建。

RTSP 需要服务器安装 FFmpeg 并加入 PATH，或者配置 `face.ffmpeg-path` 为可执行文件的完整路径。也可自行把可执行文件放在部署目录 `tools/ffmpeg.exe`（Windows）或 `tools/ffmpeg`（Linux/macOS），服务会在默认配置下优先使用它。USB 不依赖 FFmpeg。现有离线包**不捆绑 FFmpeg**，须在部署机器自行准备；程序运行时不会下载软件或连接云服务。当前开发机的 FFmpeg 检查通过。所用 RTSP 传输与超时参数参考 [FFmpeg 官方协议文档](https://ffmpeg.org/ffmpeg-protocols.html#rtsp)。

默认最多同时运行 4 个摄像头会话。RTSP 采集与推理分离，只保留最新待识别画面，较慢时跳过旧帧；这不同于离线视频的逐帧扫描。每路会话同一已知人员每 10 秒至多记录一次历史；未匹配和过小人脸按画面分区采样保存，每个分区/状态每 10 秒最多一次，这不是对陌生人的身份聚类。页面检测次数包含重复出现，不能视为不同人员数。

停止按钮或切换离开实时工作台会释放连接，USB 同时关闭浏览器视频轨道。关闭页面后，未成功发送停止请求的会话在超过 30 秒没有心跳时自动停止；RTSP 超过约 12 秒未收到新画面会失败并释放解码进程。断流后由用户点击重新连接，不会无限自动重试。暂停识别引擎可能使实时会话异常结束，恢复引擎后重新连接即可。服务重启后未结束的采集会话会在历史中标记为重启中断。

| 方法 | 接口 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/live/capabilities` | USB/RTSP 能力、FFmpeg 可用性和会话限制 |
| POST | `/api/admin/live` | JSON：`source`（USB/RTSP）、`name`、可选 `role`、RTSP 时 `rtspUrl`；返回会话 ID |
| POST | `/api/admin/live/{id}/frame` | USB multipart `frame`；最大 4 MB、1920×1080 像素，单会话串行处理 |
| GET | `/api/admin/live/{id}?after=序号` | 状态和计数；新画面才附带 `faces` 与 RTSP `image`（JPEG Data URL），刷新会话心跳 |
| POST | `/api/admin/live/{id}/stop` | 幂等停止并释放资源 |

所有 POST 仍要求 `X-Face-Admin: 1`。状态为 `CONNECTING`、`RUNNING`、`FAILED` 或 `STOPPED`。每次画面带宽高、识别序号、服务器接收时间及人脸结果；接口只保留最新画面。近期抓拍不等于全部视频录像，服务不保存连续录像。

### 识别历史

管理入口 `/api/#history`。`recognition_history` 表与 `face_records` 位于同一 SQLite 文件，自动创建，不修改旧人员数据或模型特征。历史保留识别发生时间（UTC）、匹配姓名/编号/角色快照、分数、阈值、抓拍缩略图、检测框，以及视频文件名、时间点、帧号和任务状态；修改或删除人员档案不改变旧历史。历史不保存整段视频或完整上传照片，也不向人员库自动添加记录。

每次有效照片 1:N 请求保存一条成功、未匹配或引擎异常记录；参数校验失败不记录。每个视频保存一条任务概览和逐帧人脸检测记录。概览可为完成、无脸、异常或取消；检测记录保持当时匹配结果，并显示最终任务状态，避免将部分视频处理当作全部成功。服务重启时尚未结束的视频会标记“重启中断”。历史列表统计包括任务概览，因此记录总数不等于抓拍次数或不同人员数。旧版本未保存的识别无法补录。

| 方法 | 接口 | 用途 |
| --- | --- | --- |
| GET | `/api/admin/history` | 分页查询；参数 `source`（IMAGE/VIDEO/USB/RTSP）、`status`、`q`、`from`、`to`、`taskId`、`page`、`size`（最大 100） |
| GET | `/api/admin/history/{id}` | 历史快照详情和抓拍 |
| GET | `/api/admin/history/export` | 按同一筛选条件导出 CSV，最多 10000 条，超过时要求缩小范围 |

`from` 与 `to` 使用含时区的 ISO 时间；开始包含、结束不包含。页面使用本地时间展示与输入。导出保留 UTC 时间，包含匹配元数据，不包含照片。历史不自动过期，会随视频逐帧识别量持续增长，应为 SQLite 留足磁盘空间并随数据库一起备份；视频任务页的一小时清理规则仅适用于临时任务文件。正常识别结果的历史写入失败时，请求不报告成功；视频历史保存失败会使任务进入异常状态。

### 视频识别接口与限制

入口：`/api/#video`。先录入底库人员，再上传视频；底库为空时人脸仍会被检测和抓拍，匹配状态为未匹配。视频和抓拍只在本机处理，不自动录入档案。

| 方法 | 接口 | 用途 |
| --- | --- | --- |
| POST | `/api/admin/videos` | multipart `video`，可选 `role`；返回后台任务 ID，要求 `X-Face-Admin: 1` |
| GET | `/api/admin/videos/{id}` | 状态、已处理帧数、检测次数、不同已知人员数及错误信息 |
| GET | `/api/admin/videos/{id}/faces?page=1&size=24` | 按时间顺序返回抓拍、0 起始帧号、秒数、人脸框、匹配信息；size 最大 100 |
| POST | `/api/admin/videos/{id}/cancel` | 请求取消，在当前处理步骤结束后停止；要求管理请求头 |
| GET | `/api/admin/videos/{id}/export` | 导出当前已完成部分的 CSV 元数据，不包含照片 |

每次检测状态为 `MATCHED`（满足服务端相似度及候选分差阈值）、`UNKNOWN`（未匹配）或 `TOO_SMALL`（小于 `face.min-face-size`，不提取特征）。视频中的模糊、遮挡、侧脸与小脸可能漏检；逐帧处理不等于保证识别所有实际出现的人脸。未匹配者不做身份聚类，不能把未匹配次数当作陌生人数。运行期间的人员库变更对后续帧生效。

单视频最大 500 MB，单并发视频任务，最多处理 30 分钟；每个任务最多保留 20 万次检测或 256 MB 抓拍结果。达到限制、暂停/重载引擎或解码异常时，任务标记为失败并明确提示，已处理部分可查看和导出，不会显示为完整成功。取消也保留已完成部分。普通图片接口仍限制 10 MB。

最近 4 个任务保留在本机临时文件中，终态约 1 小时后清理；新任务可能淘汰最旧的已完成任务。任务 ID 可在当前浏览器标签页刷新后恢复，服务重启后任务不可查询。正常关闭会清理文件；异常终止可能遗留系统临时文件，部署环境应定期清理其临时目录。

本机已验证 MJPEG AVI 和 MPEG-4 Part 2 MP4 解码。其他编码（如 H.264/H.265）与容器支持取决于部署平台 OpenCV 的视频后端。浏览器能预览与服务端能解码是两个独立能力，无法预览的视频仍可尝试识别；不能解码时请转换为 MJPEG AVI。时间点优先使用解码器提供的时间戳，缺失时按帧号/帧率估算。

主表 `face_records`：

| 列 | 内容 |
| --- | --- |
| `role`、`code` | 联合主键，同角色同编号再次采集会更新 |
| `name` | 姓名 |
| `image_base64` | 原图（保留现有接口需要的 Base64 / Data URL 格式） |
| `feature` | 128 维 float 的小端二进制 BLOB，512 字节 |
| `feature_space_id` | 检测模型、识别模型 SHA-256 和预处理版本 |
| `created_at`、`updated_at` | 创建、更新时间（SQLite UTC） |

`face_metadata` 保存数据库结构版本和模型指纹。错误模型、旧算法或损坏特征会在启动或写入时被拦截。

数据库启用 WAL 和完整同步，写入/更新/删除使用事务；提交成功后才发布新的内存快照，失败不会污染检索底库。2000 人特征启动时从数据库载入内存，识别无需每次查 SQL。250 个在途推理任务由默认 4 个独立推理实例处理；排队超限或超过 60 秒返回 HTTP 503。

此实现供单个服务进程使用。`.lock` 文件锁阻止多个服务共享同一库，锁文件保留不代表服务正在运行。可用 DB Browser for SQLite 等工具查看数据库。**人员变更应走服务接口**；外部直接改 SQL 不会刷新已运行服务的内存快照，需停服务操作并重启。不要把同一数据库放到网络共享盘供多实例使用。

备份时优先停服务后复制数据库；运行中备份须使用 SQLite 在线备份功能，不能仅复制主 `.db` 忽略 `-wal`。`face.db-wal`、`face.db-shm` 是 SQLite 正常的辅助文件。

## 已有 JSON 数据迁移

旧 JSON 文件不删除、不自动加载、不继续写入。支持两种迁移：

### 已有 SFace JSON（模型指纹相同）

向**新的空 SQLite 库**一次性导入：

```powershell
java -jar target/offline-face-api-1.0.0.jar --face.storage-path=./data/face.db --face.import-json=./data/sface-db.json
```

整批校验模型、维度和重复主键，再在一个数据库事务内写入。任何记录校验或 SQL 失败都会整批回滚，源文件不修改。目标库非空时拒绝导入，避免覆盖人员。

**导入成功后移除 `--face.import-json` 参数，后续正常启动。** 该参数不是持久化格式配置。

### 原来的 SeetaFace JSON，或模型指纹不同

不能复制特征，需要使用保存的原图重新提取。先启动新服务，再执行：

```powershell
./scripts/migrate-faces.ps1 -Source ./data/face-db.json -BaseUrl http://127.0.0.1:8080/api
```

脚本保留角色、编号和姓名，通过采集接口重新提取特征并写入 SQLite。同一 role/code 会更新；成功项逐条提交，失败项写入迁移报告。原图缺失、多人或小脸等失败记录需要补采。源文件保持不变。

## 离线部署

YuNet 模型 MIT、SFace 模型 Apache-2.0、OpenCV Apache-2.0，SQLite JDBC Apache-2.0，按许可证条件免费使用和商业部署。无 API Key、联网激活、运行时下载或云端推理；首次构建准备依赖和模型需要联网或提前复制缓存。许可与模型哈希见 `THIRD_PARTY_NOTICES.md`、`licenses/`。

模型在本机 `models/` 已准备好；源码不分发模型二进制时，在联网准备机执行 `./scripts/download-models.ps1`。缺少模型会启动失败，不回退模拟引擎。

```powershell
./scripts/package-offline.ps1 -Output ./target/offline-bundle-sqlite
```

生成 `target/offline-face-api.zip`，包含 JAR、两个模型、配置、许可和脚本，**不包含生产数据库**。如输出目录已存在，使用新的 `-Output` 路径，避免将旧运行数据混入部署包。

解压后 Windows 运行 `./run.ps1`，Linux 运行 `sh run.sh`，新采集数据落在解压目录的 `data/face.db`。本次验证平台为 Windows x64；其他平台需确认原生库与系统运行库兼容性。

## 匹配策略与配置

```yaml
face:
  storage-path: ./data/face.db
  import-json: ""
  workers: 4
  max-in-flight: 250
  request-timeout-ms: 60000
  threshold: 0.55
  min-margin: 0.08
  detection-threshold: 0.9
  min-face-size: 80
  detection-size: 640
  max-image-pixels: 16000000
```

- 采集和识别必须检测到恰好一张足够大的人脸，经过五点对齐、提取、L2 归一化。
- 1:N 仅返回超过阈值、且与第二名分差足够大的唯一最高候选；分差不足则拒识，第二名低于阈值也参与检查。
- `matchScore` 只能提高阈值；NaN、无穷和越界数值报参数错误。
- 初始 0.55 阈值与 0.08 分差需用现场同人/异人照片标定，不保证零误识别。SFace 分数不可沿用旧引擎阈值解释。
- SFace / AuraFace 均不提供活体能力；活体接口返回 `supported=false`。质量接口仅检查人脸数量。

2000 × 512 维 float 特征本身约 4 MB，照片、对象及并发上传另占内存。250 并发不代表 250 QPS。建议图片长边 640～1280、JPEG 数百 KB，并按目标硬件压测调整 workers。可执行 `mvn test` 运行自动化测试；现场识别率和吞吐需在部署硬件上独立评测。

## 测试与压测

常规测试：`mvn test`。真实模型与可选负载测试需要预先将 OpenCV 公开样例 `lena.jpg` 放到 `target/test-images/lena.jpg`：

```powershell
mvn '-Dface.integration=true' '-Dface.load=true' test
```

生成 `target/sqlite-load-report.json`，其 `databasePath` 指向新建的 **SQLite 测试库**（1 条公开样例特征 + 1999 条随机干扰特征）。该测试库不可当生产人员库使用。

```powershell
$load = Get-Content target/sqlite-load-report.json -Raw | ConvertFrom-Json
java -Xms512m -Xmx2g -jar target/offline-face-api-1.0.0.jar --server.port=18090 "--face.storage-path=$($load.databasePath)"
# 另一终端：
java scripts/FaceHttpLoad.java http://127.0.0.1:18090/api target/test-images/lena.jpg 250 250 P0
```

## 接口

| 方法 | 路径 | 参数 |
| --- | --- | --- |
| POST | `/api/face/v2/faceCollection` | `img`、`role`、`code`，可选 `name` |
| POST | `/api/face/v2/faceVerification` | `img`，可选 `role`、`matchScore`、`verifyMinSide` |
| POST | `/api/face/v2/faceVerificationOne` | `img1`、`img2`，可选 `matchScore` |
| POST | `/api/face/v2/faceDetectionAndDraw` | `img`；返回人脸框 |
| POST | `/api/face/v2/faceDetectLiveliness` | `img` |
| POST | `/api/face/v2/faceDetectQuality` | `img` |
| POST | `/api/face/v2/faceRemove` | `role`、`code` |
| POST | `/api/face/v2/faceListByRole` | 可选 `role` |
| GET | `/api/face/v2/status` | 引擎、可用状态、数据库人员数量 |

## 从未匹配抓拍录入档案

视频识别结果和USB/RTSP实时抓拍中的“未匹配”卡片提供“录入档案”。点击后保留当时的抓拍，预览并填写姓名、角色、人员编号，保存到人脸档案。实时取流继续运行，录入照片不会随新画面更换；悬停/键盘聚焦实时抓拍列表或打开录入窗口时暂缓列表刷新，识别画面继续更新。

录入图由原始检测帧裁剪，四周保留30%空间，最长边不超过512像素，区别于112像素展示缩略图；只为UNKNOWN生成，过小人脸、确认中或复核中的人脸没有入口。服务端仍重新检测单人、校验尺寸、提取当前模型特征。模糊、姿态或裁剪中出现多人可能导致录入失败，需换一张清晰抓拍。

新增 POST /api/admin/faces/from-capture，multipart img/role/code/name，需 X-Face-Admin 请求头。它只创建新档案；同角色编号已存在时返回400，保留旧主图和参考照片。需要补充已有人脸时使用档案详情的参考照片功能。保存后当前实时会话后续帧立即检索新档案，无需重启会话；角色过滤仍有效。已处理的视频和历史保留原识别结果，需要重新运行视频才能按新档案识别。

较清晰录入抓拍会计入现有视频结果256MB限额；临时视频任务过期后不再提供其抓拍入口。已提交的档案原图与特征持久保存。
