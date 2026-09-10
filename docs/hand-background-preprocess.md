> 2026-09-09 更新：0.4.0已实现下述十三水协议、预处理与显隐规则。当前行为及边界以 [client-v2-implementation.md](client-v2-implementation.md) 为准；原文“待实现”是此前记录。

# 手牌底色统一：客户端必须实现

状态：2026-09-09 已接入 jpq-tools；Android 客户端待实现。不能只复制52张PNG和阈值而遗漏此步骤。

## 适用范围与顺序

仅 category=牌面、semantic.group=me、semantic.role=hand 的模板。搜索ROI与模板必须执行相同变换：坐标/尺寸适配 → 裁出搜索区及读取模板 → 底色统一 → 用户选定的彩色/灰度/Otsu预处理 → TM_CCOEFF_NORMED匹配 → 候选去重。开局、结束、其他图片不执行此变换。

使用8位RGB通道定义（不是HSV）：

    R_out = R_in
    G_out = G_in
    B_out = max(B_in, min(G_in, R_in))

OpenCV图像为BGR，蓝通道索引0；Android ARGB需按颜色通道取值，不能直接套数组索引。使用原通道值计算，保持alpha，不改变源图。必须在灰度或二值化之前执行，不能只处理模板或只处理截图。理想白、黑、红保持不变，黄色变为白色；实际压缩/抗锯齿像素也按同一公式处理。

## 配置契约

能力标识：`cards.background.yellow_to_white.v1`。

- 项目 `adaptation.hand_preprocess` 保存该标识；旧手牌项目加载/准备时补上默认值。
- 导出的 manifest `hand_preprocess` 与 `requires` 包含该标识。
- 每个我方手牌 detector 的 `params.background_normalization` 包含该标识；`params.preprocess` 仍为 color/gray/otsu，表示随后执行的处理。
- PNG保留原图，不预先永久改色。客户端缓存可存处理后模板，但必须以能力版本及后续模式作为缓存键。
- 客户端不支持 `requires` 中的能力时必须拒绝启用该包并提示升级，禁止静默按原始彩色匹配。未知版本也不能默认为已支持。
- 当前工具手牌路径固定启用v1；单模板测试、预览、整手测试、视频验证均已接入。状态检测保持原逻辑。
- 服务器WebSocket牌数据协议无须改动：这是识别素材/客户端能力契约，不增加hand.submit字段。

## 验收不能省略

1. 同一帧白底模板识别黄色红桃K：旧分约0.819，新分约0.964，手牌阈值0.85。
2. 白底、黄色黑桃8、红色花色、重复牌、空画面均检查；不能只核对13张数量。
3. 工具与客户端对同一尺寸ROI、同一模板、同一模式应得到相近分数及相同身份；浮点容差需按Android OpenCV实现确定。
4. 开局/结束仍用彩色0.9，不被底色处理影响。
5. 导出包检查requires及detector参数；旧客户端拒绝不支持的包。

全视频试验：873采样点，8段初始手牌均有连续相同13张结果；没有新增空画面非空结果或超过13张结果。此结果不是全牌型准确率证明。真实黄色K夹具在jpq-tools/tests/fixtures，回归测试为tests/test_hand_background.py。


后续必须同时执行 [数字与花色独立验证](hand-corner-matching-v1.md)。底色统一解决黄底差异，不能单独保证小花色正确；最新素材包要求 `cards.hand_rank_suit.v1`。
