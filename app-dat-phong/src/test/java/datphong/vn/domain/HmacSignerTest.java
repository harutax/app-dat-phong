package datphong.vn.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HmacSignerTest {

    @Test
    void signAndVerify() {
        String sig = HmacSigner.sign("secret-key-1234567890", "1700000000.{\"a\":1}");
        assertTrue(HmacSigner.verify("secret-key-1234567890", "1700000000.{\"a\":1}", sig));
        assertTrue(HmacSigner.verify("secret-key-1234567890", "1700000000.{\"a\":1}", sig.toUpperCase()));
    }

    @Test
    void tamperedPayloadOrWrongSecretRejected() {
        String sig = HmacSigner.sign("secret-key-1234567890", "payload");
        assertFalse(HmacSigner.verify("secret-key-1234567890", "payload2", sig));
        assertFalse(HmacSigner.verify("other-secret", "payload", sig));
        assertFalse(HmacSigner.verify("secret-key-1234567890", "payload", null));
        assertFalse(HmacSigner.verify("", "payload", sig));
    }
}
