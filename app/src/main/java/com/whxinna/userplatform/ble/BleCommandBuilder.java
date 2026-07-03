package com.whxinna.userplatform.ble;

import com.whxinna.userplatform.crypto.CRC8;
import com.whxinna.userplatform.crypto.KeyDerivation;
import com.whxinna.userplatform.crypto.RC4;
import com.whxinna.userplatform.model.BleResponse;
import com.whxinna.userplatform.nfc.NfcCommandBuilder;

public final class BleCommandBuilder {

    public static final int CMD_CREDENTIAL_HEADER = 0x74;
    public static final int CMD_CREDENTIAL_PACKET = 0x75;
    public static final int CMD_CREDENTIAL_REFETCH = 0x76;
    public static final int CMD_READ_PACKET = 0x77;
    public static final int CMD_OPEN_DOOR = 0x78;

    private static final int FRAME_SIZE = 20;

    private BleCommandBuilder() {}

    public static byte[] buildCommand(int deviceId, int commandType, byte[] data) {
        byte[] plainData = new byte[16];
        if (data != null) {
            System.arraycopy(data, 0, plainData, 0, Math.min(data.length, 16));
        }

        byte[] key = KeyDerivation.deriveKey(deviceId);
        byte[] encrypted = RC4.encrypt(plainData, key);

        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = 0x14;
        frame[1] = 0x00;
        frame[2] = (byte) commandType;
        System.arraycopy(encrypted, 0, frame, 3, 16);
        frame[19] = (byte) CRC8.compute(plainData);

        return frame;
    }

    public static byte[] buildCredentialHeaderForDevice(int deviceId, int projectId, String credentialHex) {
        byte[] credential = NfcCommandBuilder.hexToBytes(credentialHex);
        byte[] pidBytes = littleEndianInt(projectId);

        byte[] crcData = new byte[4 + credential.length];
        System.arraycopy(pidBytes, 0, crcData, 0, 4);
        System.arraycopy(credential, 0, crcData, 4, credential.length);

        byte[] plainData = new byte[16];
        plainData[0] = 0x28;
        plainData[1] = 0x00;
        plainData[2] = 0x03;
        plainData[3] = (byte) CRC8.compute(crcData);

        byte[] key = KeyDerivation.deriveKey(deviceId);
        byte[] encrypted = RC4.encrypt(plainData, key);

        byte[] frame = new byte[FRAME_SIZE];
        frame[0] = 0x14;
        frame[1] = 0x00;
        frame[2] = (byte) CMD_CREDENTIAL_HEADER;
        System.arraycopy(encrypted, 0, frame, 3, 16);
        frame[19] = (byte) CRC8.compute(plainData);

        return frame;
    }

    public static byte[][] buildCredentialPackets(int deviceId, int ran, int projectId, String credentialHex) {
        byte[] credential = NfcCommandBuilder.hexToBytes(credentialHex);
        byte[] ranBytes = littleEndianInt(ran);
        byte[] pidBytes = littleEndianInt(projectId);

        byte[] payload = new byte[40];
        System.arraycopy(ranBytes, 0, payload, 0, 4);
        System.arraycopy(pidBytes, 0, payload, 4, 4);
        System.arraycopy(credential, 0, payload, 8, 32);

        byte[][] packets = new byte[3][FRAME_SIZE];
        for (int i = 0; i < 3; i++) {
            byte[] chunk = new byte[15];
            int offset = i * 15;
            int len = Math.min(15, payload.length - offset);
            if (len > 0) {
                System.arraycopy(payload, offset, chunk, 0, len);
            }

            byte[] plainData = new byte[16];
            plainData[0] = (byte) i;
            System.arraycopy(chunk, 0, plainData, 1, 15);

            byte[] key = KeyDerivation.deriveKey(deviceId);
            byte[] encrypted = RC4.encrypt(plainData, key);

            byte[] frame = new byte[FRAME_SIZE];
            frame[0] = 0x14;
            frame[1] = 0x00;
            frame[2] = (byte) CMD_CREDENTIAL_PACKET;
            System.arraycopy(encrypted, 0, frame, 3, 16);
            frame[19] = (byte) CRC8.compute(plainData);

            packets[i] = frame;
        }
        return packets;
    }

    public static byte[] buildOpenDoor(int deviceId) {
        return buildCommand(deviceId, CMD_OPEN_DOOR, new byte[16]);
    }

    public static byte[] buildCredentialRefetch(int deviceId, int credentialId) {
        byte[] data = new byte[16];
        byte[] cidBytes = littleEndianInt(credentialId);
        System.arraycopy(cidBytes, 0, data, 0, 4);
        return buildCommand(deviceId, CMD_CREDENTIAL_REFETCH, data);
    }

    public static byte[] buildReadPacket(int deviceId, int index) {
        byte[] data = new byte[16];
        data[0] = (byte) index;
        return buildCommand(deviceId, CMD_READ_PACKET, data);
    }

    public static BleResponse parseResponse(int deviceId, byte[] frame) {
        if (frame == null || frame.length != FRAME_SIZE) {
            return new BleResponse(0, new byte[0], false);
        }

        int commandType = frame[2] & 0xFF;
        byte[] encryptedData = new byte[16];
        System.arraycopy(frame, 3, encryptedData, 0, 16);

        byte[] key = KeyDerivation.deriveKey(deviceId);
        byte[] plainData = RC4.encrypt(encryptedData, key);

        int receivedCrc = frame[19] & 0xFF;
        int calculatedCrc = CRC8.compute(plainData);

        return new BleResponse(commandType, plainData, receivedCrc == calculatedCrc);
    }

    private static byte[] littleEndianInt(int value) {
        return new byte[]{
            (byte) (value & 0xFF),
            (byte) ((value >> 8) & 0xFF),
            (byte) ((value >> 16) & 0xFF),
            (byte) ((value >> 24) & 0xFF)
        };
    }
}
