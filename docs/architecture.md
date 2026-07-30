# 架构设计

MyTCP 将协议决策与 UDP 隧道、命令行入口相互隔离。

```text
文件字节流
    |
    v
TcpSession
    |
    +--> TcpConnectionLifecycle  握手、关闭、RST、TIME-WAIT
    |
    +--> StandaloneTcpEndpoint
            |
            +--> TcpSenderEngine    累计确认、流量控制、Reno、RTO
            |
            +--> TcpReceiverEngine  窗口检查、乱序重组、有序交付
    |
    v
SegmentTransport
    |
    +--> 可选 FaultInjectingTransport
    |
    +--> 可选 TracingSegmentTransport
    |
    v
UdpSegmentTransport --> TcpWireCodec --> UDP Socket
```

## 连接层

`TcpConnectionLifecycle` 维护 RFC 连接状态，并负责 SYN/FIN 对序列号空间的
消耗。`TcpHandshakeRunner` 驱动主动或被动打开，并对控制报文执行次数受限的
指数退避重传。`TcpSession` 协调连接状态机与已建立状态下的发送、接收引擎。

FIN 在被确认前始终保留为重传候选。主动关闭会进入 TIME-WAIT，并维持两倍于
所配置最大报文段生存时间的时长；收到另一个可接受的 FIN 时，该定时器会重新
开始计时。

## 数据层

`TcpSenderEngine` 负责维护：

- `SND.UNA` 和 `SND.NXT`
- 对端通告窗口以及有序窗口更新标记
- 尚未发送的应用数据和重传队列
- Reno 状态以及 RTO/Persist 定时器

`TcpReceiverEngine` 负责维护 `RCV.NXT`、执行接收窗口验证并管理乱序重组队列。
它会把最新接收状态同步给发送端，使每一个新数据段或重传数据段都携带当前的
累计 ACK 和接收窗口。

## 线格式与隧道层

`TcpWireCodec` 编解码 RFC 定义的固定 TCP 首部。IP 地址不属于编码后的 TCP
报文段，而由 UDP 外层提供；但在计算 IPv4 伪首部校验和时仍会使用这些地址。

`UdpSegmentTransport` 要求逻辑 TCP 地址、端口与 UDP 外层信息一致。这样可以
明确隧道边界，并防止调用者在发送时静默伪造报文段源身份。

## 并发模型

协议引擎会同步所有改变状态的操作。数据重传、控制报文重传、Persist、RTO 和
TIME-WAIT 定时器运行在单线程调度器中。定时器回调在对应的状态锁下完成状态
修改，并在可能产生重入式传输回调的位置释放引擎锁之后再发送报文。

## 可观测性与故障注入

`ProtocolTrace` 是可选的协议观察边界。它记录报文发送与接收、状态迁移、
发送控制块快照和注入的故障，但不会参与协议决策。

`FaultInjectingTransport` 按照出站发送次数消费确定性的故障计划，使丢失、
损坏、重复和重排行为能够在不同验证运行中稳定复现。
