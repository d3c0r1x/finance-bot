package com.decorix.finance.core.api;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finance.receipts.storage.provider", havingValue = "filesystem", matchIfMissing = true)
public class FileSystemReceiptObjectStorage implements ReceiptObjectStorage {
    private final Path root;

    @Autowired
    public FileSystemReceiptObjectStorage(@Value("${finance.receipts.storage.filesystem-root:./var/receipt-objects}") String root) {
        try {
            Path configured = Path.of(root).toAbsolutePath().normalize();
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException error) {
            throw new IllegalStateException("Receipt storage root is unavailable", error);
        }
    }

    FileSystemReceiptObjectStorage(Path root) {
        try {
            Path configured = root.toAbsolutePath().normalize();
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException error) {
            throw new IllegalStateException("Receipt storage root is unavailable", error);
        }
    }

    @Override
    public void put(String key, byte[] body, String contentType) {
        if (body == null || body.length == 0 || !supportedContentType(contentType)) {
            throw new IllegalArgumentException("Receipt object is invalid");
        }
        Path destination = resolve(key, true);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(destination.getParent(), ".receipt-", ".tmp");
            Files.write(temporary, body, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Receipt object could not be stored", error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    @Override
    public byte[] get(String key) {
        Path path = resolve(key, false);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Receipt object not found");
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new IllegalStateException("Receipt object could not be read", error);
        }
    }

    @Override
    public void delete(String key) {
        Path path = resolve(key, false);
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            throw new IllegalStateException("Receipt object could not be deleted", error);
        }
    }

    private Path resolve(String key, boolean createParents) {
        ReceiptObjectKeys.validate(key);
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Receipt object key is invalid");
        Path parent = resolved.getParent();
        try {
            if (createParents) Files.createDirectories(parent);
            Path current = root;
            Path relativeParent = root.relativize(parent);
            for (Path segment : relativeParent) {
                current = current.resolve(segment);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("Receipt object key is invalid");
                }
            }
            if (!parent.toRealPath().startsWith(root)) throw new IllegalArgumentException("Receipt object key is invalid");
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
                throw new IllegalArgumentException("Receipt object key is invalid");
            }
            return resolved;
        } catch (IOException error) {
            throw new IllegalStateException("Receipt storage directory is unavailable", error);
        }
    }

    private static boolean supportedContentType(String contentType) {
        return "image/png".equals(contentType) || "image/jpeg".equals(contentType);
    }
}
