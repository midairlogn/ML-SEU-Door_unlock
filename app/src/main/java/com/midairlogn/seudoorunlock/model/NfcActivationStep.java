package com.midairlogn.seudoorunlock.model;

import org.json.JSONObject;

import java.util.List;

public class NfcActivationStep {

    public final JSONObject requestData;
    public final List<String> packets;
    public final String credentialId;
    public final String credentialHex;

    public NfcActivationStep(JSONObject requestData, List<String> packets,
                              String credentialId, String credentialHex) {
        this.requestData = requestData;
        this.packets = packets;
        this.credentialId = credentialId;
        this.credentialHex = credentialHex;
    }

    public boolean isComplete() {
        return packets == null || packets.isEmpty();
    }
}
