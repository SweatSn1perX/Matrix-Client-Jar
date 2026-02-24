package net.matrix.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class StringDecryptor {
    private static byte[] KEY = null;

    private static void initKey() {
        if (KEY != null)
            return;
        try {
            String seed = String.class.getName() + Math.PI + System.class.getSimpleName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            KEY = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            KEY = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 };
        }
    }

    public static String decrypt(String encrypted) {
        initKey();
        try {
            byte[] bytes = Base64.getDecoder().decode(encrypted);
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (bytes[i] ^ KEY[i % KEY.length]);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encrypted; // Fallback if decryption fails (e.g., empty string or not base64)
        }
    }
}
