package com.decorix.finance.core.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

final class ReceiptPhotoMultipart {
    private ReceiptPhotoMultipart() {}

    static byte[] bytes(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > ReceiptImageValidator.MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image size is invalid");
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0 || bytes.length > ReceiptImageValidator.MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image size is invalid");
            }
            return bytes;
        } catch (java.io.IOException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipt image could not be read", error);
        }
    }
}
