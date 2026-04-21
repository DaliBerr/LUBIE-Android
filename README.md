# LUBIE

## 当前状态
- 当前是一个 Android 原生 Kotlin MVP，用于验证“近眼 IR 单目输入”的 2D 检测链路与 ONNX segmentation 推理链路。
- 当前页面使用 XML Layout，支持 `Live / Record / Replay / Reset` 工作流，以及 segmentation 的 `Model / Backend / Set ROI / Clear ROI / 2D On-Off` 调试控件。
- 当前仍保留经典 `pupil-detectors` 风格的 2D 检测实现，同时已接入 ONNX Runtime Android，用于验证 `unet_b16_384x240_int8_qdq.onnx` 与 `unet_b8_384x240_int8_qdq.onnx` 的真机分割推理。
- 当前 segmentation 路径使用手动框选的 eye ROI，不与 gaze、椭圆拟合或几何估计耦合。
- 当前支持关闭 2D 检测，进入 segmentation-only 验证模式，便于单独评估量化模型时延与可视化结果。
- 当前调试文本会显式显示 segmentation 的当前输出 FPS 与平均输出 FPS，便于评估模型在真机上的实际产出速率。

## 运行方式
- 在 Android Studio 打开工程并执行 Gradle Sync。
- 运行 `app` 模块后，首页会初始化 OpenCV 并请求相机权限。
- 授权后默认进入 `Live` 模式，优先使用前置相机；如设备没有前置相机，会回退到后置相机。
- 当前 segmentation 调试按钮包括：
  - `Model`
  - `Backend`
  - `Set ROI`
  - `Clear ROI`
  - `2D On / 2D Off`
- `Model` 按钮会在当前已打包的模型之间热切换，并在切换后懒加载对应 ORT session。
- 当前 segmentation 调试流程为：
  - 进入 `Live` 或 `Replay`
  - 如需纯模型验证，可先点击 `2D: On/Off` 关闭 2D
  - 点击 `Set ROI`
  - 在预览层拖拽框选 eye ROI
- 观察主预览 overlay、右上角两个小窗、session 状态和 timing 文本
- 观察结果区与调试区中的 `fps(now)` / `fps(avg)`，用于判断模型输出帧率
- 右上角两个 segmentation 小窗当前分别显示：
  - `ROI Gray`：送入模型前的灰度 ROI，已 resize 到 `384x240`
  - `Seg Overlay`：灰度 ROI 与 argmax 分割结果叠加后的预览图
- 页面顶部调试按钮当前包括：
  - `Live`
  - `Record`
  - `Replay`
  - `Reset`
  - `Play / Pause / Prev / Next / 0.25x / 1x`
  - `Show Debug / Hide Debug`

## 数据流
- `CameraXEyeFrameSource` 或 `RecordedEyeFrameSource` 输出 `EyeFrame(frameId, timestampNs, width, height, grayMat, sourceTag)`。
- `EyeTrackingController` 使用单线程串行执行：
  - 取帧
  - ROI 状态机
  - 2D pupil detection
  - 手动 segmentation ROI 推理
  - 质量分类
  - 录制写盘
  - 回放调度
  - UI 回调
- 当 `2D` 被关闭时，主链路会跳过 `Pupil2DDetector`，仅保留 segmentation 路径与录制/回放/UI 更新。
- 2D 输出当前统一收敛为 `PupilObservation2D`：
  - `frameId`
  - `captureTimestampNs`
  - `eyeId`
  - `frameWidth / frameHeight`
  - `pupil: PupilDatum2D?`
  - `quality: PupilQualityState`
  - `debug: Pupil2DDebugInfo`
- segmentation 输入契约当前固定为：
  - 输入名：`input`
  - 输入形状：`[1, 1, 240, 384]`
  - 输入类型：`float32`
  - 预处理：手动 ROI 灰度图、双线性 resize 到 `384x240`、除以 `255f`
- segmentation 输出契约当前固定为：
  - 输出名：`logits`
  - 输出形状：`[1, 4, 240, 384]`
  - 后处理：`argmax`
  - 关键类别：`iris=2`、`pupil=3`
- segmentation 可视化当前包括三种形式：
  - 主预览上的 ROI 透明 overlay
  - `ROI Gray` 小窗
  - `Seg Overlay` 小窗

## 核心模块
- `app/src/main/java/Aquin/lubie/MainActivity.kt`
  - 负责 OpenCV 初始化、权限申请、按钮事件、手动 ROI 交互与调试页展示。
- `app/src/main/java/Aquin/lubie/tracking/pipeline/EyeTrackingController.kt`
  - 负责 live/record/replay 工作流，以及 2D 与 segmentation 的串行处理链路。
- `app/src/main/java/Aquin/lubie/tracking/pipeline/Pupil2DDetector.kt`
  - 执行经典 2D detector 风格的 coarse ROI、轮廓筛选、support pixel refit 和 confidence 计算。
- `app/src/main/java/Aquin/lubie/tracking/segmentation/EyeSegmentationRunner.kt`
  - 负责 ORT session 懒加载、模型预处理、ONNX 推理、argmax、主预览 overlay 与小窗位图生成。
- `app/src/main/java/Aquin/lubie/tracking/segmentation/ModelAssetRepository.kt`
  - 负责将 `assets/models/` 下的 ONNX 模型复制到 `files/models/` 后供 ORT 加载。
- `app/src/main/java/Aquin/lubie/ui/DetectionOverlayView.kt`
  - 绘制 pupil ROI、手动 segmentation ROI、segmentation overlay、中心点和调试文本。

## 模型资源
- 当前 app 默认打包的模型资源位于 `app/src/main/assets/models/`。
- 当前已打包：
  - `unet_b16_384x240_int8_qdq.onnx`
  - `unet_b8_384x240_int8_qdq.onnx`
- 当前实现支持可选的 FP32 对照模型：
  - `unet_b16_384x240_fp32.onnx`
  - `unet_b16_384x240_fp32.onnx.data`
- 如果某个模型资源未打包，调试页的 `Model` 按钮会自动跳过该模型，只在当前可用模型之间切换。

## 录制与回放
- 录制文件写入 app 私有目录 `files/eye_sessions/<sessionId>/`。
- 当前每个 session 目录包含：
  - `session.json`
  - `metadata.jsonl`
  - `frames/000001.png ...`
- replay 读取最近一次 session，使用原始 `timestampNs` 驱动播放节奏，并复用同一条处理链重新输出当前调试结果。
- segmentation 在 replay 模式下也可用，适合固定同一段输入做 `FP32/INT8` 与 `CPU/NNAPI` 对照验证。

## 测试
- 本地单元测试当前覆盖：
  - ROI clamp、归一化、split、confidence 数学逻辑
  - ROI 状态机从 `INIT -> FOLLOW -> RECOVERY`
  - `PupilQualityState` 分类规则
  - `PupilObservation2D` 字段契约
  - segmentation timing 平均值逻辑
  - session/metadata 文本编解码
- 当前已验证以下命令可通过：
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:assembleDebug`

## 已知边界
- 当前只实现 2D 子系统，3D detector 仍是接口占位，没有实现 gaze mapping 或 fixation detection。
- 当前 segmentation 首轮只做 `argmax + overlay`，还没有接入最大连通域、3x3 close/open、椭圆拟合等桌面版后处理。
- 当前 live 输入仍是手机摄像头模拟，不等同于真实近眼 IR 相机效果。
- 当前 replay 只读取最近一次 session，不提供 session 列表页。
- 当前没有实现网络视频源、FPV/world camera、双相机对时、3D 标定或 gaze 叠加。
