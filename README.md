# RFC-Aligned TCP Transport Core

This project implements the established-state data transfer portion of TCP
over the OUC TCP teaching framework. The implementation is being developed
against the following standards:

- RFC 9293: reliable byte-stream transfer, sequence space, cumulative
  acknowledgments, checksums, and receive-window flow control
- RFC 5681: TCP Reno slow start, congestion avoidance, fast retransmit, and
  fast recovery
- RFC 6298: RTT measurement and retransmission timeout management

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

## Build

JDK 17 and Maven are required.

```shell
mvn clean package
```

The supplied framework dependency is stored at
`lib/TCP_TestSys_Linux.jar`.

## Repository layout

```text
src/main/java/    implementation
src/test/java/    automated tests
lib/              supplied teaching framework
```
