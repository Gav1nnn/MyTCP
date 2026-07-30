# Architecture

MyTCP keeps protocol decisions independent from the UDP tunnel and CLI.

```text
file bytes
    |
    v
TcpSession
    |
    +--> TcpConnectionLifecycle  handshake, close, RST, TIME-WAIT
    |
    +--> StandaloneTcpEndpoint
            |
            +--> TcpSenderEngine    cumulative ACK, flow control, Reno, RTO
            |
            +--> TcpReceiverEngine  window checks, reassembly, ordered delivery
    |
    v
SegmentTransport
    |
    +--> optional FaultInjectingTransport
    |
    +--> optional TracingSegmentTransport
    |
    v
UdpSegmentTransport --> TcpWireCodec --> UDP socket
```

## Connection layer

`TcpConnectionLifecycle` owns the RFC connection states and the SYN/FIN
sequence-space consumption. `TcpHandshakeRunner` drives active or passive
open with bounded, exponentially backed-off control retransmission.
`TcpSession` coordinates the connection state machine with the established
sender and receiver engines.

FIN remains a retransmission candidate until acknowledged. Active close enters
TIME-WAIT for twice the configured maximum segment lifetime; another accepted
FIN restarts that timer.

## Data layer

`TcpSenderEngine` owns:

- `SND.UNA` and `SND.NXT`
- the peer window and ordered window-update markers
- pending application data and the retransmission queue
- Reno state and the RTO/persist timers

`TcpReceiverEngine` owns `RCV.NXT`, receive-window validation, and the
out-of-order reassembly queue. It exposes the current receive state to the
sender so every new or retransmitted data segment carries the latest
cumulative ACK and advertised window.

## Wire and tunnel layer

`TcpWireCodec` encodes the fixed RFC TCP header. IP addresses remain outside
the encoded TCP segment because the UDP envelope supplies them; they are still
included in the IPv4 pseudo-header checksum.

`UdpSegmentTransport` requires the logical TCP ports and addresses to match
the UDP envelope. This makes the tunnel explicit and prevents a caller from
silently changing a segment's source identity.

## Concurrency

Protocol engines synchronize state-changing operations. Data, control,
persist, RTO, and TIME-WAIT timers run on single-threaded schedulers.
Timer callbacks perform state changes under the relevant monitor and send
outside the engine lock where re-entrant transport callbacks could occur.

## Observability and fault injection

`ProtocolTrace` is an optional observation boundary. It records segment
attempts, state transitions, sender snapshots, and injected faults without
being used to make protocol decisions.

`FaultInjectingTransport` consumes a deterministic plan by outbound
transmission number. This keeps loss, corruption, duplication, and reordering
repeatable across verification runs.
