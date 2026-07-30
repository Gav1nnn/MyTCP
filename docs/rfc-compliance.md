# RFC compliance profile

This document defines what “RFC-aligned” means for MyTCP. It is an acceptance
profile for a user-space TCP carried over a controlled UDP tunnel, not a claim
that every optional TCP extension or operating-system API is implemented.

## Requirement mapping

| Standard | Required behavior in this profile | Implementation evidence | Verification evidence |
|---|---|---|---|
| RFC 9293 §3.4 | byte sequence space, cumulative ACK, 32-bit wrap | `SequenceNumber32`, sender and receiver control blocks | `SequenceNumber32Test`, `TcpSenderEngineTest`, `TcpReceiverEngineTest` |
| RFC 9293 §3.5 | three-way handshake, SYN sequence consumption, RST validation | `TcpConnectionLifecycle`, `TcpHandshakeRunner` | `TcpConnectionLifecycleTest`, `TcpHandshakeRunnerTest` |
| RFC 9293 §3.6 | orderly and simultaneous-close states, FIN sequence consumption | `TcpConnectionLifecycle`, `TcpSession` | lifecycle, session, and FIN-loss tests |
| RFC 9293 §3.8 | checksum, receive-window acceptability, reliable ordered delivery, flow control | checksum, sender, receiver, and reassembly modules | checksum, receive, sender, endpoint, and end-to-end tests |
| RFC 9293 TIME-WAIT | active close remains for `2 * MSL`; duplicate FIN restarts it | `SessionTiming`, session timer | `SessionTimingTest`, `TcpSessionTest` |
| RFC 5681 §3.1 | initial window, slow start, congestion avoidance | `EndpointTuning`, `RenoCongestionController` | tuning and Reno tests |
| RFC 5681 §3.2 | duplicate ACK, fast retransmit, fast recovery | sender and Reno controller | `TcpSenderRenoTest`, deterministic fault transfer |
| RFC 5681 §4.1 | restart after idle | sender idle tracking | `TcpSenderPersistTest` |
| RFC 6298 §§2–5 | SRTT/RTTVAR, Karn, one RTO timer, earliest retransmission, backoff | `RttEstimator`, `RetransmissionTimer`, retransmission queue | RTT, timer, and retransmission tests |
| RFC 6298 §5.7 | data RTO returns to 3 seconds after SYN loss | handshake result and endpoint RTO injection | handshake and endpoint tests |
| RFC 6429 | zero-window persist and exponential probes | sender persist timer | `TcpSenderPersistTest` |

## Acceptance criteria

The profile is accepted only when all of the following hold:

- the full Maven test suite has no failure or error
- the executable JAR has `com.ouc.tcp.cli.TcpCli` as its main class
- a real two-process UDP transfer produces byte-identical input and output
- the deterministic combined loss/corruption/duplication/reordering plan
  still produces byte-identical output
- the trace shows three-way handshake, monotonic cumulative `SND.UNA`
  advancement, Reno state changes, FIN close, and TIME-WAIT
- no teaching-framework class or binary dependency remains

## Excluded behavior

The following are outside the accepted profile:

- SACK and SACK loss recovery
- NewReno multi-loss recovery
- TCP options, including MSS negotiation, timestamps, and window scaling
- ECN and urgent-data semantics
- simultaneous active open
- multiple-client listen backlog
- IPv6 and raw-IP transport
- Path MTU Discovery and IP fragmentation control
- Nagle and delayed-ACK optimizations
- POSIX socket compatibility
- cryptographic authentication

These exclusions must remain visible in project documentation. Adding one
requires new protocol state, tests, and an update to this matrix.
