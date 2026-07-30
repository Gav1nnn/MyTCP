# RFC 符合性说明

本文档定义 MyTCP 中“与 RFC 对齐”的准确含义。它是一份面向受控 UDP 隧道的
用户态 TCP 验收范围，不表示已经实现全部可选 TCP 扩展或操作系统接口。

## 标准条目映射

| 标准 | 当前范围要求 | 实现证据 | 验证证据 |
|---|---|---|---|
| RFC 9293 §3.4 | 字节序列号空间、累计确认、32 位回绕 | `SequenceNumber32`、发送与接收控制块 | `SequenceNumber32Test`、`TcpSenderEngineTest`、`TcpReceiverEngineTest` |
| RFC 9293 §3.5 | 三次握手、SYN 消耗序列号、RST 验证 | `TcpConnectionLifecycle`、`TcpHandshakeRunner` | `TcpConnectionLifecycleTest`、`TcpHandshakeRunnerTest` |
| RFC 9293 §3.6 | 有序关闭和同时关闭状态、FIN 消耗序列号 | `TcpConnectionLifecycle`、`TcpSession` | 生命周期、会话和 FIN 丢失测试 |
| RFC 9293 §3.8 | 校验和、接收窗口可接受性、可靠有序交付、流量控制 | 校验和、发送端、接收端和重组模块 | 校验和、接收、发送、端点与端到端测试 |
| RFC 9293 TIME-WAIT | 主动关闭保持 `2 * MSL`，重复 FIN 重新计时 | `SessionTiming`、会话定时器 | `SessionTimingTest`、`TcpSessionTest` |
| RFC 5681 §3.1 | 初始窗口、慢启动、拥塞避免 | `EndpointTuning`、`RenoCongestionController` | 端点调优和 Reno 测试 |
| RFC 5681 §3.2 | 重复 ACK、快速重传、快速恢复 | 发送端和 Reno 控制器 | `TcpSenderRenoTest`、确定性故障传输 |
| RFC 5681 §4.1 | 空闲后的拥塞窗口重启 | 发送端空闲时间跟踪 | `TcpSenderPersistTest` |
| RFC 6298 §§2–5 | SRTT/RTTVAR、Karn、单一 RTO 定时器、最早报文重传、退避 | `RttEstimator`、`RetransmissionTimer`、重传队列 | RTT、定时器和重传测试 |
| RFC 6298 §5.7 | SYN 丢失后数据 RTO 恢复为三秒 | 握手结果和端点 RTO 注入 | 握手与端点测试 |
| RFC 6429 | 零窗口 Persist 和指数退避探测 | 发送端 Persist 定时器 | `TcpSenderPersistTest` |

## 验收条件

只有同时满足以下条件，当前实现范围才可以通过验收：

- Maven 全部测试没有失败或错误
- 可执行 JAR 的主类为 `com.ouc.tcp.cli.TcpCli`
- 真实双进程 UDP 传输的输入、输出逐字节一致
- 同时注入丢失、损坏、重复和重排后，输出仍与输入逐字节一致
- Trace 能展示三次握手、累计 `SND.UNA` 前移、Reno 状态变化、FIN 关闭和
  TIME-WAIT
- 不再包含教学框架类或二进制依赖

## 明确排除的行为

以下功能不属于当前验收范围：

- SACK 及其丢包恢复算法
- NewReno 多丢包恢复
- TCP 选项，包括 MSS 协商、时间戳和窗口缩放
- ECN 和紧急数据语义
- 同时主动打开
- 多客户端监听队列
- IPv6 和原始 IP 传输
- 路径 MTU 发现与 IP 分片控制
- Nagle 和延迟 ACK 优化
- POSIX Socket 兼容
- 密码学认证

这些限制必须始终在项目文档中明确说明。如果未来要加入其中一项，就必须增加
对应的协议状态、自动化测试并更新本验收矩阵。
