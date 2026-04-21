# Pupil Core 眼球识别算法 Android/Kotlin 重写指南

## 1. 目的

本文档用于指导后续 agent 将当前仓库中的“眼球识别核心”剥离并重写到 Kotlin/Android 平台。

这里的“核心”按优先级分为两层：

1. 2D 瞳孔检测
   输入单帧灰度眼图，输出瞳孔椭圆、中心、直径、置信度。
2. 3D 眼球模型估计
   以上述 2D 椭圆为输入，输出眼球球心、3D 瞳孔圆、法向量、3D 直径和修正后的 2D 投影。

如果你们的目标是先在 Android 上得到稳定的 pupil center / diameter，建议先完成 2D 部分。
如果目标是后续继续兼容 Pupil 的 3D gaze mapping，再补 3D 部分。


## 2. 先说结论

当前仓库里并不包含 2D/3D 检测的全部底层实现。

本仓库真正保留的是：

- 进程/插件调度逻辑
- 眼图输入输出数据结构
- 2D/3D 检测器的 Python 包装层
- 3D gaze mapping 和 calibration 的上层几何逻辑

真正的底层检测核心来自两个官方外部包：

- `pupil-detectors`
  2D pupil detector 的 C++/Cython 实现
- `pye3d`
  3D eye model detector 的 Python 实现

因此，Android 重写时要区分：

1. 必须从本仓库复刻的“调度和数据契约”
2. 必须从官方外部仓库复刻的“算法主体”


## 3. 本仓库中的实际调用链

### 3.1 实时链路

默认 `capture` 模式下：

1. `pupil_src/main.py`
   启动 `world` 主进程和 `eye0/eye1` 眼睛进程。
2. `pupil_src/launchables/eye.py`
   每个 eye 进程读取眼图、加载 ROI 插件、加载 pupil detector 插件。
3. `pupil_src/shared_modules/pupil_detector_plugins/detector_2d_plugin.py`
   调用 `pupil_detectors.Detector2D.detect(...)`。
4. `pupil_src/shared_modules/pupil_detector_plugins/pye3d_plugin.py`
   读取上一阶段 2D 结果，调用 `pye3d.detector_3d.Detector3D.update_and_detect(...)`。
5. eye 进程向 IPC 发出 `pupil.<eye_id>.*` 消息。
6. `world` 或 `service` 进程拿到 pupil 数据后，才能做 gaze mapping。

### 3.2 插件执行顺序

eye 进程里默认顺序是：

1. 视频源插件
2. `Roi`
3. `Detector2DPlugin`
4. `Pye3DPlugin`

这点很关键，因为：

- 3D 检测依赖 2D 检测输出
- ROI 必须在 2D 检测前生效


## 4. Kotlin 侧建议保留的统一数据契约

无论内部算法怎么改，建议 Android 侧先保留与 Pupil Core 接近的数据结构。

### 4.1 2D 输出结构

```kotlin
data class PupilEllipse2D(
    val centerX: Double,
    val centerY: Double,
    val axisMinor: Double,
    val axisMajor: Double,
    val angleDeg: Double
)

data class PupilDatum2D(
    val eyeId: Int,
    val timestamp: Double,
    val method: String,          // 例如 "2d c++"
    val normPosX: Double,        // x / width
    val normPosY: Double,        // 1 - y / height
    val diameterPx: Double,
    val confidence: Double,
    val ellipse: PupilEllipse2D
)
```

### 4.2 3D 输出结构

```kotlin
data class Vec3(val x: Double, val y: Double, val z: Double)

data class Circle3D(
    val center: Vec3,
    val normal: Vec3,
    val radius: Double
)

data class Sphere3D(
    val center: Vec3,
    val radius: Double
)

data class PupilDatum3D(
    val eyeId: Int,
    val timestamp: Double,
    val method: String,          // 例如 "pye3d 0.x real-time"
    val normPosX: Double,
    val normPosY: Double,
    val diameterPx: Double,
    val confidence: Double,
    val modelConfidence: Double,
    val theta: Double,
    val phi: Double,
    val ellipse: PupilEllipse2D,
    val projectedSphere: PupilEllipse2D?,
    val sphere: Sphere3D,
    val circle3D: Circle3D,
    val diameter3dMm: Double
)
```

