# Protocol behavior

## Sequence space and checksums

Sequence and acknowledgment numbers use serial-number arithmetic modulo
2^32. Comparisons are valid only within the unambiguous half-range. Application
integers are encoded as four network-order bytes, so all sequence variables
count bytes.

Every segment carries a 16-bit one's-complement TCP checksum including the
IPv4 pseudo-header. A corrupted or malformed segment is discarded before it
can change protocol state.

## Receive path

The configured connection four-tuple is checked before data processing.
Acceptable bytes are trimmed to the current receive window, overlap is
deduplicated, and out-of-order data is retained. Only the contiguous prefix
beginning at `RCV.NXT` is delivered.

Every valid data arrival that affects reliability produces a cumulative
acknowledgment with:

```text
SEG.ACK = RCV.NXT
```

Out-of-order, duplicate, and out-of-window segments therefore repeat the
current acknowledgment and can drive sender loss recovery. Checksum failures
are discarded silently.

The implementation sends immediate ACKs. Delayed ACK is an allowed
optimization rather than a reliability requirement and is intentionally not
used in the teaching adapter, keeping fault behavior deterministic.

## Send path and flow control

New data may be emitted only while:

```text
FlightSize < min(cwnd, SND.WND)
```

Segments are limited to SMSS and retained until cumulatively acknowledged.
Partial ACKs trim the acknowledged prefix of the oldest outstanding segment.
ACKs beyond `SND.NXT`, stale ACKs, invalid checksums, and incorrect
connections cannot release data.

Window updates follow `SND.WL1` and `SND.WL2`. A zero window prevents normal
new-data transmission. The persist timer sends probes after one RTO and then
backs off exponentially to 60 seconds. Persist probing does not reduce
`cwnd`, consume unsent data, or advance sequence space.

## Retransmission timing

The initial RTO is one second. RTT samples update:

```text
RTTVAR <- (1 - beta) * RTTVAR + beta * |SRTT - R|
SRTT   <- (1 - alpha) * SRTT + alpha * R
RTO    <- SRTT + max(G, 4 * RTTVAR)
```

where `alpha = 1/8` and `beta = 1/4`. RTO is clamped between 1 and
60 seconds. Timeout retransmits only the earliest outstanding segment and
doubles RTO. Karn's algorithm excludes retransmitted data from RTT samples.

## Reno congestion control

Slow start increases `cwnd` by `min(N, SMSS)` for an ACK that newly
acknowledges `N` bytes. Congestion avoidance counts acknowledged bytes and
adds one SMSS after approximately one congestion window is acknowledged.

The first two qualifying duplicate ACKs may send one limited-transmit segment
without changing `cwnd`. On the third:

```text
ssthresh = max(FlightSize / 2, 2 * SMSS)
cwnd     = ssthresh + 3 * SMSS
```

The earliest outstanding segment is fast retransmitted. Additional duplicate
ACKs inflate `cwnd` by one SMSS. The next new ACK exits basic Reno fast
recovery and deflates `cwnd` to `ssthresh`.

An RTO sets `cwnd` to one SMSS and resumes slow start. A connection idle for
longer than one RTO restarts with `min(IW, cwnd)`.

## Deliberate scope limits

The supplied callbacks expose an already established, one-way application
transfer. The core therefore does not implement SYN negotiation, FIN/RST
lifecycle, simultaneous open, or TIME-WAIT. SACK, NewReno multi-loss
recovery, ECN, timestamps, window scaling, Nagle, and Path MTU Discovery are
also outside scope.
