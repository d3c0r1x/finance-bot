package com.decorix.finance.core.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class BankImportParseException extends ResponseStatusException {
    private final String parserCode;

    public BankImportParseException(String parserCode, String safeMessage) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, safeMessage);
        this.parserCode = parserCode;
    }

    public String parserCode() {
        return parserCode;
    }
}