### 4.3 坐标系约定

本仓库里几个坐标系要分清：

- 眼图像素坐标
  `x in [0, width)`, `y in [0, height)`
- `norm_pos`
  `x = centerX / width`
  `y = 1 - centerY / height`
- 3D 眼相机坐标
  由 `pye3d` 建模使用
- 世界相机坐标
  gaze mapping/calibration 使用

注意：

- Pupil 的 `norm_pos` 是 `flip_y=True` 的，也就是 `y` 轴翻转后归一化
- 如果 Android 侧后续还要对接上游 gaze mapper，必须保持这个约定


## 5. 2D 检测器真实算法链

## 5.1 仓库内可见部分

本仓库 Python 包装层在：

- `pupil_src/shared_modules/pupil_detector_plugins/detector_2d_plugin.py`
- `pupil_src/shared_modules/pupil_detector_plugins/detector_base_plugin.py`

包装层实际做的事非常少：

1. 从当前 ROI 取边界
2. 把灰度眼图 `frame.gray` 交给 `Detector2D.detect(...)`
3. 把返回结果转换为 Pupil 的统一字典结构

也就是说，Android 重写的重点不在这两个 Python 文件，而在 `pupil-detectors` 里的 2D 底层实现。

## 5.2 2D 检测器的底层处理流程

根据官方 `pupil-detectors` 中的 `detect_2d.hpp`，2D 算法主流程可以概括为：

1. 在 ROI 内处理
2. 生成暗瞳候选区域
3. 做边缘提取
4. 从边缘生成轮廓
5. 过滤短轮廓
6. 按曲率或方向变化把粗轮廓切成更稳定的片段
7. 根据几何指标选择 seed contours
8. 以 seed 为中心组合多个 contour 片段
9. 对组合后的点集进行椭圆拟合
10. 用支持像素重新评分和重拟合
11. 输出最终椭圆与置信度

### 5.2.1 ROI

Pupil 不是全图检测，而是先由 ROI 限定范围。

本仓库 ROI 插件在：

- `pupil_src/shared_modules/roi.py`

ROI 特点：

- 初始默认是整帧
- 支持拖拽修改
- 改分辨率时按比例缩放
- 下游检测器直接读取 `bounds = (minX, minY, maxX, maxY)`

Android 侧建议：

- 检测器内部只处理 ROI 子图
- 输出椭圆中心时要再加回 ROI 偏移

### 5.2.2 粗定位和暗瞳分割

从 `detect_2d.hpp` 可知，2D 检测不是直接在整张边缘图上找椭圆，而是先建立 pupil 候选区域。

从现有参数和代码痕迹可以确认至少包含这些控制量：

- `intensity_range`
- `pupil_size_min`
- `pupil_size_max`
- `canny_treshold`
- `contour_size_min`
- `initial_ellipse_fit_treshhold`
- `strong_perimeter_ratio_range_min/max`
- `strong_area_ratio_range_min/max`
- `support_pixel_ratio_exponent`

其中 UI 中直接暴露出来的是：

- `intensity_range`
- `pupil_size_min`
- `pupil_size_max`
- `canny_treshold`

推荐 Android 侧按下面的等价结构实现：

1. 灰度眼图输入
2. 按 ROI 裁切
3. 基于暗瞳先验做阈值分割，得到 dark mask
4. 在 dark mask 上做 specular reflection 抑制
5. 结合 Canny 边缘得到 edge map

虽然本仓库没把 `pupil-detectors` 的全部辅助文件 vendoring 进来，但从 Pupil 的调参与下游几何判断能看出：

- `intensity_range` 决定暗瞳强度带宽
- `pupil_size_min/max` 用于约束候选椭圆尺度
- `canny_treshold` 控制边缘敏感度

### 5.2.3 轮廓与分裂

在 `detect_2d.hpp` 中，边缘图会进入：

- `cv::findContours`
- 过滤 `contour.size() > props.contour_size_min`
- 对粗轮廓做 split

split 的目的不是纯粹为了细化，而是为了处理以下情况：

- 上下眼睑遮挡
- 反光导致轮廓不闭合
- 一个粗 contour 内同时混入多个几何段

