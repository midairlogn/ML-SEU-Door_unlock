package com.midairlogn.seudoorunlock.model;

public class BleResponse {

    public final int commandType;
    public final byte[] plainData;
    public final boolean crcValid;

    public BleResponse(int commandType, byte[] plainData, boolean crcValid) {
        this.commandType = commandType;
        this.plainData = plainData;
        this.crcValid = crcValid;
    }

    public int getResultCode() {
        if (plainData == null || plainData.length == 0) return -1;
        return plainData[0] & 0xFF;
    }

    public boolean isSuccess() {
        return crcValid && (getResultCode() == 0 || getResultCode() == 23);
    }

    public int getRandom() {
        if (plainData.length < 8) return 0;
        return (plainData[4] & 0xFF)
             | ((plainData[5] & 0xFF) << 8)
             | ((plainData[6] & 0xFF) << 16)
             | ((plainData[7] & 0xFF) << 24);
    }

    public String getErrorMessage() {
        if (!crcValid) {
            return "Response CRC check failed";
        }
        int code = getResultCode();
        switch (code) {
            case 0: return "Success";
            case 1: return "CRC check error";
            case 2: return "ISN random error";
            case 3: return "Busy";
            case 7: return "No key set";
            case 10: return "Authentication failed";
            case 11: return "Invalid parameter";
            case 12: return "User deleted";
            case 13: return "Random verification failed";
            case 14: return "Project ID mismatch";
            case 20: return "User info not found";
            case 21: return "Key type mismatch";
            case 22: return "Admin random mismatch";
            case 23: return "Door already open";
            case 24: return "Expired";
            case 25: return "Offline count exhausted";
            case 26: return "Credential update failed";
            case 27: return "Need to update key";
            case 255: return "Unknown command";
            default: return "Error code: " + code;
        }
    }
}
