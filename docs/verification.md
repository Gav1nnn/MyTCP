# Verification

## Automated suite

From the repository root:

```shell
mvn clean test
```

The suite covers:

- wire header and checksum vectors
- sequence-number wraparound
- receive-window acceptability
- cumulative and partial acknowledgments
- out-of-order reassembly, overlap, duplication, and corruption
- ordered peer-window updates and zero-window persist
- Reno slow start, avoidance, fast retransmit, recovery, timeout, and idle
  restart
- RFC 6298 estimator, Karn filtering, timer lifecycle, and backoff
- handshake retransmission and failure limits
- FIN loss, duplicate FIN, TIME-WAIT, and RST validation
- real UDP endpoint and full session transfer
- CLI transfer under deterministic loss, corruption, duplication, and
  reordering

## Package verification

```shell
mvn package
unzip -p target/mytcp-1.0-SNAPSHOT.jar META-INF/MANIFEST.MF
```

The manifest must contain:

```text
Main-Class: com.ouc.tcp.cli.TcpCli
```

## Normal two-process verification

Prepare a binary input:

```shell
dd if=/dev/urandom of=input.bin bs=1024 count=64
```

Terminal A:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  server 19002 19001 received.bin \
  --trace server.trace
```

Terminal B:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client.trace
```

Verify:

```shell
cmp input.bin received.bin
shasum -a 256 input.bin received.bin
```

## Fault-recovery verification

Repeat the server command, then run:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client-fault.trace \
  --fault "3=drop,4=duplicate,5=reorder,9=corrupt"
```

The transfer must finish with the same byte count and hash. Inspect:

```shell
rg "event=fault|event=sender|event=state" client-fault.trace
```

Expected evidence includes all four fault actions, repeated ACK numbers while
the first data gap exists, a later `SND.UNA` jump after recovery, and the
closing state transitions.

## Repository checks

```shell
git diff --check
git status --short --branch
rg "TCP_TestSys|com\\.ouc\\.tcp\\.test" pom.xml src
```

The first and third commands must produce no output. After the final commit,
the worktree must be clean and synchronized with `origin/rfc-tcp-core`.
