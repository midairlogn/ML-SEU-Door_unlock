package com.whxinna.userplatform.crypto;

public final class RC4 {

    private RC4() {}

    public static byte[] encrypt(byte[] data, byte[] key) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = i;
        }

        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xFF)) & 0xFF;
            int tmp = s[i];
            s[i] = s[j];
            s[j] = tmp;
        }

        byte[] output = new byte[data.length];
        int x = 0, y = 0;
        for (int k = 0; k < data.length; k++) {
            x = (x + 1) & 0xFF;
            y = (y + s[x]) & 0xFF;
            int tmp = s[x];
            s[x] = s[y];
            s[y] = tmp;
            output[k] = (byte) (data[k] ^ s[(s[x] + s[y]) & 0xFF]);
        }

        return output;
    }
}
