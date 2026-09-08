# v2 手牌快照接口草案

目前没有服务器地址。本客户端只预留可关闭的 HTTPS JSON POST 通道；不部署服务器、不上传截图。导入适配包不会开启网络上传，必须由用户在客户端另行开启。

## 请求

- `POST` 用户指定的 HTTPS URL，拒绝 HTTP、URL 用户密码及 fragment；不跟随重定向。
- `Content-Type: application/json; charset=utf-8`
- `Idempotency-Key: <event_id>`，重试复用同一值。
- 服务端返回任意 2xx 表示接收成功。认证方案、用户与设备绑定、返回体格式需要服务端接口确定后补充，目前不携带认证 Token。

```json
{
  "schema": "jpq.hand-snapshot/1",
  "event_id": "uuid",
  "session_id": "uuid-per-capture-service",
  "sequence": 1,
  "captured_at_ms": 1788880000000,
  "package_id": "adaptation-package-id",
  "region_id": "own-hand-region-id",
  "seat_id": "me",
  "complete": false,
  "observed_count": 3,
  "cards": [
    {"rank": "5", "suit": "clubs", "count": 2},
    {"rank": "Q", "suit": "hearts", "count": 1}
  ]
}
```

`rank` 为 A、2–10、J、Q、K；`suit` 为 spades / hearts / clubs / diamonds。同一身份多张牌用 count 表示。当前不支持大小王身份。`complete:false` 表示只确认检测到的牌，没有证明整副手牌全部识别完毕。不能自动补齐或据此推断未命中牌不存在。

## 状态与失败处理

快照是当前观察值，必须覆盖相应会话/区域的旧观察值，不能累加成出牌。按 event_id 幂等，按 session_id＋sequence 处理乱序。应用进程重启换 session_id，客户端时钟不保证与服务器同步。空识别帧不上传，因此服务端应自行显示最后更新时间及超时状态，不将沉默解释为零张牌。

连续相同的牌数量组合达到配置的确认次数后才产生快照。稳定不变时不重复发送，也没有定时心跳。网络工作在独立线程，只保存最新一个待发快照，不把队列写入手机。每个快照最多尝试 3 次，网络错误、429、5xx 可重试；其他非 2xx 停止重试。连接和读取各限 4 秒，重试间隔 1、2 秒。新快照优先，旧快照可能丢弃。失败 3 次后相同手牌不会自动无限重发，须手牌发生变化或重新采集；这不是可靠消息队列。

结束检测停止生成新快照。已经提交给网络线程的请求可能完成发送。停止服务会关闭上传线程，但已经被服务器接收的请求无法撤销。接口尚未存在，真实 HTTPS 接收、鉴权与服务端幂等均未联调。
