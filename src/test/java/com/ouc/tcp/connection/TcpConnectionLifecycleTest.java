package com.ouc.tcp.connection;

import com.ouc.tcp.checksum.TcpChecksum;
import com.ouc.tcp.core.SequenceNumber32;
import com.ouc.tcp.core.TcpFlag;
import com.ouc.tcp.core.TcpSegment;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpConnectionLifecycleTest {
    private static final long CLIENT_ISN = 10_000;
    private static final long SERVER_ISN = 90_000;

    @Test
    void completesThreeWayHandshakeWithSynSequenceConsumption() throws Exception {
        Peers peers = peers();

        TcpSegment syn = only(peers.client.connect());
        assertEquals(TcpState.SYN_SENT, peers.client.state());
        assertEquals(CLIENT_ISN, syn.sequenceNumber());
        assertEquals(Set.of(TcpFlag.SYN), syn.flags());

        TcpSegment synAck = only(peers.server.receive(syn));
        assertEquals(TcpState.SYN_RECEIVED, peers.server.state());
        assertEquals(SERVER_ISN, synAck.sequenceNumber());
        assertEquals(CLIENT_ISN + 1, synAck.acknowledgmentNumber());
        assertEquals(Set.of(TcpFlag.SYN, TcpFlag.ACK), synAck.flags());

        TcpSegment ack = only(peers.client.receive(synAck));
        assertEquals(TcpState.ESTABLISHED, peers.client.state());
        assertEquals(CLIENT_ISN + 1, ack.sequenceNumber());
        assertEquals(SERVER_ISN + 1, ack.acknowledgmentNumber());

        LifecycleResult established = peers.server.receive(ack);
        assertTrue(established.accepted());
        assertEquals(TcpState.ESTABLISHED, peers.server.state());
        assertTrue(established.transmissions().isEmpty());
        assertTrue(peers.client.retransmissionCandidate().isEmpty());
        assertTrue(peers.server.retransmissionCandidate().isEmpty());
    }

    @Test
    void retainsHandshakeControlUntilAcknowledged() throws Exception {
        Peers peers = peers();

        TcpSegment syn = only(peers.client.connect());
        assertEquals(Optional.of(syn), peers.client.retransmissionCandidate());

        TcpSegment synAck = only(peers.server.receive(syn));
        assertTrue(peers.client.retransmissionCandidate().isPresent());
        assertEquals(Optional.of(synAck), peers.server.retransmissionCandidate());

        TcpSegment repeatedSynAck = only(peers.server.receive(syn));
        assertEquals(synAck, repeatedSynAck);

        TcpSegment finalAck = only(peers.client.receive(synAck));
        assertTrue(peers.client.retransmissionCandidate().isEmpty());
        peers.server.receive(finalAck);
        assertTrue(peers.server.retransmissionCandidate().isEmpty());

        TcpSegment repeatedFinalAck = only(peers.client.receive(synAck));
        assertEquals(finalAck, repeatedFinalAck);
    }

    @Test
    void performsOrderlyActiveAndPassiveClose() throws Exception {
        Peers peers = establishedPeers();
        SequenceNumber32 clientSendNext = SequenceNumber32.of(CLIENT_ISN + 1 + 800);
        SequenceNumber32 serverReceiveNext = clientSendNext;
        SequenceNumber32 serverSendNext = SequenceNumber32.of(SERVER_ISN + 1);
        SequenceNumber32 clientReceiveNext = serverSendNext;
        peers.client.synchronizeEstablishedSequenceSpace(
                clientSendNext, clientReceiveNext);
        peers.server.synchronizeEstablishedSequenceSpace(
                serverSendNext, serverReceiveNext);

        TcpSegment clientFin = only(peers.client.close(
                clientSendNext, clientReceiveNext));
        assertEquals(
                Optional.of(clientFin),
                peers.client.retransmissionCandidate());
        LifecycleResult passiveFin = peers.server.receive(clientFin);
        TcpSegment finAck = only(passiveFin);
        assertEquals(TcpState.FIN_WAIT_1, peers.client.state());
        assertEquals(TcpState.CLOSE_WAIT, peers.server.state());
        assertEquals(clientSendNext.add(1).toLong(), finAck.acknowledgmentNumber());

        peers.client.receive(finAck);
        assertEquals(TcpState.FIN_WAIT_2, peers.client.state());
        assertTrue(peers.client.retransmissionCandidate().isEmpty());

        TcpSegment serverFin = only(peers.server.close(
                serverSendNext, serverReceiveNext.add(1)));
        assertEquals(
                Optional.of(serverFin),
                peers.server.retransmissionCandidate());
        TcpSegment lastAck = only(peers.client.receive(serverFin));
        assertEquals(TcpState.LAST_ACK, peers.server.state());
        assertEquals(TcpState.TIME_WAIT, peers.client.state());

        peers.server.receive(lastAck);
        assertEquals(TcpState.CLOSED, peers.server.state());
        assertTrue(peers.server.retransmissionCandidate().isEmpty());
        peers.client.expireTimeWait();
        assertEquals(TcpState.CLOSED, peers.client.state());
    }

    @Test
    void ignoresWrongConnectionAndCorruptHandshakeSegments() throws Exception {
        Peers peers = peers();
        TcpSegment syn = only(peers.client.connect());
        TcpSegment wrongTuple = new TcpSegment(
                syn.sourceAddress(),
                syn.destinationAddress(),
                syn.sourcePort(),
                syn.destinationPort() + 1,
                syn.sequenceNumber(),
                syn.acknowledgmentNumber(),
                syn.flags(),
                syn.advertisedWindow(),
                syn.checksum(),
                syn.payload());
        TcpSegment corrupt = syn.withChecksum(syn.checksum() ^ 1);

        assertFalse(peers.server.receive(wrongTuple).accepted());
        assertFalse(peers.server.receive(corrupt).accepted());
        assertEquals(TcpState.LISTEN, peers.server.state());
    }

    @Test
    void resetClosesSynchronizedConnection() throws Exception {
        Peers peers = establishedPeers();
        TcpSegment reset = TcpChecksum.apply(new TcpSegment(
                peers.clientConfig.localAddress(),
                peers.clientConfig.remoteAddress(),
                peers.clientConfig.localPort(),
                peers.clientConfig.remotePort(),
                CLIENT_ISN + 1,
                SERVER_ISN + 1,
                Set.of(TcpFlag.RST),
                32_768,
                0,
                new byte[0]));

        assertTrue(peers.server.receive(reset).accepted());
        assertEquals(TcpState.CLOSED, peers.server.state());
    }

    @Test
    void resetIsIgnoredWhileListening() throws Exception {
        Peers peers = peers();
        TcpSegment reset = TcpChecksum.apply(new TcpSegment(
                peers.clientConfig.localAddress(),
                peers.clientConfig.remoteAddress(),
                peers.clientConfig.localPort(),
                peers.clientConfig.remotePort(),
                CLIENT_ISN,
                0,
                Set.of(TcpFlag.RST),
                32_768,
                0,
                new byte[0]));

        LifecycleResult result = peers.server.receive(reset);

        assertFalse(result.accepted());
        assertEquals(TcpState.LISTEN, peers.server.state());
    }

    @Test
    void inWindowResetUsesChallengeAckUnlessItExactlyMatchesRcvNxt()
            throws Exception {
        Peers peers = establishedPeers();
        TcpSegment reset = TcpChecksum.apply(new TcpSegment(
                peers.clientConfig.localAddress(),
                peers.clientConfig.remoteAddress(),
                peers.clientConfig.localPort(),
                peers.clientConfig.remotePort(),
                CLIENT_ISN + 2,
                SERVER_ISN + 1,
                Set.of(TcpFlag.RST),
                32_768,
                0,
                new byte[0]));

        LifecycleResult result = peers.server.receive(reset);

        assertFalse(result.accepted());
        assertEquals(TcpState.ESTABLISHED, peers.server.state());
        TcpSegment challengeAck = only(result);
        assertEquals(CLIENT_ISN + 1, challengeAck.acknowledgmentNumber());
        assertEquals(Set.of(TcpFlag.ACK), challengeAck.flags());
    }

    @Test
    void finSequenceFollowsPayloadSequenceSpace() throws Exception {
        Peers peers = establishedPeers();
        TcpSegment dataAndFin = TcpChecksum.apply(new TcpSegment(
                peers.clientConfig.localAddress(),
                peers.clientConfig.remoteAddress(),
                peers.clientConfig.localPort(),
                peers.clientConfig.remotePort(),
                CLIENT_ISN + 1,
                SERVER_ISN + 1,
                Set.of(TcpFlag.ACK, TcpFlag.FIN),
                32_768,
                0,
                new byte[] {1, 2, 3}));
        peers.server.synchronizeReceiveSequenceSpace(
                SequenceNumber32.of(CLIENT_ISN + 4));

        TcpSegment acknowledgment = only(peers.server.receive(dataAndFin));

        assertEquals(TcpState.CLOSE_WAIT, peers.server.state());
        assertEquals(CLIENT_ISN + 5, acknowledgment.acknowledgmentNumber());
    }

    @Test
    void duplicateFinIsAcknowledgedWhileClosing() throws Exception {
        Peers peers = establishedPeers();
        TcpSegment clientFin = only(peers.client.close(
                SequenceNumber32.of(CLIENT_ISN + 1),
                SequenceNumber32.of(SERVER_ISN + 1)));
        TcpSegment firstAck = only(peers.server.receive(clientFin));
        assertEquals(TcpState.CLOSE_WAIT, peers.server.state());

        TcpSegment repeatedAck = only(peers.server.receive(clientFin));

        assertEquals(firstAck, repeatedAck);
        assertEquals(TcpState.CLOSE_WAIT, peers.server.state());
    }

    @Test
    void simultaneousClosePassesThroughClosingAndTimeWait()
            throws Exception {
        Peers peers = establishedPeers();
        SequenceNumber32 clientNext =
                SequenceNumber32.of(CLIENT_ISN + 1);
        SequenceNumber32 serverNext =
                SequenceNumber32.of(SERVER_ISN + 1);

        TcpSegment clientFin = only(peers.client.close(
                clientNext,
                serverNext));
        TcpSegment serverFin = only(peers.server.close(
                serverNext,
                clientNext));

        TcpSegment clientAck = only(peers.client.receive(serverFin));
        TcpSegment serverAck = only(peers.server.receive(clientFin));
        assertEquals(TcpState.CLOSING, peers.client.state());
        assertEquals(TcpState.CLOSING, peers.server.state());

        peers.client.receive(serverAck);
        peers.server.receive(clientAck);
        assertEquals(TcpState.TIME_WAIT, peers.client.state());
        assertEquals(TcpState.TIME_WAIT, peers.server.state());
    }

    @Test
    void rejectsInvalidLifecycleCalls() throws Exception {
        Peers peers = peers();

        assertThrows(IllegalStateException.class, peers.server::connect);
        assertThrows(IllegalStateException.class, () -> peers.client.close(
                SequenceNumber32.of(1), SequenceNumber32.of(1)));
        assertThrows(IllegalStateException.class, peers.client::expireTimeWait);
    }

    private static Peers establishedPeers() throws Exception {
        Peers peers = peers();
        TcpSegment syn = only(peers.client.connect());
        TcpSegment synAck = only(peers.server.receive(syn));
        TcpSegment ack = only(peers.client.receive(synAck));
        peers.server.receive(ack);
        return peers;
    }

    private static Peers peers() throws Exception {
        Inet4Address clientAddress = ipv4("192.0.2.1");
        Inet4Address serverAddress = ipv4("198.51.100.2");
        ConnectionConfig clientConfig = new ConnectionConfig(
                clientAddress,
                serverAddress,
                40_001,
                40_002,
                SequenceNumber32.of(CLIENT_ISN),
                32_768);
        ConnectionConfig serverConfig = new ConnectionConfig(
                serverAddress,
                clientAddress,
                40_002,
                40_001,
                SequenceNumber32.of(SERVER_ISN),
                32_768);
        TcpConnectionLifecycle client = new TcpConnectionLifecycle(clientConfig);
        TcpConnectionLifecycle server = new TcpConnectionLifecycle(serverConfig);
        server.listen();
        return new Peers(
                clientConfig, serverConfig, client, server);
    }

    private static TcpSegment only(LifecycleResult result) {
        List<TcpSegment> transmissions = result.transmissions();
        assertEquals(1, transmissions.size());
        return transmissions.get(0);
    }

    private static Inet4Address ipv4(String address) throws Exception {
        return (Inet4Address) InetAddress.getByName(address);
    }

    private record Peers(
            ConnectionConfig clientConfig,
            ConnectionConfig serverConfig,
            TcpConnectionLifecycle client,
            TcpConnectionLifecycle server) {
    }
}
