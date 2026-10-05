package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ClamAvReceiptScannerTest {
    @Test
    void sendsClamAvInstreamProtocolAndAcceptsCleanResult() throws Exception {
        byte[] image = new byte[] {1, 2, 3, 4};
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var received = CompletableFuture.supplyAsync(() -> readRequestAndReply(server, image, "stream: OK\0"));
            var scanner = new ClamAvReceiptScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));

            assertEquals(ClamAvReceiptScanner.ScanResult.CLEAN, scanner.scan(image));
            assertTrue(received.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void reportsPositiveMalwareSignatureAsInfected() throws Exception {
        byte[] image = new byte[] {5, 6};
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> readRequestAndReply(server, image, "stream: Eicar-Test-Signature FOUND\0"));
            var scanner = new ClamAvReceiptScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));

            assertEquals(ClamAvReceiptScanner.ScanResult.INFECTED, scanner.scan(image));
        }
    }

    private static boolean readRequestAndReply(ServerSocket server, byte[] expected, String response) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(2000);
            var input = new DataInputStream(socket.getInputStream());
            var output = new DataOutputStream(socket.getOutputStream());
            ByteArrayAccumulator command = new ByteArrayAccumulator();
            int value;
            while ((value = input.readUnsignedByte()) != 0) command.add((byte) value);
            if (!"zINSTREAM".equals(new String(command.bytes(), java.nio.charset.StandardCharsets.US_ASCII))) return false;
            int size = input.readInt();
            byte[] content = input.readNBytes(size);
            if (size != expected.length || !Arrays.equals(content, expected) || input.readInt() != 0) return false;
            output.write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.flush();
            return true;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class ByteArrayAccumulator {
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        void add(byte value) { output.write(value); }
        byte[] bytes() { return output.toByteArray(); }
    }
}
