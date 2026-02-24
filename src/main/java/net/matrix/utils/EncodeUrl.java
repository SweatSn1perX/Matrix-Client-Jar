package net.matrix.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class EncodeUrl {
    private static byte[] getDynamicKey() {
        try {
            String seed = String.class.getName() + Math.PI + System.class.getSimpleName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(hash, 0, key, 0, 16);
            return key;
        } catch (Exception e) {
            return new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }; // fallback
        }
    }

    public static void main(String[] args) {
        String url = System.getenv("MATRIX_AUTH_URL"); // URL removed for security — set via environment variable
        byte[] sk = getDynamicKey();
        byte[] urlBytes = url.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[urlBytes.length];

        for (int i = 0; i < urlBytes.length; i++) {
            encoded[i] = (byte) (urlBytes[i] ^ sk[i % sk.length]);
        }

        System.out.print("    private static final byte[] _d1 = {\n            ");
        int half = encoded.length / 2;
        for (int i = 0; i < half; i++) {
            System.out.print(encoded[i] + (i == half - 1 ? "" : ", "));
        }
        System.out.println("\n    };");

        System.out.print("    private static final byte[] _d2 = {\n            ");
        for (int i = half; i < encoded.length; i++) {
            System.out.print(encoded[i] + (i == encoded.length - 1 ? "" : ", "));
        }
        System.out.println("\n    };");
    }
}