仓库中旧工具函数 `methods.py` 也保留了类似的辅助思路：

- `GetAnglesPolyline`
- `find_kink_and_dir_change`
- `split_at_corner_index`
- `pruning_quick_combine`

虽然 2D 核心实现不在本仓库，但可以确定其思想就是：

- 先拆 contour
- 再以组合搜索恢复完整 pupil 边界

### 5.2.4 Seed contour 选择

`detect_2d.hpp` 中会先选一批 seed contours：

- strong contours
- weak contours

如果 strong 为空，再退回 weak。

其筛选依据直接体现在参数名上：

- perimeter ratio range
- area ratio range

这说明每个 contour 片段会先做一次几何合理性估计，只有接近“椭圆弧”的片段才会进入后续组合。

Android 侧建议复刻为：

1. 对每个 contour 片段做初步椭圆拟合
2. 计算 contour 长度 / 拟合椭圆周长比例
3. 计算 contour 面积 / 拟合椭圆面积比例
4. 按阈值分 strong / weak

### 5.2.5 片段组合搜索

`detect_2d.hpp` 中有一个重要阶段：

- `pruning_quick_combine(...)`

它的含义不是暴力枚举所有 contour 组合，而是：

1. 从 seed 开始
2. 逐步向后添加更多 contour 片段
3. 对每个组合拟合椭圆
4. 如果当前组合拟合质量已经不可能成立，就剪枝
5. 只保留满足初始椭圆质量阈值的组合

这里的关键阈值是：

- `initial_ellipse_fit_treshhold`

文档层面的理解是：

- 该算法不是“找一个轮廓拟合椭圆”
- 而是“找一组局部轮廓片段，其组合后最像 pupil 椭圆”

这一步非常适合 Kotlin/NDK 用 C++ 重写，因为：

- 需要大量小规模几何计算
- 需要控制内存分配和剪枝效率

### 5.2.6 最终重拟合和支持像素

选出最佳 contour 组合后，算法不会直接返回，而是还有两步：

1. 对最佳 contour 组合拼接成一个点集
2. 重新提取 support pixels
3. 对 support pixels 再 `fitEllipse`

从 `detect_2d.hpp` 可以确认：

- 若最终边缘点少于 5 个，直接失败
- `cv::fitEllipse(final_edges)` 会再次拟合
- 若新椭圆相对旧椭圆尺寸差异太大，则不采用

这意味着 Android 侧不要只做“一次椭圆拟合”，否则鲁棒性会明显差于原实现。

### 5.2.7 2D 置信度公式

这是目前最值得原样保留的部分之一。

从 `detect_2d.hpp` 可以确认最终置信度近似为：

```text
support_ratio = support_pixels_count / ellipse_circumference

confidence =
    min(0.99, support_ratio) *
    pow(support_pixels_count / final_edges_count, support_pixel_ratio_exponent)
```

可得出结论：

- 置信度不是神经网络概率
- 它是一个基于几何支持度的评分
- 其核心看两件事：
  - 最终边缘点中有多少真正贴着拟合椭圆
  - 支持点覆盖了椭圆周长的多少比例

因此 Android 重写时，哪怕你们暂时替换部分候选生成逻辑，也建议保留这一套 confidence 计算方法。

### 5.2.8 2D 输出字段

Pupil Python 包装层最终依赖这些结果字段：

- `location`
- `diameter`
- `confidence`
- `ellipse.axes`
- `ellipse.angle`
- `ellipse.center`

其中：

- `location` 是像素坐标
- `ellipse.axes` 是椭圆长短轴长度，不是半径
- `ellipse.angle` 为 OpenCV 风格角度

Android 侧若要兼容上层逻辑，这几个字段必须保留。


## 6. 3D 检测器真实算法链

## 6.1 仓库内可见部分

本仓库 3D 包装层在：

- `pupil_src/shared_modules/pupil_detector_plugins/pye3d_plugin.py`

它的逻辑是：

1. 从 2D 检测结果里找出 `method == "2d c++"` 的 datum
2. 将该 datum 与灰度帧传给 `Detector3D.update_and_detect(...)`
3. 把返回结果转为统一 pupil datum

因此，Android 真正要重写的 3D 主体在 `pye3d` 外部包，而不是仓库里的 `pye3d_plugin.py`。

