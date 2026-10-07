package com.decorix.finance.core.api;

import java.time.Duration;

@FunctionalInterface
public interface ExportDownloadSigner {
    String sign(String objectKey, Duration lifetime);
}
