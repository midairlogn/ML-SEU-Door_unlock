package com.midairlogn.seudoorunlock.crypto;

import java.security.SecureRandom;

/**
 * Centralized utility class for cryptographic operations and data conversions.
 */
public class CryptoUtils {

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Converts a byte array to a hexadecimal string.
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    /**
     * Converts a hexadecimal string to a byte array.
     */
    public static byte[] hexToBytes(String s) {
        if (s == null) return new byte[0];
        s = s.replaceAll("\\s+", "");
        if (s.isEmpty()) return new byte[0];
        if (s.length() % 2 != 0) {
            s = "0" + s;
        }
        byte[] data = new byte[s.length() / 2];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((Character.digit(s.charAt(i * 2), 16) << 4)
                              + Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return data;
    }

    /**
     * Generates a secure random nonce string of the specified length.
     */
    public static String generateNonce(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(SECURE_RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