## 6.2 3D 输入依赖

从 `pye3d/detector_3d.py` 和本仓库包装层可以确定，3D 输入至少依赖：

1. 2D 椭圆
   - center
   - axes
   - angle
   - confidence
   - timestamp
2. 相机模型
   - focal length
   - resolution
3. 当前灰度帧
   - 用于 3D 阶段进一步 edge search

这说明：

- 3D 不是纯几何后处理
- 它还会重新回到图像里搜索和评分

## 6.3 Observation 构造

在 `pye3d` 中，2D 椭圆会先被包装成 `Observation`。

这个对象会做几件事：

1. 把 OpenCV 椭圆转换为内部几何对象
2. 以相机焦距为条件，将 2D 椭圆反投影为两个可能的 3D 圆
3. 为每个候选 3D 圆生成 gaze line
4. 预计算 2D/3D 优化辅助矩阵

重要含义：

- 单帧 2D 椭圆几何上通常对应两个可能的 3D 圆解
- pye3d 后续需要借助历史模型与边缘支持来决定更合理的解

## 6.4 3D 模型更新思路

`pye3d_plugin.py` 中有两个模式：

- `asynchronous`
  Capture / Service 实时模式用
- `blocking`
  Player 离线模式用

说明它本质上是“状态机 + 模型更新器”，而不是纯函数。

从 `detector_3d.py` 可以确认的大致流程是：

1. 从 2D datum 抽取 `Observation`
2. `update_models(observation)`
3. 从 long-term model 取当前眼球球心估计
4. 预测 pupil circle
5. 进行 refraction correction
6. 生成最终 result dict

这意味着 Kotlin 侧一定要把 3D detector 设计成“有内部状态的对象”，不能写成单帧无状态函数。

## 6.5 3D 结果字段

从 `detector_3d.py` 的结果准备逻辑可确认最终字段至少包括：

- `sphere.center`
- `sphere.radius`
- `projected_sphere`
- `circle_3d`
- `diameter_3d`
- `ellipse`
- `location`
- `diameter`
- `confidence`
- `model_confidence`
- `theta`
- `phi`

其中：

- `ellipse` 和 `location` 是 3D 结果重新投影回图像后的 2D 表达
- `circle_3d.normal` 是 gaze mapping 的关键输入
- `sphere.center` 是 3D gaze mapping 的关键输入

## 6.6 3D 搜索置信度

从 `detector_3d.py` 可见，3D 搜索阶段也会用边缘支持度打分：

```text
confidence_3d_search = clip(final_edges_count / projected_ellipse_circumference, 0, 1)
return confidence_3d_search * 0.6
```

这说明：

- 3D 阶段同样是基于几何支持评分
- 最终结果依旧与图像边缘贴合程度强相关

## 6.7 model_confidence 规则

`pye3d` 还引入了 `model_confidence`，其规则不是学习型得分，而是参数合理性检查。

从 `detector_3d.py` 可确认至少检查这些范围：

- 眼球中心 `x`
- 眼球中心 `y`
- 眼球中心 `z`
- 3D 瞳孔直径
- `phi/theta` 是否在生理合理范围内

结论：

- `confidence` 更偏向当前帧观测质量
- `model_confidence` 更偏向当前 3D 模型是否处在合理生理范围

Android 若只迁移 `confidence`，不迁移 `model_confidence`，上层稳定性会变差。


## 7. 与 gaze mapping 的耦合点

虽然你们当前任务是“眼球识别剥离”，但如果后面还要继续使用 Pupil 的 gaze mapping，就必须保留下面字段。

### 7.1 2D gaze mapping 依赖

`gaze_mapping/gazer_2d.py` 只依赖：

- `norm_pos`
- `confidence`
- `id`
- `timestamp`

### 7.2 3D gaze mapping 依赖

`gaze_mapping/gazer_3d/gazer_headset.py` 依赖：

- `id`
- `sphere.center`
- `circle_3d.normal`
- `confidence`
- `timestamp`

这意味着：

- 如果只想兼容 2D gaze mapping，保住 2D 输出即可
- 如果要兼容 3D gaze mapping，`sphere.center` 和 `circle_3d.normal` 必须保持语义一致


## 8. Android/Kotlin 实现建议

