package com.whxinna.userplatform.nfc;

import android.util.Log;

import com.whxinna.userplatform.crypto.CRC8;
import com.whxinna.userplatform.crypto.KeyDerivation;
import com.whxinna.userplatform.crypto.RC4;
import com.whxinna.userplatform.model.DoorResponse;

public final class NfcCommandBuilder {

    private static final String TAG = "ZL_NfcCmdBuilder";
    private static final byte HEADER = (byte) 0xB1;
    private static final byte SUBCOMMAND = 0x0D;
    private static final int ENCRYPTED_LEN = 36;

    private NfcCommandBuilder() {}

    public static byte[] buildCommand(int deviceId, String credentialHex, int projectId) {
        byte[] credential = hexToBytes(credentialHex);
        if (credential.length != 32) {
            Log.e(TAG, "Invalid credential length: " + credential.length);
            return null;
        }

        byte[] pidBytes = littleEndianInt(projectId);
        byte[] plainData = new byte[ENCRYPTED_LEN];
        System.arraycopy(pidBytes, 0, plainData, 0, 4);
        System.arraycopy(credential, 0, plainData, 4, 32);

        byte[] key = KeyDerivation.deriveKey(deviceId);
        byte[] encrypted = RC4.encrypt(plainData, key);

        byte[] command = new byte[40];
        command[0] = HEADER;
        command[1] = SUBCOMMAND;
        command[2] = (byte) ENCRYPTED_LEN;
        System.arraycopy(encrypted, 0, command, 3, ENCRYPTED_LEN);

        byte[] crcInput = new byte[2 + ENCRYPTED_LEN];
        crcInput[0] = SUBCOMMAND;
        crcInput[1] = (byte) ENCRYPTED_LEN;
        System.arraycopy(plainData, 0, crcInput, 2, ENCRYPTED_LEN);
        command[39] = (byte) CRC8.compute(crcInput);

        return command;
    }

    public static DoorResponse parseResponse(int deviceId, byte[] frame) {
        if (frame == null || frame.length < 4) {
            return new DoorResponse(0, 0, -1, false, new byte[0]);
        }

        int commandId = frame[1] & 0xFF;
        int payloadSize = frame[2] & 0xFF;

        if (3 + payloadSize > frame.length) {
            return new DoorResponse(commandId, payloadSize, -1, false, frame);
        }

        byte[] encryptedPayload = new byte[payloadSize];
        System.arraycopy(frame, 3, encryptedPayload, 0, payloadSize);

        byte[] key = KeyDerivation.deriveKey(deviceId);
        byte[] plainPayload = RC4.encrypt(encryptedPayload, key);

        int resultCode = -1;
        if (plainPayload.length > 3) {
            resultCode = plainPayload[3] & 0xFF;
        }

        boolean crcValid = false;
        if (3 + payloadSize < frame.length) {
            byte[] crcInput = new byte[2 + payloadSize];
            crcInput[0] = frame[1];
            crcInput[1] = frame[2];
            System.arraycopy(plainPayload, 0, crcInput, 2, payloadSize);
            int expectedCrc = CRC8.compute(crcInput);
            crcValid = (frame[3 + payloadSize] & 0xFF) == expectedCrc;
        }

        boolean success = resultCode == 0 || resultCode == 23;
        String updatedCredentialHex = null;
        if (success && plainPayload.length >= 36) {
            byte[] credBytes = new byte[32];
            System.arraycopy(plainPayload, 4, credBytes, 0, 32);
            boolean hasNonZero = false;
            for (byte b : credBytes) {
                if (b != 0) { hasNonZero = true; break; }
            }
            if (hasNonZero) {
                updatedCredentialHex = bytesToHex(credBytes).toUpperCase();
            }
        }

        return new DoorResponse(commandId, payloadSize, resultCode, crcValid, plainPayload, updatedCredentialHex);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] littleEndianInt(int value) {
        return new byte[]{
            (byte) (value & 0xFF),
            (byte) ((value >> 8) & 0xFF),
            (byte) ((value >> 16) & 0xFF),
            (byte) ((value >> 24) & 0xFF)
        };
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        hex = hex.replaceAll("\\s+", "");
        if (hex.length() % 2 != 0) {
            hex = "0" + hex;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
