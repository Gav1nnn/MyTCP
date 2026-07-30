# Protocol behavior

## Sequence space and acknowledgment

All data sequence numbers count bytes. Arithmetic is modulo `2^32`, and
ordering is used only within the unambiguous half of the sequence space.
SYN and FIN each consume one sequence number.

The receive path delivers only the contiguous prefix beginning at `RCV.NXT`.
Every reliability ACK contains:

```text
SEG.ACK = RCV.NXT
```

An ACK of `X` therefore confirms every byte before `X`. Out-of-order and
duplicate data repeat the same ACK, while filling a gap advances it across all
newly contiguous buffered bytes.

## Input validation

Before protocol state changes, the implementation checks:

1. connection four-tuple
2. IPv4 TCP checksum
3. segment sequence-space acceptability against `RCV.NXT` and `RCV.WND`
4. ACK acceptability against `SND.UNA` and `SND.NXT`

Checksum failures and wrong connections are discarded. An unacceptable
non-RST segment receives the current ACK. A future ACK cannot release unsent
data. RST handling uses an exact `RCV.NXT` match and a challenge ACK for an
in-window non-exact sequence.

## Flow control

Normal data may be sent only while:

```text
FlightSize < min(cwnd, SND.WND)
```

Window updates follow `SND.WL1` and `SND.WL2`, preventing an older segment
from overwriting a newer peer-window value.

When `SND.WND` is zero, normal transmission and the data RTO timer stop. A
persist probe is sent after one current RTO and then at exponentially backed
off intervals up to 60 seconds. A probe does not consume pending bytes,
advance `SND.NXT`, or reduce `cwnd`.

## RTO behavior

Before an RTT measurement, RTO is one second. If this endpoint retransmitted
its SYN or SYN-ACK, the data-phase RTO starts at three seconds.

For an RTT sample `R`:

```text
RTTVAR <- 3/4 * RTTVAR + 1/4 * |SRTT - R|
SRTT   <- 7/8 * SRTT   + 1/8 * R
RTO    <- SRTT + max(G, 4 * RTTVAR)
```

RTO is clamped to the range 1 through 60 seconds. A timeout retransmits the
earliest unacknowledged segment, doubles RTO, sets `cwnd` to one SMSS, and
restarts slow start. Karn's algorithm excludes retransmitted data from RTT
measurement.

## Reno behavior

For the default 1200-byte SMSS, initial `cwnd` is three segments. A
retransmitted handshake control reduces it to one segment.

- slow start adds at most one SMSS for each ACK that confirms new data
- congestion avoidance adds approximately one SMSS per RTT using byte counting
- the first two qualifying duplicate ACKs may use Limited Transmit
- the third duplicate ACK sets
  `ssthresh = max(FlightSize / 2, 2 * SMSS)` and fast retransmits the oldest
  outstanding segment
- fast recovery uses `cwnd = ssthresh + 3 * SMSS`, inflates it for further
  duplicate ACKs, and exits to `ssthresh` on the next new ACK
- an idle sender restarts with `min(IW, cwnd)`

This is basic Reno rather than SACK or NewReno multi-loss recovery.

## Connection lifecycle

The active peer sends SYN and enters SYN-SENT. The passive peer transitions
from LISTEN to SYN-RECEIVED and replies with SYN+ACK. A valid final ACK
establishes the connection. Duplicate SYN and SYN+ACK controls cause the
corresponding control response to be retransmitted.

Close follows FIN-WAIT-1, FIN-WAIT-2, CLOSE-WAIT, CLOSING, LAST-ACK, and
TIME-WAIT as appropriate. FIN is retransmitted until acknowledged. A FIN
following payload is interpreted at `SEG.SEQ + SEG.LEN`, and duplicate FINs
are acknowledged throughout closing. TIME-WAIT lasts exactly twice the
configured MSL.

The loopback CLI uses a 250 ms MSL because its UDP envelope does not have an
Internet path on which old datagrams may remain for minutes. The multiplier
and restart behavior remain `2 * MSL`.
