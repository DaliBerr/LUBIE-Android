# LUBIE

## 当前状态
- 当前是一个 Android 原生 Kotlin MVP，主流程围绕远端树莓派提供的 `RTSP 视频 + UDP gaze JSON` 展开。
- 当前首页使用 XML Layout，主界面支持：
  - 连接 RTSP
  - 自动开始本地录制
  - 添加 marker
  - 回放最近一次 session
  - 断开连接
- 当前应用已锁定竖屏显示，主视频区域按 `16:9` 展示，并使用完整显示模式避免裁切画面内容。
- 当前支持一个隐藏演示模式：连续点击顶部状态文本 `5` 次会拉起系统视频选择器，导入本地视频作为演示源。
- 当前演示模式不接 UDP，不再额外绘制 gaze dot；适用于视频里已经带好 overlay 的演示素材。
- 当前仓库中仍保留旧的本地眼部识别、OpenCV、CameraX、2D detector、segmentation 代码与文件，但主页面不再提供这些功能入口。

## 运行方式
- 在 Android Studio 打开工程并执行 Gradle Sync。
- 运行 `app` 模块后，首页默认停留在远端视频页，不请求相机权限。
- 当前页面固定为竖屏。
- 在 `RTSP URL` 输入框中填写树莓派 RTSP 地址，在 `UDP Port` 输入框中填写 gaze UDP 端口。
- 点击 `Connect` 后：
  - 页面使用 Media3 `PlayerView` 播放 RTSP 视频
  - UDP 开始接收 gaze JSON
  - 收到首帧后自动开始本地录制
- 点击 `Add Marker` 可在当前录制 session 中写入一个 marker。
- 点击 `Replay` 会读取最近一次录制好的 session，并进入本地回放模式。
- 隐藏演示模式流程：
  - 连续点击顶部状态文本 `5` 次
  - 选择一个本地 `video/*` 文件
  - 页面切换到演示源播放，并同样自动录制、支持 marker 与回放

## 数据流
- Live 模式当前统一由 `RemoteTrackingController` 管理：
  - RTSP 或本地演示视频播放
  - UDP gaze 监听
  - `TextureView` 以固定 `30 fps` 抓帧
  - session 写盘
  - marker 写盘
  - replay 调度
  - UI render state 回调
- RTSP 模式下，UDP payload 当前按以下结构解析：
  - `screen_uv`
  - `tracking_valid`
  - `fps`
  - `inference_ms`
  - `calibration_state`
  - `status_message`
  - 以及其余同步 / 标定 / feature 相关字段
- 当前仅当：
  - `tracking_valid == true`
  - 且 `screen_uv` 非空
  时，才会在视频 overlay 上绘制 gaze dot。
- 演示模式下，gaze 始终为空，overlay 只显示状态文本，不绘制注视点。

## 录制与回放
- 当前录制统一写入 app 私有目录 `files/remote_sessions/<sessionId>/`。
- 当前每个 session 目录包含：
  - `session.json`
  - `metadata.jsonl`
  - `markers.jsonl`
  - `frames/000000.jpg ...`
- `session.json` 当前记录：
  - `sourceKind`
  - `sourceLabel`
  - `frameWidth / frameHeight`
  - `rtspUrl`
  - `demoSourceDisplayName`
  - `udpPort`
- `metadata.jsonl` 当前每帧记录：
  - `frameId`
  - `timestampNs`
  - `fileName`
  - `width / height`
  - `sourceKind`
  - `gazeSample`
- `markers.jsonl` 当前每行记录：
  - `timestampNs`
  - `frameId`
- 当前 replay 只读取最近一次 session。
- 当前 replay 支持：
  - `Play / Pause`
  - `Prev / Next`
  - `0.25x / 1x`
  - 拖动进度条 seek
  - marker 高亮时间轴
- RTSP session 回放时会复用录制时保存的 gaze snapshot 重新绘制 overlay。
- 演示模式 session 回放时不绘制 gaze，仅显示视频帧与 marker 时间轴。

## 核心模块
- `app/src/main/java/Aquin/lubie/MainActivity.kt`
  - 负责 RTSP 输入、隐藏演示模式入口、主按钮事件、回放拖动与 UI 文本渲染。
- `app/src/main/java/Aquin/lubie/remote/RemoteTrackingController.kt`
  - 负责 live / replay 状态切换、Media3 播放、UDP gaze、30fps 录制、marker 和 replay 调度。
- `app/src/main/java/Aquin/lubie/remote/UdpGazeReceiver.kt`
  - 负责 UDP socket 接收与 gaze JSON 解析回调。
- `app/src/main/java/Aquin/lubie/remote/RemoteSessionStore.kt`
  - 负责 remote session 的目录创建、metadata / marker 编解码与帧序列写盘。
- `app/src/main/java/Aquin/lubie/remote/RemoteReplaySource.kt`
  - 负责读取最近一次 remote session 并按时间戳进行回放步进与 seek。
- `app/src/main/java/Aquin/lubie/ui/DetectionOverlayView.kt`
  - 负责在 live / replay 预览上绘制 gaze dot 和状态文本。
- `app/src/main/java/Aquin/lubie/ui/MarkerTimelineView.kt`
  - 负责在回放进度条上绘制 marker 位置与当前高亮 marker。

## 依赖与权限
- 当前主流程额外使用：
  - `androidx.media3:media3-exoplayer`
  - `androidx.media3:media3-exoplayer-rtsp`
  - `androidx.media3:media3-ui`
- 当前 `AndroidManifest.xml` 中主流程使用的权限为：
  - `android.permission.INTERNET`
- 当前不再申请相机权限。

## 测试
- 本地单元测试当前覆盖：
  - UDP gaze JSON 解析
  - `screen_uv=null` 的降级行为
  - remote session / metadata / marker JSON 编解码
  - replay 30fps 时间基准
  - replay seek 定位逻辑
  - session duration 计算
- 当前已验证以下命令可通过：
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest`
  - `./gradlew.bat :app:assembleDebug`

## 已知边界
- 当前 replay 仍只读取最近一次 session，不提供 session 列表页。
- 当前“保存视频”仍按 MVP 路径落为本地 `30 fps` JPEG 帧序列，而不是 MP4。
- 当前录制抓帧来源是 `TextureView`，若设备性能不足会主动跳帧写盘，以优先保证预览与主线程流畅。
- 当前 RTSP 与演示模式共用同一套录制 / replay 结构，但演示模式不会接收 UDP。
- 当前旧的本地眼部识别链路代码仍保留在仓库中，后续如需彻底清理依赖与入口，需要单独做一次收敛。