## 8.1 架构建议

推荐把 Android 端拆成四层：

1. FrameSource
   CameraX / MediaCodec / 离线视频输入
2. EyePreprocessor
   ROI、灰度、去畸变、镜像处理
3. Pupil2DDetector
   输出 2D pupil datum
4. Pupil3DDetector
   可选，输出 3D pupil datum

额外建议一个状态容器：

5. EyeTrackingSessionState
   保存上一帧 ROI、上一帧椭圆、3D model state、帧时间戳

## 8.2 线程模型建议

推荐：

- Camera 线程只负责入帧
- 单独检测线程串行处理某一只眼
- 不要多个线程同时改同一个 3D detector 状态

如果双眼都在 Android 上跑：

- `eye0` 一个 detector 实例
- `eye1` 一个 detector 实例
- 二者状态完全隔离

## 8.3 OpenCV 与 NDK 边界建议

推荐两条路线：

### 路线 A：快速落地

- Kotlin 管流程
- OpenCV Android SDK 处理图像与椭圆拟合
- JNI/NDK 只实现 contour split + pruning combine + support scoring

优点：

- 迁移快
- 容易调试

缺点：

- 若 3D 也完全重写，Kotlin 数值性能会吃紧

### 路线 B：高保真迁移

- Kotlin 管生命周期和接口
- 2D 核心完全 NDK C++
- 3D 核心可选 C++ 或 Kotlin + EJML/Apache Commons Math

优点：

- 更贴近现有 Pupil 性能
- 方便直接复刻 `pupil-detectors` 的思路

缺点：

- 开发成本更高

我的建议：

1. 先做路线 A
2. 把 2D 检测稳定跑起来
3. 再决定 3D 是否走纯 Kotlin 还是 NDK


## 9. 推荐的分阶段重写计划

## Phase 1：只做 2D 瞳孔检测

目标：

- 在 Android 上稳定输出 pupil center / ellipse / diameter / confidence

最低实现要求：

1. ROI 裁切
2. 灰度预处理
3. 暗瞳阈值候选
4. Canny 边缘
5. contour 提取与过滤
6. contour split
7. 候选组合
8. 最终椭圆拟合
9. support-based confidence

验收指标：

- 对同一段眼视频，中心点偏差可控
- 置信度排序和 Python 原版趋势一致
- 部分遮挡下仍能稳定出椭圆

## Phase 2：补 3D detector

目标：

- 输出 `sphere.center`、`circle_3d.normal`、`diameter_3d`

最低实现要求：

1. 从 2D 椭圆构造 observation
2. 建立有状态 detector
3. 长期模型 + 短期预测
4. refraction correction
5. model_confidence 规则

## Phase 3：必要时再接 calibration / gaze

若最终目标是完整 gaze pipeline，再对接：

- `gaze_mapping/gazer_2d.py`
- `gaze_mapping/gazer_3d/gazer_headset.py`
- `calibration_choreography`


## 10. Kotlin 伪代码骨架

```kotlin
class Pupil2DDetector(
    private val params: Pupil2DParams
) {
    fun detect(gray: Mat, roi: Rect, timestamp: Double, eyeId: Int): PupilDatum2D? {
        val roiGray = gray.submat(roi)

        val darkMask = buildDarkMask(roiGray, params)
        val edgeMap = buildEdgeMap(roiGray, darkMask, params)

        val contours = findContours(edgeMap)
            .filter { it.size >= params.contourSizeMin }

        val splitContours = contours.flatMap { splitContour(it) }
        val seeds = selectSeedContours(splitContours, params)

        val candidateSets = combineContoursWithPruning(splitContours, seeds, params)
        val best = scoreCandidatesAndPickBest(candidateSets, splitContours, edgeMap, params)
            ?: return null

        val finalEllipse = refitWithSupportPixels(best, edgeMap, params) ?: return null
        val confidence = computeConfidence(finalEllipse, best.supportPixels, best.finalEdges, params)

        val cx = finalEllipse.centerX + roi.x
        val cy = finalEllipse.centerY + roi.y

        return PupilDatum2D(
            eyeId = eyeId,
            timestamp = timestamp,
            method = "2d kotlin",
            normPosX = cx / gray.cols(),
            normPosY = 1.0 - cy / gray.rows(),
            diameterPx = finalEllipse.axisMajor,
            confidence = confidence,
            ellipse = finalEllipse.toGlobal(roi.x, roi.y)
        )
    }
}
```


