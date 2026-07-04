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
            case 23: return "Door already open";
            case 27: return "Credential Expired";
            case 10: return "Authentication Failed";
            case 11: return "Invalid Parameter";
            default: return "Unknown error (" + code + ")";
        }
    }
}
