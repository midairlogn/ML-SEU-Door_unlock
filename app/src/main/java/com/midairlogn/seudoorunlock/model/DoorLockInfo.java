package com.midairlogn.seudoorunlock.model;

import org.json.JSONException;
import org.json.JSONObject;

public class DoorLockInfo {

    public final Accommodation accommodation;
    public final DoorLock doorLock;

    public DoorLockInfo(Accommodation accommodation, DoorLock doorLock) {
        this.accommodation = accommodation;
        this.doorLock = doorLock;
    }

    public static DoorLockInfo fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);

        Accommodation acc = null;
        if (root.has("accommodation")) {
            JSONObject accJson = root.getJSONObject("accommodation");
            acc = new Accommodation(
                accJson.optString("building_name", ""),
                accJson.optString("floor_name", ""),
                accJson.optString("room_name", "")
            );
        }

        DoorLock lock = null;
        JSONObject lockJson = root.has("door_lock") ? root.getJSONObject("door_lock") : root;
        String deviceIdStr = findString(lockJson, "device_id", "deviceId");
        String credential = findString(lockJson, "credential", "chain_key", "chainKey");
        String credentialIdStr = findString(lockJson, "credential_id", "credentialId");

        if (!deviceIdStr.isEmpty()) {
            int deviceId = 0;
            try { deviceId = Integer.parseInt(deviceIdStr); } catch (NumberFormatException ignored) {}
            int credentialId = 0;
            try { credentialId = Integer.parseInt(credentialIdStr); } catch (NumberFormatException ignored) {}

            lock = new DoorLock(
                deviceId,
                findString(lockJson, "ble_name", "bleName"),
                findString(lockJson, "ble_mac", "bleMac"),
                lockJson.optDouble("battery_level", 100.0),
                credential,
                credentialId
            );
        }

        return new DoorLockInfo(acc, lock);
    }

    private static String findString(JSONObject obj, String... keys) {
        for (String key : keys) {
            String val = obj.optString(key, "");
            if (!val.isEmpty()) return val;
        }
        return "";
    }

    public static class Accommodation {
        public final String buildingName;
        public final String floorName;
        public final String roomName;

        public Accommodation(String buildingName, String floorName, String roomName) {
            this.buildingName = buildingName;
            this.floorName = floorName;
            this.roomName = roomName;
        }

        public String getDisplayName() {
            return buildingName + " " + floorName + " " + roomName;
        }
    }

    public static class DoorLock {
        public final int deviceId;
        public final String bleName;
        public final String bleMac;
        public final double batteryLevel;
        public final String credential;
        public final int credentialId;

        public DoorLock(int deviceId, String bleName, String bleMac,
                        double batteryLevel, String credential, int credentialId) {
            this.deviceId = deviceId;
            this.bleName = bleName;
            this.bleMac = bleMac;
            this.batteryLevel = batteryLevel;
            this.credential = credential;
            this.credentialId = credentialId;
        }
    }
}