## 11. 不建议做的简化

以下简化会明显偏离原始 Pupil 行为：

1. 只做阈值 + 最大连通域 + 一次 `fitEllipse`
2. 省略 contour split
3. 省略 contour 组合搜索
4. 省略 support pixel 二次重拟合
5. 用简单面积比例代替 confidence 公式
6. 把 3D detector 写成单帧无状态函数

这些做法短期能出结果，但和原实现差距会很大，尤其在：

- 眼睑遮挡
- 强反光
- pupil 边缘断裂
- 头戴设备轻微滑移


## 12. 测试与回归建议

## 12.1 离线基准集

建议先准备一组眼视频，覆盖：

- 正常光照
- 强反光
- 部分遮挡
- pupil 很小
- pupil 很大
- 快速眼跳

## 12.2 逐帧比对指标

Android 重写版与 Python 原版逐帧比对：

- center 像素误差
- major/minor axis 误差
- angle 误差
- confidence 排序相关性
- 3D normal 角度误差
- 3D sphere center 误差

## 12.3 性能指标

以单眼 192x192 为参考，建议目标：

- 2D 检测 < 8 ms / frame
- 3D 检测 < 8 ms / frame

如果双眼 + 60fps 以上实时运行，建议尽早把热点代码迁到 NDK。


## 13. 实现边界和缺口

这份文档基于三类源码整理：

### 13.1 本仓库中的本地源码

- `pupil_src/launchables/eye.py`
- `pupil_src/shared_modules/pupil_detector_plugins/detector_base_plugin.py`
- `pupil_src/shared_modules/pupil_detector_plugins/detector_2d_plugin.py`
- `pupil_src/shared_modules/pupil_detector_plugins/pye3d_plugin.py`
- `pupil_src/shared_modules/roi.py`
- `pupil_src/shared_modules/gaze_mapping/gazer_3d/gazer_headset.py`
- `pupil_src/shared_modules/gaze_mapping/gazer_3d/calibrate_3d.py`

### 13.2 官方外部源码

- `pupil-detectors`
  - `src/pupil_detectors/detector_2d/detect_2d.hpp`
- `pye3d-detector`
  - `pye3d/detector_3d.py`
  - `pye3d/observation.py`
  - `pye3d/constants.py`

### 13.3 当前仍需注意的缺口

当前仓库本身没有 vendor 进来这些外部包，所以：

- 2D detector 的全部辅助源码不在本仓库
- pye3d 的部分模型文件也不在本仓库

因此后续 agent 若要做高保真重写，应直接以官方仓库为准继续展开。


## 14. 建议给后续 agent 的明确任务拆分

建议后续 agent 按以下顺序推进：

1. 建立 Android 模块的统一数据结构
2. 先做 ROI + 预处理 + 2D detector 接口壳子
3. 复刻 2D contour split / seed / combine / support confidence
4. 用离线眼视频做逐帧回归
5. 再接 3D detector 状态机
6. 最后再考虑与 gaze calibration 的兼容

如果资源有限，建议先接受这个裁剪版本：

1. 只重写 2D detector
2. 输出和 Pupil 一致的 2D datum
3. 暂不迁移 3D 与 gaze mapping

这条路线最稳，也最容易先在 Android 上落地。


## 15. 参考链接

官方仓库与文档：

- Pupil 主仓库
  - https://github.com/pupil-labs/pupil
- pupil-detectors
  - https://github.com/pupil-labs/pupil-detectors
- pye3d-detector
  - https://github.com/pupil-labs/pye3d-detector
- pye3d 官方开发文档
  - https://docs.pupil-labs.com/core/developer/pye3d/

建议重点阅读的外部文件：

- https://github.com/pupil-labs/pupil-detectors/blob/master/src/pupil_detectors/detector_2d/detect_2d.hpp
- https://github.com/pupil-labs/pye3d-detector/blob/master/pye3d/detector_3d.py
- https://github.com/pupil-labs/pye3d-detector/blob/master/pye3d/observation.py

