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
        Files.write(input, expected);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> server = executor.submit(() -> {
                try {
                    TcpCli.run(new String[] {
                        "server",
                        Integer.toString(serverPort),
                        Integer.toString(clientPort),
                        output.toString()
                    });
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            TcpCli.run(new String[] {
                "client",
                Integer.toString(clientPort),
                Integer.toString(serverPort),
                input.toString()
            });
            server.get();
        } finally {
            executor.shutdownNow();
        }

        assertArrayEquals(expected, Files.readAllBytes(output));
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
    }

    private static int availableUdpPort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
