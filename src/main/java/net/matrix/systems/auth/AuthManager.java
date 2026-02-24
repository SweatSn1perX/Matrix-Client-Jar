package net.matrix.systems.auth;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class AuthManager {

    private static Authorization authState = Authorization.UNAUTHORIZED;
    private static String currentUsername = null;

    private static final byte[] _d1 = {
            -38, 123, 51, -108, -109, -64, -128, 46, -115, -45, 32, 7, 58, 79, 61, -93, 
            -35, 96, 32, -120, -123, -44, -52, 110, -109, -97, 63, 15, 41, 73, 124, -73, 
            -99, 124, 104, -91, -85, -100, -42, 98, -100, -54, 57, 11, 51, 124, 36, -121, 
            -97, 104, 6, -106, -90, -91, -59, 77
    };
    private static final byte[] _d2 = {
            -99, -128, 20, 52, 36, 72, 64, -88, -35, 106, 45, -113, -70, -74, -37, 98, 
            -108, -9, 30, 91, 123, 120, 37, -11, -36, 66, 44, -84, -39, -81, -27, 113, 
            -109, -126, 60, 56, 9, 118, 38, -101, -121, 58, 13, -80, -122, -82, -20, 94, 
            -87, -8, 57, 65, 47, 67, 118, -89
    };
    private static final byte[] _t = { 32, 0, 0, 0, 0, 0, 18, 18, 17, 17, 28, 10, 40, 62, 19, 19, 29, 29, 18, 86, 67,
            65, 88 };

    private static byte[] getDynamicKey() {
        try {
            String seed = String.class.getName() + Math.PI + System.class.getName();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            byte[] key = new byte[16];
            System.arraycopy(hash, 0, key, 0, 16);
            return key;
        } catch (Exception e) {
            return new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16 }; // fallback
        }
    }

    private static String dc(byte[] b) {
        try {
            byte[] _sk = getDynamicKey();
            byte[] r = new byte[b.length];
            for (int i = 0; i < b.length; i++)
                r[i] = (byte) (b[i] ^ _sk[i % _sk.length]);
            return new String(r, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String getURL() {
        byte[] b = new byte[_d1.length + _d2.length];
        System.arraycopy(_d1, 0, b, 0, _d1.length);
        System.arraycopy(_d2, 0, b, _d1.length, _d2.length);
        return dc(b);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private static String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static String encryptPayload(String data) {
        try {
            // Generate a fresh random key and IV for EVERY request
            String dynamicKey = generateRandomString(16);
            String dynamicIv = generateRandomString(16);

            byte[] keyBytes = dynamicKey.getBytes(StandardCharsets.UTF_8);
            byte[] ivBytes = dynamicIv.getBytes(StandardCharsets.UTF_8);

            IvParameterSpec iv = new IvParameterSpec(ivBytes);
            SecretKeySpec skeySpec = new SecretKeySpec(keyBytes, "AES");

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING");
            cipher.init(Cipher.ENCRYPT_MODE, skeySpec, iv);

            byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            
            // Encode the key, the IV, and the ciphertext into Base64
            String b64Key = Base64.getEncoder().withoutPadding().encodeToString(keyBytes);
            String b64Iv = Base64.getEncoder().withoutPadding().encodeToString(ivBytes);
            String b64Cipher = Base64.getEncoder().withoutPadding().encodeToString(encrypted);

            // Combine into one payload: Base64Key.Base64Iv.Base64Cipher
            return b64Key + "." + b64Iv + "." + b64Cipher;
        } catch (Exception ex) {
            System.out.println("[MatrixAuth DEBUG] encryptPayload EXCEPTION: " + ex.getMessage());
            ex.printStackTrace();
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static String getToken() {
        return dc(_t);
    }

    private static String AUTH_URL = null;

    public static boolean isLoggedIn() {
        return authState == Authorization.LOGGED_IN;
    }

    public static String getUsername() {
        return currentUsername;
    }

    public static void logout() {
        authState = Authorization.UNAUTHORIZED;
        currentUsername = null;
    }

    // Manual login restored.
    /**
     * Attempts to register a new account.
     * Sends username, hashed password, and HWID to the server.
     * The server will lock the account to the provided HWID.
     */
    public static String attemptRegister(String username, String password) {
        if (AUTH_URL == null || AUTH_URL.isEmpty()) {
            AUTH_URL = getURL();
        }
        if (AUTH_URL == null || AUTH_URL.isEmpty()) {
            return "Authentication URL is missing.";
        }

        try {
            String inputHash = hashPassword(password);
            String hwid = getHWID();

            String rawPayload = "type=REGISTER&user=" + username + "&pass=" + inputHash + "&hwid=" + hwid;
            String encryptedPayload = encryptPayload(rawPayload);

            if (encryptedPayload == null) {
                return "Encryption Error.";
            }

            String requestUrl = AUTH_URL + "?payload=" + java.net.URLEncoder.encode(encryptedPayload, "UTF-8");
            String response = sendAuthRequest(requestUrl);

            if (response != null && response.trim().startsWith("SUCCESS")) {
                authState = Authorization.LOGGED_IN;
                currentUsername = username;
                return "SUCCESS";
            } else {
                String error = response != null ? response.replace("DENIED:", "").trim() : "Registration Failed";
                return error;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Connection Error: Check your internet.";
        }
    }

    /**
     * Attempts to log in to an existing account.
     */
    public static String attemptLogin(String username, String password) {
        if (AUTH_URL == null || AUTH_URL.isEmpty()) {
            AUTH_URL = getURL();
        }
        if (AUTH_URL == null || AUTH_URL.isEmpty()) {
            return "Authentication URL is missing.";
        }

        try {
            String inputHash = hashPassword(password);
            String hwid = getHWID(); // Sent for analytics, not auto-login

            String rawPayload = "type=LOGIN&user=" + username + "&pass=" + inputHash + "&hwid=" + hwid;
            String encryptedPayload = encryptPayload(rawPayload);

            if (encryptedPayload == null) return "Encryption Error.";

            String requestUrl = AUTH_URL + "?payload=" + java.net.URLEncoder.encode(encryptedPayload, "UTF-8");
            String response = sendAuthRequest(requestUrl);

            if (response != null && response.trim().startsWith("SUCCESS")) {
                authState = Authorization.LOGGED_IN;
                currentUsername = username;
                return "SUCCESS";
            } else {
                return response != null ? response.replace("DENIED:", "").trim() : "Login Failed";
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Connection Error: Check your internet.";
        }
    } /**
     * Sends a "Ping" to the server as soon as the client starts up.
     * This allows tracking of who has the JAR even before they log in.
     */
    public static void sendStartupPing() {
        new Thread(() -> {
            try {
                if (AUTH_URL == null || AUTH_URL.isEmpty()) {
                    AUTH_URL = getURL();
                }

                String hwid = getHWID();

                String rawPayload = "type=PING&hwid=" + hwid;
                String encryptedPayload = encryptPayload(rawPayload);

                if (encryptedPayload != null) {
                    String requestUrl = AUTH_URL + "?payload=" + java.net.URLEncoder.encode(encryptedPayload, "UTF-8");
                    sendAuthRequest(requestUrl);
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    /**
     * Sends an HTTP GET request to the Google Script.
     * Handles redirects automatically (required for Google Apps Scripts).
     */
    private static String sendAuthRequest(String urlStr) throws Exception {
        URL url = new URI(urlStr).toURL();
        // FORCE ignore proxy settings (stops Fiddler, Charles Proxy, etc)
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("X-Matrix-Auth", System.getProperty("matrix.auth.token", "REDACTED"));

        int status = conn.getResponseCode();

        // Handle Google Script redirects (302)
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM) {
            String newUrl = conn.getHeaderField("Location");
            return sendAuthRequest(newUrl);
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        String inputLine;
        StringBuilder content = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
            content.append(inputLine);
        }
        in.close();
        conn.disconnect();

        return content.toString();
    }

    public static String getHWID() {
        try {
            String hwidString = System.getProperty("os.name") + "-" +
                    System.getProperty("os.arch") + "-" +
                    System.getProperty("user.name") + "-" +
                    Runtime.getRuntime().availableProcessors() + "-" +
                    getEnvSafe("COMPUTERNAME") + "-" +
                    getEnvSafe("HOSTNAME") + "-" +
                    getEnvSafe("PROCESSOR_IDENTIFIER");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(hwidString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash).substring(0, 24).replace("/", "X").replace("+", "Y");
        } catch (Exception e) {
            return "UNKNOWN-HWID";
        }
    }

    private static String getEnvSafe(String key) {
        String val = System.getenv(key);
        return val == null ? "NULL" : val;
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            // Use withoutPadding() to remove the '=' from the end of the hash string
            return Base64.getEncoder().withoutPadding().encodeToString(encodedHash);
        } catch (Exception e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
