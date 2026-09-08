package com.zhiyun.crypto;

import com.zhiyun.config.ZhiyunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 用户 API Key 入库加密。日志与接口均不得打印明文。 */
@Component
public class SecretBox {
    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(ZhiyunProperties properties) {
        this.key = new SecretKeySpec(sha256(properties.getJwt().getSecret()), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(packed);
        } catch (Exception e) {
            throw new IllegalStateException("无法保存密钥", e);
        }
    }

    public String decrypt(String blob) {
        if (blob == null || blob.isBlank()) {
            return "";
        }
        try {
            byte[] packed = Base64.getDecoder().decode(blob);
            if (packed.length < 13) {
                return "";
            }
            byte[] iv = new byte[12];
            byte[] ct = new byte[packed.length - 12];
            System.arraycopy(packed, 0, iv, 0, 12);
            System.arraycopy(packed, 12, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("user key decrypt failed");
            return "";
        }
    }

    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String raw = key.trim();
        String suffix = raw.length() <= 4 ? raw : raw.substring(raw.length() - 4);
        if (raw.startsWith("sk-")) {
            return "sk-****" + suffix;
        }
        return "****" + suffix;
    }

    public static String suffix(String key) {
        if (key == null || key.length() < 4) {
            return key == null ? "" : key;
        }
        return key.substring(key.length() - 4);
    }

    private static byte[] sha256(String secret) {
        try {
            String seed = secret == null ? "zhiyun" : secret;
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
