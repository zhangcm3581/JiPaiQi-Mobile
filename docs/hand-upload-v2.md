# v2 十三张手牌与服务器结果（APK 0.3.0）

接口尚未提供，目前仅完成客户端及协议草案。HTTPS 上传默认关闭，不能把本地识别结果当成服务器结果显示。无地址时面板显示未配置服务器。

## 何时发送

必须导入我方手牌区域、联合点数＋小花色模板、新局特征和结束特征。新局连续确认后建立 round_id；在该局内，全部检测结果必须正好 13 张，每张点数和花色合法，且连续至少 2 个采样结果的数量组合相同。确认次数取 max(2,配置值)。牌序变化不影响确认。

支持点数 2 A K Q J 10 9 8 7 6 5 4 3；花色 spades、hearts、clubs、diamonds。本版针对参考图两副牌布局，同一花色点数最多两张。重复牌计入 13 张总数；12 张、14 张、未知花色、超过两张相同身份均不发送。没有新局特征命中也不发送。13 张是数量和身份格式校验，并不等于识别准确率 100%。

每局只产生一个请求；网络重试复用同一个 event_id，不能再次计作新手牌。暂停、遮挡、短暂消失只打断确认，不允许已发过的同一局重新生成请求。下一局可发送完全相同的 13 张牌。结束优先，面板清空并等待下一局。应用重新启动会创建新的 session_id，服务端按会话区分。

## 请求草案

POST 用户指定的 HTTPS 地址，拒绝 HTTP、URL 用户密码和 fragment，不跟随重定向。

- `Content-Type: application/json; charset=utf-8`
- `Idempotency-Key: <event_id>`
- 认证与账户绑定未实现，待接口文档确定后对接，不适用于需要鉴权的正式接口。

```json
{
  "schema": "jpq.hand-snapshot/2",
  "event_id": "request-uuid",
  "session_id": "capture-session-uuid",
  "round_id": 1,
  "sequence": 1,
  "captured_at_ms": 1788880000000,
  "package_id": "game-package-id",
  "region_id": "own-hand-region-id",
  "seat_id": "me",
  "expected_count": 13,
  "observed_count": 13,
  "cards": [
    {"suit":"clubs","rank":"A"},
    {"suit":"hearts","rank":"Q"},
    {"suit":"spades","rank":"10"},
    {"suit":"hearts","rank":"9"},
    {"suit":"clubs","rank":"9"},
    {"suit":"hearts","rank":"7"},
    {"suit":"clubs","rank":"7"},
    {"suit":"spades","rank":"6"},
    {"suit":"clubs","rank":"5"},
    {"suit":"clubs","rank":"5"},
    {"suit":"clubs","rank":"4"},
    {"suit":"spades","rank":"2"},
    {"suit":"hearts","rank":"2"}
  ]
}
```

上传的是当前这副手牌，服务端不可当成出牌记录累加。cards 数组必须恰好 13 个元素，每个元素有点数与花色；重复牌各占一个元素，不合并。请求不包含截图。

## 响应草案与面板位置

2xx 返回 JSON，必须回传对应的 event_id 和 round_id。下面是用于显示的示例，不表示客户端会自行生成这些牌：

```json
{
  "schema":"jpq.hand-result/1",
  "event_id":"request-uuid",
  "round_id":1,
  "cards":[
    {"suit":"spades","rank":"A","count":2},
    {"suit":"hearts","rank":"Q","count":1},
    {"suit":"clubs","rank":"5","count":2},
    {"suit":"diamonds","rank":"2","count":1}
  ]
}
```

面板共有 8 行、13 个牌点列。列从左到右为 2 A K Q J 10 9 8 7 6 5 4 3；行从上到下为黑桃两行、红桃两行、梅花两行、方块两行。count=1 填该花色第一行，count=2 再填第二行。黑桃梅花黑字，红桃方块红字。服务端可返回不同于上传手牌的集合，客户端只负责显示，不推断其含义。空 cards 清空本局格子；响应不要求也是 13 张，最多 52 种身份、104 张。

非法身份、非整数 count、count 超过 2、重复身份条目、错误版本、非 JSON、超过 64 KiB 响应均拒绝，不部分显示。请求不匹配、上一局、结束或暂停后的响应不会进入当前面板。新局先清空旧结果再等待新的响应。

## 失败与取消

连接/读取各超时 4 秒。网络异常、429、5xx 最多 3 次尝试，间隔 1 秒、2 秒。其他 HTTP 错误、2xx 但无有效结果不重试。未成功时保留失败状态，不伪造结果；本局不会生成第二个新请求，待下一局或重新启动采集。

仅缓存最新待发请求，不写入手机文件队列。新局、暂停、结束、停止服务时取消待发请求，并断开活动连接；已经到达服务器的数据不能撤销，靠请求 ID 与局 ID 防止串局显示。没有 WebSocket、轮询、定时心跳；若真实接口采用异步推送，须按最终接口文档补接。

截图只在内存处理，上传不包含截图。新功能不改变游戏 FLAG_SECURE 截屏保护。
