# MyTCP

MyTCP 是一个独立的用户态 TCP 核心语义实现，使用 UDP 数据报承载编码后的
TCP 报文段。它在不依赖原有教学框架的情况下，提供可靠、有序、全双工的字节流。

当前实现遵循以下标准：

- [RFC 9293](https://www.rfc-editor.org/rfc/rfc9293.html)：TCP 首部、序列号空间、
  累计确认、接收窗口流量控制、连接建立、复位处理与有序关闭
- [RFC 5681](https://www.rfc-editor.org/rfc/rfc5681.html)：Reno 慢启动、拥塞避免、
  快速重传、快速恢复与空闲重启
- [RFC 6298](https://www.rfc-editor.org/rfc/rfc6298.html)：SRTT/RTTVAR、
  Karn 算法、RTO 定时器管理与指数退避
- [RFC 6429](https://www.rfc-editor.org/rfc/rfc6429.html)：零窗口 Persist 行为

本项目有意不实现 SACK。

## 已实现功能

- 使用网络字节序编解码固定 20 字节 TCP 首部
- 基于 IPv4 伪首部计算 TCP 校验和
- 主动/被动三次握手，以及 SYN/SYN-ACK 重传
- 从 `CLOSED` 到 `TIME_WAIT` 的正常 TCP 连接状态
- 面向字节的 32 位模序列号运算
- 满足 `ACK = RCV.NXT` 的累计确认
- 发送端变量 `SND.UNA`、`SND.NXT`、`SND.WND`、`SND.WL1` 和 `SND.WL2`
- 在处理 ACK 或数据前执行接收窗口可接受性检查
- 有序交付、乱序缓存、重叠裁剪和重复数据抑制
- 滑动窗口流量控制与零窗口 Persist 探测
- Reno 拥塞控制和 RFC 初始拥塞窗口规则
- 自适应 RTO、Karn 过滤、最早报文段超时重传与指数退避
- FIN 重传、重复 FIN 处理、同时关闭状态与可配置的 `2 * MSL` TIME-WAIT
- RST 序列号验证与 Challenge ACK
- 稳定的协议 Trace 和确定性故障注入

## 明确的实现边界

MyTCP 是一个与 RFC 核心语义对齐的实验性实现，不是操作系统 TCP 协议栈。
编码后的 TCP 报文段由 UDP 承载，因此不能直接连接普通的操作系统 TCP Socket。
当前 CLI 绑定 IPv4 回环地址，只连接一个预先配置的对端，并从客户端向服务端传输
一个文件。

以下 TCP 选项和扩展不在当前范围内：SACK、时间戳、窗口缩放、MSS 选项协商、
ECN、紧急数据以及 TCP 认证。同时主动打开、监听队列、IPv6、路径 MTU 发现和
POSIX Socket API 也没有实现。当前固定 SMSS 为 1200 字节；接收端会立即向应用
交付可用字节，因此 CLI 不模拟应用消费速度导致的接收缓冲区压力。

具体的标准条目与测试映射见
[RFC 符合性说明](docs/rfc-compliance.md)。

## 构建与测试

需要 JDK 17 或更高版本以及 Maven。

```shell
mvn clean test
mvn package
```

生成的可执行文件为：

```text
target/mytcp-1.0-SNAPSHOT.jar
```

不带参数运行可以查看命令格式：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar
```

## 传输文件

首先启动服务端：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  server 19002 19001 received.bin \
  --trace server.trace
```

然后在另一个终端启动客户端：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client.trace
```

验证传输结果：

```shell
cmp input.bin received.bin
shasum -a 256 input.bin received.bin
```

`cmp` 不应产生任何输出，两个文件的哈希值必须完全一致。

## 观察累计确认与 Reno 状态

Trace 使用稳定的单行格式：

```text
event=segment direction=RECEIVE seq=... ack=... len=0 flags=ACK rwnd=32768 checksum=...
event=sender state=ESTABLISHED snd_una=... snd_nxt=... flight=... cwnd=... ssthresh=... rwnd=... rto_ms=...
event=state from=ESTABLISHED to=FIN_WAIT_1
```

发送端收到 ACK 后，`snd_una` 的前移体现累计确认。`flight` 表示
`SND.NXT - SND.UNA`，`cwnd`、`ssthresh` 和 `rto_ms` 分别展示拥塞控制和
重传定时器的状态。

## 注入可复现故障

故障按照从 1 开始的出站发送次数进行配置：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client.trace \
  --fault "3=drop,4=duplicate,5=reorder,9=corrupt"
```

支持 `drop`、`corrupt`、`duplicate` 和 `reorder`。故障事件会写入同一份
Trace，使每次实验都可以复现和检查。

## 文档

- [架构设计](docs/architecture.md)
- [协议行为](docs/protocol-behavior.md)
- [RFC 符合性说明](docs/rfc-compliance.md)
- [验证流程](docs/verification.md)
