package jainingfei.site.ai.agent.test.infrastructure;

import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import jainingfei.site.ai.agent.infrastructure.adapter.port.AesGcmCredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 凭证加密单元测试——AES-256-GCM 往返、幂等、历史明文兼容与篡改检测。
 */
public class AesGcmCredentialCipherTest {

    private AesGcmCredentialCipher cipher;

    @BeforeEach
    public void setUp() {
        cipher = newCipher("unit-test-master-key-0123456789");
    }

    private AesGcmCredentialCipher newCipher(String key) {
        SecurityProperties properties = new SecurityProperties();
        properties.setCredentialKey(key);
        AesGcmCredentialCipher instance = new AesGcmCredentialCipher();
        ReflectionTestUtils.setField(instance, "securityProperties", properties);
        ReflectionTestUtils.invokeMethod(instance, "init");
        return instance;
    }

    @Test
    @DisplayName("加密后带版本前缀，可解密回原文")
    public void test_round_trip() {
        String plain = "p@ssw0rd-中文-🔐";
        String encrypted = cipher.encrypt(plain);

        assertTrue(encrypted.startsWith(AesGcmCredentialCipher.PREFIX));
        assertNotEquals(plain, encrypted);
        assertTrue(cipher.isEncrypted(encrypted));
        assertEquals(plain, cipher.decrypt(encrypted));
    }

    @Test
    @DisplayName("同一明文两次加密产生不同密文（随机 IV），但都能解密")
    public void test_randomized_iv() {
        String first = cipher.encrypt("same-secret");
        String second = cipher.encrypt("same-secret");

        assertNotEquals(first, second, "随机 IV 必须让密文不同");
        assertEquals("same-secret", cipher.decrypt(first));
        assertEquals("same-secret", cipher.decrypt(second));
    }

    @Test
    @DisplayName("重复加密幂等：已是密文不再套娃")
    public void test_idempotent_encrypt() {
        String once = cipher.encrypt("secret");
        String twice = cipher.encrypt(once);

        assertEquals(once, twice);
    }

    @Test
    @DisplayName("历史明文原样透传，便于平滑升级存量数据")
    public void test_legacy_plaintext_passthrough() {
        assertFalse(cipher.isEncrypted("legacy-plain-password"));
        assertEquals("legacy-plain-password", cipher.decrypt("legacy-plain-password"));
    }

    @Test
    @DisplayName("null / 空串原样返回")
    public void test_null_and_empty() {
        assertEquals(null, cipher.encrypt(null));
        assertEquals("", cipher.encrypt(""));
        assertEquals(null, cipher.decrypt(null));
        assertEquals("", cipher.decrypt(""));
    }

    @Test
    @DisplayName("不同主密钥无法解密：报出可操作错误")
    public void test_wrong_key_fails() {
        String encrypted = cipher.encrypt("top-secret");
        AesGcmCredentialCipher other = newCipher("another-master-key-abcdef");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> other.decrypt(encrypted));
        assertTrue(error.getMessage().contains("解密失败"));
    }

    @Test
    @DisplayName("密文被篡改时 GCM 校验失败")
    public void test_tampered_ciphertext_fails() {
        String encrypted = cipher.encrypt("top-secret");
        char last = encrypted.charAt(encrypted.length() - 1);
        String tampered = encrypted.substring(0, encrypted.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }
}
