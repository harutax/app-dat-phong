package fakebank.vn;

import org.springframework.http.HttpStatus;

class BankException extends RuntimeException {
    final HttpStatus status;
    final String code;

    BankException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
