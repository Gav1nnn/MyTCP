# Verification

## Automated checks

Run all tests without network access:

```shell
mvn -o clean test
```

The suite uses a manual monotonic clock. Advancing virtual time executes due
callbacks synchronously, so RTO, persist, delay, and reordering tests contain
no sleeps.

Coverage is organized by protocol responsibility:

- adapter: integer encoding and framework packet round trips
- checksum: pseudo-header, odd payload, corruption, and malformed length
- sequence arithmetic: wraparound and half-range boundaries
- receive path: ordering, overlap, duplicates, window trimming, and tuple
  validation
- send path: segmentation, cumulative and partial ACKs, flow control, and
  ordered window updates
- timing: RTT estimation, Karn filtering, timer lifecycle, timeout backoff,
  persist probing, and idle restart
- congestion: slow start, byte-counted avoidance, Limited Transmit, fast
  retransmit/recovery, and timeout response
- integration: complete sender/channel/receiver/ACK loops under loss,
  corruption, delay, duplication, ACK loss, and sequence wraparound

## Teaching-framework experiment

From the repository root:

```shell
mvn compile
java -cp "target/classes:lib/TCP_TestSys_Linux.jar" com.ouc.tcp.test.TestRun
```

Alternatively, run `mvn package` and start
`target/tcp-test-1-1.0-SNAPSHOT.jar` with `java -jar`.

Before running:

1. Confirm `Config.ini` ports are free.
2. Keep `ENCDA.tcp` in the repository root.
3. Remove an old `recvData.txt` if comparing output manually; the framework
   receiver normally truncates it during initialization.
4. Press Enter when the sender prompt appears.

After completion, compare the decrypted integer sequence represented by
`ENCDA.tcp` with `recvData.txt`. Framework packet events and injected faults
are also written to `Log.txt`. The supplied server and listener threads are
long-lived; stop them with `Ctrl-C` after the transfer is complete.

## Final repository checks

```shell
git diff --check
git status --short
```

The first command must print nothing. The second must be empty after the final
commit.
