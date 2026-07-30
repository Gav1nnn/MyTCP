# MyTCP

MyTCP is a standalone user-space implementation of the core TCP semantics,
carried inside UDP datagrams. It implements a reliable, ordered, full-duplex
byte stream without depending on the original teaching framework.

The implemented profile follows:

- [RFC 9293](https://www.rfc-editor.org/rfc/rfc9293.html): TCP header,
  sequence space, cumulative acknowledgment, receive-window flow control,
  connection establishment, reset handling, and orderly close
- [RFC 5681](https://www.rfc-editor.org/rfc/rfc5681.html): Reno slow start,
  congestion avoidance, fast retransmit, fast recovery, and idle restart
- [RFC 6298](https://www.rfc-editor.org/rfc/rfc6298.html): SRTT/RTTVAR,
  Karn's algorithm, RTO timer management, and exponential backoff
- [RFC 6429](https://www.rfc-editor.org/rfc/rfc6429.html): zero-window
  persist behavior

SACK is intentionally not implemented.

## What is implemented

- fixed 20-byte TCP header encoding in network byte order
- IPv4 pseudo-header TCP checksum
- active and passive three-way handshake with SYN/SYN-ACK retransmission
- all normal connection states from `CLOSED` through `TIME_WAIT`
- byte-oriented 32-bit modular sequence arithmetic
- cumulative ACK with `ACK = RCV.NXT`
- sender variables `SND.UNA`, `SND.NXT`, `SND.WND`, `SND.WL1`, and `SND.WL2`
- receive-window acceptability checks before ACK or data processing
- ordered delivery, out-of-order buffering, overlap trimming, and duplicate
  suppression
- sliding-window flow control and zero-window persist probing
- Reno congestion control and RFC initial-window rules
- adaptive RTO, Karn filtering, earliest-segment timeout retransmission, and
  exponential backoff
- FIN retransmission, duplicate-FIN handling, simultaneous-close states, and
  a configurable `2 * MSL` TIME-WAIT
- RST sequence validation and challenge ACKs
- stable protocol traces and deterministic fault injection

## Explicit boundaries

MyTCP is an RFC-aligned experimental profile, not an operating-system TCP
stack. The encoded TCP segment is transported inside UDP, so it cannot connect
directly to a normal OS TCP socket. The CLI currently binds to IPv4 loopback,
uses one configured peer, and transfers one file from client to server.

TCP options and related extensions are outside this profile: SACK, timestamps,
window scaling, MSS option negotiation, ECN, urgent data, and TCP
authentication. Simultaneous open, a listener backlog, IPv6, Path MTU
Discovery, and a POSIX socket API are also not implemented. The fixed SMSS is
1200 bytes and the receiver delivers available bytes immediately, so the CLI
does not model application-driven receive-buffer pressure.

See [RFC compliance](docs/rfc-compliance.md) for the exact requirement-to-test
mapping.

## Build and test

JDK 17 or newer and Maven are required.

```shell
mvn clean test
mvn package
```

The executable artifact is:

```text
target/mytcp-1.0-SNAPSHOT.jar
```

Running it without arguments prints the command syntax:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar
```

## Transfer a file

Start the server first:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  server 19002 19001 received.bin \
  --trace server.trace
```

Then start the client in another terminal:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client.trace
```

Validate the result:

```shell
cmp input.bin received.bin
shasum -a 256 input.bin received.bin
```

`cmp` must produce no output and both hashes must match.

## Observe cumulative ACK and Reno state

The trace is deliberately line oriented:

```text
event=segment direction=RECEIVE seq=... ack=... len=0 flags=ACK rwnd=32768 checksum=...
event=sender state=ESTABLISHED snd_una=... snd_nxt=... flight=... cwnd=... ssthresh=... rwnd=... rto_ms=...
event=state from=ESTABLISHED to=FIN_WAIT_1
```

On the sender, a larger `snd_una` after an ACK demonstrates cumulative
acknowledgment. `flight` is `SND.NXT - SND.UNA`, while `cwnd`, `ssthresh`, and
`rto_ms` expose congestion and timer behavior.

## Inject reproducible faults

Faults are keyed by the one-based outbound transmission number:

```shell
java -jar target/mytcp-1.0-SNAPSHOT.jar \
  client 19001 19002 input.bin \
  --trace client.trace \
  --fault "3=drop,4=duplicate,5=reorder,9=corrupt"
```

Supported actions are `drop`, `corrupt`, `duplicate`, and `reorder`. Fault
events appear in the same trace, making a run reproducible and reviewable.

## Documentation

- [Architecture](docs/architecture.md)
- [Protocol behavior](docs/protocol-behavior.md)
- [RFC compliance](docs/rfc-compliance.md)
- [Verification](docs/verification.md)
