package com.midairlogn.seudoorunlock.crypto;

public final class KeyDerivation {

    private static final long DELTA0 = 0x9E3779B9L;
    private static final long DELTA_STEP = 0x12345678L;

    private static final byte[] KEY_CONST = {
        (byte) 172, (byte) 171, (byte) 188, (byte) -38,
        (byte) 174, (byte) -65, (byte) 20, (byte) 38,
        (byte) 53, (byte) 66, (byte) 84, (byte) 101,
        (byte) 114, (byte) -121, (byte) -110, (byte) 1
    };

    private KeyDerivation() {}

    public static byte[] deriveKey(int deviceId) {
        byte[] idBytes = new byte[4];
        idBytes[0] = (byte) (deviceId & 0xFF);
        idBytes[1] = (byte) ((deviceId >> 8) & 0xFF);
        idBytes[2] = (byte) ((deviceId >> 16) & 0xFF);
        idBytes[3] = (byte) ((deviceId >> 24) & 0xFF);

        long value = ((idBytes[0] & 0xFFL) << 24)
                   | ((idBytes[1] & 0xFFL) << 16)
                   | ((idBytes[2] & 0xFFL) << 8)
                   | (idBytes[3] & 0xFFL);
        value &= 0xFFFFFFFFL;

        long[] words = new long[4];
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            words[i] = (KEY_CONST[offset] & 0xFFL)
                      | ((KEY_CONST[offset + 1] & 0xFFL) << 8)
                      | ((KEY_CONST[offset + 2] & 0xFFL) << 16)
                      | ((KEY_CONST[offset + 3] & 0xFFL) << 24);
        }

        for (int i = 0; i < 4; i++) {
            long delta = (DELTA0 + DELTA_STEP * i) & 0xFFFFFFFFL;
            long left = ((value & delta) + i) & 0xFFFFFFFFL;
            long middle = ((value | delta) - 2L * i) & 0xFFFFFFFFL;
            long right = ((0xFFFFFFFFL ^ value) ^ delta) & 0xFFFFFFFFL;
            long t = (left + middle - right) & 0xFFFFFFFFL;
            words[i] = words[i] ^ t;
        }

        byte[] key = new byte[16];
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            key[offset]     = (byte) ((words[i] >> 24) & 0xFF);
            key[offset + 1] = (byte) ((words[i] >> 16) & 0xFF);
            key[offset + 2] = (byte) ((words[i] >> 8) & 0xFF);
            key[offset + 3] = (byte) (words[i] & 0xFF);
        }

        return key;
    }
}
