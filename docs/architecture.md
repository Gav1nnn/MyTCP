# Architecture

The project separates TCP protocol decisions from the OUC teaching framework.
The framework is treated as an unreliable packet transport and does not own
TCP state.

```text
OUC application int[]
        |
        v
IntegerPayloadCodec
        |
        v
TcpSenderEngine ----> FrameworkPacketCodec ----> OUC unreliable channel
        ^                                               |
        |                                               v
ACK handling <---- FrameworkPacketCodec <---- TcpReceiverEngine
                                                    |
                                                    v
                                            ordered application bytes
```

## Core

`com.ouc.tcp.core` contains immutable segments and the established-state
sender and receiver engines. It owns the TCP control variables:

- sender: `SND.UNA`, `SND.NXT`, `SND.WND`, `SND.WL1`, and `SND.WL2`
- receiver: `RCV.NXT` and `RCV.WND`

All public engine methods are synchronized. Timer callbacks acquire the same
engine monitor before changing protocol state, but invoke the packet sink
after releasing it to avoid re-entrant network callbacks.

## Buffers

`PendingDataBuffer` contains application bytes that have not entered sequence
space. `RetransmissionQueue` contains sent bytes until a cumulative
acknowledgment covers them. `ReassemblyQueue` contains accepted out-of-order
receive bytes until the gap at `RCV.NXT` is filled.

The distinction is important: queueing application data does not advance
`SND.NXT`; only emitting a normal data segment does. A zero-window probe peeks
at pending bytes and therefore also leaves `SND.NXT` unchanged.

## Congestion and timers

`RenoCongestionController` owns `cwnd`, `ssthresh`, duplicate-ACK counting,
and the slow-start, congestion-avoidance, and fast-recovery phases.

`RttEstimator` owns `SRTT`, `RTTVAR`, and the backed-off RTO.
`RetransmissionTimer` provides a generation-protected one-shot timer.
The sender uses independent instances for retransmission and zero-window
persist timing.

Time and scheduling are injected through `Clock` and `Scheduler`. Production
uses `System.nanoTime()` and `ExecutorScheduler`; tests use a manual clock and
deterministic scheduler.

## Framework boundary

Only the classes under `com.ouc.tcp.test` extend the supplied framework:

- `TCP_Sender` converts application integers to bytes and delegates to the
  sender engine.
- `TCP_Receiver` delegates incoming segments to the receiver engine and writes
  delivered integers to the framework output file.
- `TestRun` starts the supplied experiment.

`FrameworkPacketCodec` is the only place that understands mutable
`TCP_PACKET`, signed Java header fields, and the framework's `int[]` payload
format.
