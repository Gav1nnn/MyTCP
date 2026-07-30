package com.ouc.tcp.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpCliTest {
    @Test
    void transfersFileThroughStandaloneClientAndServer(@TempDir Path temp)
            throws Exception {
        int clientPort = availableUdpPort();
        int serverPort = availableUdpPort();
        byte[] expected = new byte[75_321];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 29);
        }
        Path input = temp.resolve("input.bin");
        Path output = temp.resolve("output.bin");
        Path clientTrace = temp.resolve("client.trace");
        Path serverTrace = temp.resolve("server.trace");
        Files.write(input, expected);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> server = executor.submit(() -> {
                try {
                    TcpCli.run(new String[] {
                        "server",
                        Integer.toString(serverPort),
                        Integer.toString(clientPort),
                        output.toString(),
                        "--trace",
                        serverTrace.toString()
                    });
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            TcpCli.run(new String[] {
                "client",
                Integer.toString(clientPort),
                Integer.toString(serverPort),
                input.toString(),
                "--trace",
                clientTrace.toString(),
                "--fault",
                "3=drop,4=duplicate,5=reorder,9=corrupt"
            });
            server.get();
        } finally {
            executor.shutdownNow();
        }

        assertArrayEquals(expected, Files.readAllBytes(output));
        String clientEvents = Files.readString(clientTrace);
        String serverEvents = Files.readString(serverTrace);
        assertTrue(clientEvents.contains(
                "event=state from=CLOSED to=SYN_SENT"));
        assertTrue(clientEvents.contains("flags=SYN"));
        assertTrue(clientEvents.contains("event=sender state=ESTABLISHED"));
        assertTrue(clientEvents.contains("snd_una="));
        assertTrue(clientEvents.contains(
                "event=fault transmission=3 action=DROP"));
        assertTrue(clientEvents.contains(
                "event=fault transmission=4 action=DUPLICATE"));
        assertTrue(clientEvents.contains(
                "event=fault transmission=5 action=REORDER"));
        assertTrue(clientEvents.contains(
                "event=fault transmission=9 action=CORRUPT"));
        assertTrue(serverEvents.contains(
                "event=state from=LISTEN to=SYN_RECEIVED"));
        assertTrue(serverEvents.contains("direction=RECEIVE"));
    }

    @Test
    void rejectsInvalidCommandLine() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TcpCli.run(new String[] {"client"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> TcpCli.run(new String[] {
                    "unknown", "19001", "19002", "file"
                }));
        assertThrows(
                IllegalArgumentException.class,
                () -> TcpCli.run(new String[] {
                    "server", "0", "19002", "file"
                }));
        assertThrows(
                IllegalArgumentException.class,
                () -> TcpCli.run(new String[] {
                    "server",
                    "19001",
                    "19002",
                    "file",
                    "--fault",
                    "1=unknown"
                }));
    }

    private static int availableUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
