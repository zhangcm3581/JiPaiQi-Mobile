# 客户端识别与同步结构 · 0.4.11

当前十三水业务流程：`MediaProjection → ImageReader最新截图 → Vision状态/手牌识别 → RoundSession轮次确认 → RoundSocket协议上传 → HandOverlay服务器结果`。旧 `Ledger` 与 HTTP 上传草案保留兼容代码，不能作为当前十三水业务协议或显隐规则依据。

`CaptureService` 使用一个识别 HandlerThread。图片回调仅在取到新 Image 时替换并释放旧缓存，采样 tick 取出最新一帧，分析后关闭；目标间隔200ms，超时不积压。没有新缓冲不能重用旧画面增加确认次数，超过1.5秒进入采集中断。悬浮控制栏指示点帮助静态模拟器产生新合成帧，但不绕过无效画面检查。

模板/ROI 同步执行素材要求的底色统一，然后匹配；状态按钮保留原色。识别只取自己的手牌区域。宽高比偏差超过3.5%拒绝拉伸，小幅差异使用等比有效区域。

`RoundSession` 的所有修改在识别线程串行进行。分别维护本机面板阶段、已知服务器版本、固定视觉开局目标、实际绑定版本及持久化待确认请求。服务器快照不得伪造本机结束或重标旧牌；网络迟到可以等待原确认链，真实版本跨度则停止同步。完整规则见 [round-sync.md](round-sync.md)。

`RoundSocket` 回调投递到同一工作线程，按 WebSocket 实例过滤过期回调。请求每3秒原样重试，15秒 ping，45秒无响应失效，2秒重连；注册失败和重复客户端ID停止重复连接。停止状态先在worker确定，再取消连接，避免关闭回调重启网络。

面板数据、显隐与说明在工作线程一起读取后投递主线程。复用一个系统窗口，以 VISIBLE/GONE 切换；结果数据不会决定是否打开面板。结果窗口固定左上角 `(2,2)`，控制栏独立可拖动。识别区与固定面板冲突时隐藏保护，不自动迁移。

每次采集由 Activity 发起系统授权，启动 mediaProjection 类型前台服务。VirtualDisplay 每个授权会话只创建一次，尺寸变化修改 Surface；停止释放 ImageReader、OpenCV Mat、VirtualDisplay、悬浮窗及工作线程。返回大厅不会走停止路径。

`MainActivity` 是透明授权/设置入口，没有常驻主页，授权完成就返回原应用。已运行时启动入口只展开原控制栏。设置只持久保存租户和客户端 ID；`BuildConfig.SERVER_URL` 在构建时固定地址，旧地址偏好不参与连接。更改身份在原工作线程串行关闭旧 socket、清除当前会话引用并建立新身份连接，保持 MediaProjection 与悬浮窗；自动识别暂停，用户再次点击启动。相同 ID 保存保持原连接和运行状态。设置打开期间停止视觉连续确认并隐藏结果窗，控制栏仍保留，关闭后由真实画面重新决定结果显隐。

配置只读取 ZIP 的 manifest.json。拒绝未知必需算法、缺失区域、损坏图片、不合理牌库与不安全 ZIP 路径。当前素材及预处理要求见 [client-v2-implementation.md](client-v2-implementation.md)。

`CaptureService` 调用 `HandOverlay.showSettings`。控制条的 `ControlWindow` 在设置开合期间保持原生窗口坐标、高度和焦点标志不变；`IdentitySettingsView` 由紧贴控制条的 `SettingsWindow` 承载，仅这个窗口接收输入焦点。两个窗口共用 `JoinedWindow` 绘制，统一 240 dp 宽度、背景、圆角及边缘，没有间距。控制条高 48 dp，设置约 160 dp；上方空间不足则向下展开。拖动与旋转时同步定位两个部分，保持尚未保存的输入。

0.4.10 曾直接改变同一窗口的原生高度与 Y 坐标。Android 会对已经显示的 Surface 额外执行位移动画，即便应用内部布局结束后的坐标相同，合成画面仍出现约一个设置区高度的跳动。0.4.11 固定控制条原生 Surface，设置仅在自己的固定范围内执行裁剪、位移、透明度动画（200/160ms），不让控制条参与开合布局变化。不得只用动画结束后的布局坐标验证无抖动，需运行 `scripts/verification/check_settings_motion.py` 检查屏幕合成帧。

取消、返回键、再次点设置和收起走相同关闭路径；服务停止取消动画并移除窗口。无全屏遮罩；初始焦点在设置容器，输入框禁止横屏全屏编辑。`IdentitySettingsDialog` 仅用于采集尚未启动时的旧设置 intent，常用入口与其共用表单。
