package jainingfei.site.ai.agent.domain.security.adapter.port;

/**
 * 凭证加解密端口——领域层只关心"能不能安全存取"，不关心算法实现。
 *
 * <p>实现约定（AES-256-GCM）：密文以 {@code enc:v1:} 前缀标识算法与版本；
 * 无前缀的历史值视为明文原样返回，便于存量数据平滑升级（下次保存时自动加密）。
 */
public interface ICredentialCipher {

    /**
     * 加密明文凭证。
     *
     * @param plainText 明文；null / 空串原样返回
     * @return 带版本前缀的密文
     */
    String encrypt(String plainText);

    /**
     * 解密密文凭证。
     *
     * @param cipherText 密文；null / 空串 / 无前缀历史值原样返回
     * @return 明文
     * @throws IllegalStateException 带前缀但密钥不匹配 / 密文损坏
     */
    String decrypt(String cipherText);

    /**
     * 判断值是否为本实现产出的密文。
     */
    boolean isEncrypted(String value);
}
