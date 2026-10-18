package jainingfei.site.ai.agent.infrastructure.adapter.port;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jainingfei.site.ai.agent.domain.security.adapter.port.ICredentialCipher;
import jainingfei.site.ai.agent.domain.security.model.valobj.properties.SecurityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 凭证加解密——SSH 密码与私钥不再以明文落库。
 *
 * <p>密文格式：{@code enc:v1:} + Base64(12 字节 IV || 密文 || 16 字节 GCM Tag)。
 * 前缀同时承担"算法版本"与"是否本实现产出"两个职责，历史明文值原样返回，
 * 下次保存时自动升级为密文。
 *
 * <p>密钥来源（按优先级）：
 * <ol>
 *   <li>{@code ai.security.credential-key}（Base64 / hex / ≥32 字节原文）</li>
 *   <li>未配置时用固定开发密钥派生——<b>仅限本地开发</b>，启动日志 WARN</li>
 * </ol>
 * 生产请通过环境变量 {@code AI_SECURITY_CREDENTIAL_KEY} 注入，并妥善备份：
 * 密钥丢失意味着所有已保存凭证无法解密（需要重新录入）。
 */
@Slf4j
@Component
public class AesGcmCredentialCipher implements ICredentialCipher {

    /** 算法与版本标识 */
    public static final String PREFIX = "enc:v1:";

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 未配置主密钥时的开发默认种子（固定值，保证重启后仍能解密） */
    private static final String DEV_SEED = "ai-ssh/debug-only-credential-key/please-override-in-production";

    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private SecurityProperties securityProperties;

    private SecretKeySpec secretKey;

    @PostConstruct
    void init() {
        String configured = securityProperties.getCredentialKey();
        byte[] keyBytes = resolveKeyBytes(configured);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        if (securityProperties.hasConfiguredCredentialKey()) {
            log.info("凭证加密已启用 | 算法:AES-256-GCM | 密钥指纹:{}", fingerprint(keyBytes));
        } else {
            log.warn("凭证加密使用开发默认密钥（未配置 ai.security.credential-key）；"
                    + "生产环境请通过 AI_SECURITY_CREDENTIAL_KEY 注入并妥善备份");
            log.info("凭证加密已启用 | 算法:AES-256-GCM | 密钥指纹:{}（开发默认）", fingerprint(keyBytes));
        }
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return plainText;
        }
        if (isEncrypted(plainText)) {
            return plainText;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("凭证加密失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        if (!isEncrypted(cipherText)) {
            // 历史明文：原样返回，下次保存时由 encrypt 升级
            return cipherText;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            if (payload.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(encrypted);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "凭证解密失败：主密钥与密文不匹配（请检查 AI_SECURITY_CREDENTIAL_KEY，或重新录入凭证）", e);
        }
    }

    @Override
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    // ==================== 内部 ====================

    private static byte[] resolveKeyBytes(String configured) {
        byte[] raw = null;
        if (configured != null && !configured.isBlank()) {
            String value = configured.trim();
            // 1) 64 位 hex（优先于 Base64：hex 字符本身也是合法 Base64 字符）
            if (value.matches("(?i)[0-9a-f]{64}")) {
                raw = hexToBytes(value);
            } else {
                // 2) Base64（标准或 URL 安全）
                try {
                    raw = Base64.getDecoder().decode(value);
                } catch (IllegalArgumentException ignored) {
                    try {
                        raw = Base64.getUrlDecoder().decode(value);
                    } catch (IllegalArgumentException ignoredToo) {
                        // 3) 原文
                        raw = value.getBytes(StandardCharsets.UTF_8);
                    }
                }
            }
        }
        if (raw == null) {
            raw = DEV_SEED.getBytes(StandardCharsets.UTF_8);
        }
        // 任意长度输入 → 固定 256 位密钥（与 SHA-256 派生）
        return sha256(raw);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 SHA-256: " + e.getMessage(), e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return out;
    }

    /** 密钥指纹（仅用于日志/诊断，不可逆） */
    private static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 密钥位数（供诊断接口展示） */
    public int keyBits() {
        return secretKey.getEncoded().length * 8;
    }
}
