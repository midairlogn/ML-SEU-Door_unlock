package com.midairlogn.seudoorunlock.model;

public class DoorResponse {

    public final int commandId;
    public final int payloadSize;
    public final int resultCode;
    public final boolean crcValid;
    public final byte[] rawPayload;
    public final String updatedCredentialHex;

    public DoorResponse(int commandId, int payloadSize, int resultCode,
                        boolean crcValid, byte[] rawPayload) {
        this(commandId, payloadSize, resultCode, crcValid, rawPayload, null);
    }

    public DoorResponse(int commandId, int payloadSize, int resultCode,
                        boolean crcValid, byte[] rawPayload, String updatedCredentialHex) {
        this.commandId = commandId;
        this.payloadSize = payloadSize;
        this.resultCode = resultCode;
        this.crcValid = crcValid;
        this.rawPayload = rawPayload;
        this.updatedCredentialHex = updatedCredentialHex;
    }

    public boolean isSuccess() {
        return resultCode == 0 || resultCode == 23;
    }

    public boolean isExpired() {
        return resultCode == 24 || resultCode == 27;
    }

    public String getErrorMessage() {
        switch (resultCode) {
            case 0: return "Success";
            case 1: return "CRC check error";
            case 2: return "ISN random error";
            case 3: return "Busy";
            case 7: return "No key set";
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
            default: return "Error code: " + resultCode;
        }
    }
}
