package com.decorix.finance.core.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ClamAvReceiptScanner implements ReceiptMalwareScanner {
    private final String host;
    private final int port;
    private final Duration timeout;

    @Autowired
    public ClamAvReceiptScanner(
            @Value("${finance.receipts.scanner.clamav.host:127.0.0.1}") String host,
            @Value("${finance.receipts.scanner.clamav.port:3310}") int port,
            @Value("${finance.receipts.scanner.clamav.timeout:PT10S}") Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public ScanResult scan(byte[] image) {
        if (image == null || image.length == 0 || image.length > ReceiptImageValidator.MAX_BYTES) {
            throw new IllegalArgumentException("Receipt image size is invalid");
        }
        int timeoutMillis = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            var output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            int offset = 0;
            while (offset < image.length) {
                int count = Math.min(64 * 1024, image.length - offset);
                output.writeInt(count);
                output.write(image, offset, count);
                offset += count;
            }
            output.writeInt(0);
            output.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            int next;
            while (true) {
                next = socket.getInputStream().read();
                if (next < 0) throw new IOException("ClamAV closed the response early");
                if (next == 0) break;
                if (response.size() >= 1024) throw new IOException("ClamAV response is too long");
                response.write(next);
            }
            String result = response.toString(StandardCharsets.US_ASCII);
            if (result.endsWith(" OK")) return ScanResult.CLEAN;
            if (result.contains(" FOUND")) return ScanResult.INFECTED;
            throw new IOException("ClamAV returned an invalid response");
        } catch (IOException error) {
            throw new IllegalStateException("Receipt malware scan is unavailable", error);
        }
    }

    public enum ScanResult { CLEAN, INFECTED }
}
