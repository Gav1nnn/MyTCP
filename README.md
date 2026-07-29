# RFC-Aligned TCP Transport Core

This project implements the established-state data transfer portion of TCP
over the OUC TCP teaching framework. The implementation is being developed
against the following standards:

- RFC 9293: reliable byte-stream transfer, sequence space, cumulative
  acknowledgments, checksums, and receive-window flow control
- RFC 5681: TCP Reno slow start, congestion avoidance, fast retransmit, and
  fast recovery
- RFC 6298: RTT measurement and retransmission timeout management

The scoped implementation is complete and connected to the framework entry
points. Protocol behavior is covered by deterministic unit and end-to-end
fault-injection tests.

## Scope

The project focuses on reliable, ordered data delivery over the framework's
simulated unreliable channel. The target behavior includes corruption
detection, loss recovery, out-of-order reassembly, receiver flow control,
Reno congestion control, and adaptive retransmission timing.

SACK, ECN, TCP timestamps, window scaling, and Path MTU Discovery are outside
the project scope.

## Framework boundary

The supplied framework transports Java `TCP_PACKET` objects over UDP and only
dispatches data and acknowledgment packet types to the student implementation.
It does not expose SYN, SYN-ACK, FIN, and RST processing to the protocol
callbacks. For that reason, this project models a connection that is already
in the TCP `ESTABLISHED` state; it is not a wire-compatible operating-system
TCP stack.

The framework entry points remain:

- `com.ouc.tcp.test.TCP_Sender`
- `com.ouc.tcp.test.TCP_Receiver`
- `com.ouc.tcp.test.TestRun`

## Data representation

The framework application supplies data as Java `int[]` groups. The adapter
encodes every integer as four bytes in network byte order before handing data
to the TCP core. Sequence and acknowledgment numbers therefore count bytes,
not framework array elements or packets. The receiver performs the inverse
conversion only after contiguous bytes are ready for application delivery.

## Receive path

The standalone receive engine validates the TCP checksum before changing
connection state, applies RFC receive-window acceptability checks, trims
overlapping data to the current window, buffers out-of-order bytes, and
delivers only the contiguous range beginning at `RCV.NXT`. Its acknowledgment
number is always the first byte that has not been received in order.

Acknowledgment transmission policy, including delayed acknowledgments, is
implemented separately from byte reassembly.

## Send path

The standalone sender assigns sequence numbers only when bytes are admitted by
both the congestion window and the peer's advertised receive window. Sent
segments remain in an ordered retransmission queue until cumulatively
acknowledged. Partial acknowledgments trim only the acknowledged prefix of the
oldest segment, while acknowledgments beyond `SND.NXT` cannot release data.

Peer window updates are ordered with `SND.WL1` and `SND.WL2` so stale
acknowledgments cannot overwrite a newer send-window value.

The Reno congestion controller uses appropriate byte counting in slow start
and congestion avoidance. The first two qualifying duplicate acknowledgments
can clock limited transmissions without increasing `cwnd`; the third triggers
fast retransmit and fast recovery. Retransmission timeout loss returns `cwnd`
to one SMSS and applies exponential RTO backoff independently.

When the peer advertises a zero window, the sender switches from the
retransmission timer to an exponentially backed-off persist timer. Probes do
not consume pending application data or advance `SND.NXT`. A long-idle sender
reduces its congestion window to the restart window before sending again.

## Build

JDK 17 and Maven are required.

```shell
mvn clean package
```

The supplied framework dependency is stored at
`lib/TCP_TestSys_Linux.jar`.

Run the deterministic protocol tests with:

```shell
mvn test
```

The test environment uses a manual monotonic clock and an in-memory channel,
so loss, corruption, delay, reordering, and duplication scenarios do not
depend on wall-clock sleeps or random outcomes.

Run the complete teaching experiment from the repository root with:

```shell
mvn compile
java -cp "target/classes:lib/TCP_TestSys_Linux.jar" com.ouc.tcp.test.TestRun
```

The framework prompts for Enter before reading `ENCDA.tcp`. Received
application integers are written to `recvData.txt`. The supplied listener
threads remain active after the transfer, so stop the experiment with
`Ctrl-C` after the final acknowledgment has arrived.

After `mvn package`, the same experiment can be started with:

```shell
java -jar target/tcp-test-1-1.0-SNAPSHOT.jar
```

## Design documentation

- [`docs/architecture.md`](docs/architecture.md): module boundaries and data
  flow
- [`docs/protocol-behavior.md`](docs/protocol-behavior.md): sender and receiver
  state transitions, invariants, and supported RFC behavior
- [`docs/verification.md`](docs/verification.md): deterministic test strategy
  and experiment checklist

## Repository layout

```text
src/main/java/    implementation
src/test/java/    automated tests
docs/             architecture and protocol notes
lib/              supplied teaching framework
```
