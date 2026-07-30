# 验证流程

## 自动化测试

在仓库根目录执行：

```shell
mvn clean test
```

测试范围包括：

- TCP 线格式首部与校验和测试向量
- 序列号回绕
- 接收窗口可接受性
- 累计确认和部分确认
- 乱序重组、重叠、重复与损坏
- 有序对端窗口更新与零窗口 Persist
- Reno 慢启动、拥塞避免、快速重传、快速恢复、超时与空闲重启
- RFC 6298 估算器、Karn 过滤、定时器生命周期与指数退避
- 握手重传与失败次数限制
- FIN 丢失、重复 FIN、TIME-WAIT 与 RST 验证
- 真实 UDP 端点和完整会话传输
- 确定性丢失、损坏、重复和重排下的 CLI 文件传输

## 构建产物验证

```shell
mvn package
unzip -p target/mytcp-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
```

Manifest 必须包含：

```text
Main-Class: com.ouc.tcp.cli.TcpCli
```

## 正常双进程验证

准备一个二进制输入文件：

```shell
dd if=/dev/urandom of=input.bin bs=1024 count=64
```

终端 A：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  server 19002 19001 received.bin \
  --trace server.trace
```

终端 B：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client.trace
```

验证文件：

```shell
cmp input.bin received.bin
shasum -a 256 input.bin received.bin
```

`cmp` 必须没有输出，两个文件的哈希值必须一致。

## 故障恢复验证

重新运行服务端命令，然后执行：

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client-fault.trace \
  --fault "3=drop,4=duplicate,5=reorder,9=corrupt"
```

传输必须正常结束，接收字节数和哈希值必须保持一致。查看关键事件：

```shell
rg "event=fault|event=sender|event=state" client-fault.trace
```

预期证据包括四种故障事件；首个数据缺口存在时会出现重复 ACK；恢复后
`SND.UNA` 会一次向前跳跃；最后能观察到完整的连接关闭状态迁移。

## 仓库检查

```shell
git diff --check
git status --short --branch
rg "TCP_TestSys|com\\.ouc\\.tcp\\.test" pom.xml src
```

第一个和第三个命令必须没有输出。最终提交后，工作区必须保持干净，并与
`origin/rfc-tcp-core` 同步。
