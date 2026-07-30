# 协议行为

## 序列号空间与确认

所有数据序列号都以字节为单位。序列号按照 `2^32` 取模运算，只有在无歧义的
半序列号空间内才进行先后关系比较。SYN 和 FIN 各消耗一个序列号。

接收路径只会交付从 `RCV.NXT` 开始的连续字节前缀。所有用于保证可靠性的 ACK
都满足：

```text
SEG.ACK = RCV.NXT
```

因此，ACK 为 `X` 表示确认了 `X` 之前的所有字节。乱序数据和重复数据会重复
当前 ACK；当缺口被填补后，ACK 会一次越过所有新形成的连续缓存字节。

## 输入验证

在改变协议状态之前，实现会依次检查：

1. 连接四元组
2. IPv4 TCP 校验和
3. 报文段序列号相对 `RCV.NXT` 和 `RCV.WND` 的可接受性
4. ACK 相对 `SND.UNA` 和 `SND.NXT` 的可接受性

校验和错误和连接不匹配的报文会被丢弃。不可接受的非 RST 报文会得到包含当前
确认号的 ACK。未来 ACK 不能释放尚未发送的数据。RST 必须精确匹配
`RCV.NXT`；处于接收窗口内但不精确匹配的 RST 会触发 Challenge ACK。

## 流量控制

只有满足以下条件时才能发送正常数据：

```text
FlightSize < min(cwnd, SND.WND)
```

窗口更新遵循 `SND.WL1` 和 `SND.WL2`，防止旧报文段覆盖更新的对端窗口。

当 `SND.WND` 为零时，正常数据发送和数据 RTO 定时器都会停止。发送端会在一个
当前 RTO 后发送 Persist 探测，之后按指数退避增加间隔，最大为 60 秒。
Persist 探测不会消耗待发送字节、推进 `SND.NXT` 或减小 `cwnd`。

## RTO 行为

尚未获得 RTT 测量值时，RTO 初始为一秒。如果本端重传过 SYN 或 SYN-ACK，
数据阶段的 RTO 会从三秒开始。

对于一次 RTT 样本 `R`：

```text
RTTVAR <- 3/4 * RTTVAR + 1/4 * |SRTT - R|
SRTT   <- 7/8 * SRTT   + 1/8 * R
RTO    <- SRTT + max(G, 4 * RTTVAR)
```

RTO 被限制在 1 到 60 秒。超时后只重传最早的未确认报文段，将 RTO 加倍，
把 `cwnd` 设为一个 SMSS，并重新进入慢启动。Karn 算法会排除发生过重传的
数据，不使用其 ACK 计算 RTT。

## Reno 行为

默认 SMSS 为 1200 字节，初始 `cwnd` 为三个报文段。如果握手控制报文发生过
重传，初始窗口会进一步降低为一个报文段。

- 慢启动中，每个确认新数据的 ACK 最多使 `cwnd` 增加一个 SMSS
- 拥塞避免使用字节计数，每个 RTT 大约增加一个 SMSS
- 前两个符合条件的重复 ACK 可以使用 Limited Transmit
- 第三个重复 ACK 会设置
  `ssthresh = max(FlightSize / 2, 2 * SMSS)`，并快速重传最早的未确认报文段
- 快速恢复使用 `cwnd = ssthresh + 3 * SMSS`，后续重复 ACK 会继续膨胀
  `cwnd`；收到新的 ACK 后退出快速恢复并把 `cwnd` 恢复为 `ssthresh`
- 空闲发送端使用 `min(IW, cwnd)` 重新开始发送

当前实现采用基础 Reno，不实现 SACK 或 NewReno 多丢包恢复。

## 连接生命周期

主动端发送 SYN 并进入 SYN-SENT。被动端从 LISTEN 进入 SYN-RECEIVED，并回复
SYN+ACK。收到合法的最终 ACK 后连接进入 ESTABLISHED。重复 SYN 和 SYN+ACK
会触发对应控制报文的重传。

关闭过程根据双方行为经过 FIN-WAIT-1、FIN-WAIT-2、CLOSE-WAIT、CLOSING、
LAST-ACK 和 TIME-WAIT。FIN 在确认前会被重传。携带数据的 FIN 位于
`SEG.SEQ + SEG.LEN`，关闭过程中的重复 FIN 会被重新确认。TIME-WAIT 精确持续
两倍于所配置的 MSL。

回环 CLI 使用 250 ms 的 MSL，因为它的 UDP 外层不存在可能让旧数据报滞留数
分钟的互联网路径；`2 * MSL` 倍数和重复 FIN 重新计时行为保持不变。
