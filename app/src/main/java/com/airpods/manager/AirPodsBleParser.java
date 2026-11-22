package com.airpods.manager;

import java.util.Arrays;

/**
 * Decoder for the public Apple Proximity Pairing BLE advertisement used by
 * AirPods Pro 3 (model bytes 0x27, 0x20).
 *
 * This deliberately reads only the public, unencrypted part of the packet.
 * The trailing encrypted bytes are ignored.
 */
public final class AirPodsBleParser {
    public static final int APPLE_COMPANY_ID = 0x004C;
    public static final int AIRPODS_PRO_3_MODEL = 0x2720;

    private AirPodsBleParser() { }

    public static Snapshot parse(byte[] payload) {
        if (payload == null || payload.length < 11) return null;
        if ((payload[0] & 0xFF) != 0x07) return null;
        if ((payload[1] & 0xFF) != 0x19) return null;

        int model = ((payload[3] & 0xFF) << 8) | (payload[4] & 0xFF);
        if (model != AIRPODS_PRO_3_MODEL) return null;

        int status = payload[5] & 0xFF;
        boolean primaryLeft = (status & 0x20) != 0;

        int podByte = payload[6] & 0xFF;
        int primaryBattery = decodeBatteryNibble((podByte >>> 4) & 0x0F);
        int secondaryBattery = decodeBatteryNibble(podByte & 0x0F);

        int leftBattery = primaryLeft ? primaryBattery : secondaryBattery;
        int rightBattery = primaryLeft ? secondaryBattery : primaryBattery;

        // Pro 3 observations place the case level in the LOW nibble and
        // charging flags in the HIGH nibble. This differs from older-model
        // packet descriptions, so this parser is intentionally model-specific.
        int caseAndFlags = payload[7] & 0xFF;
        int caseBattery = decodeBatteryNibble(caseAndFlags & 0x0F);
        int chargingFlags = (caseAndFlags >>> 4) & 0x0F;

        // Charging flags 0/1 refer to primary/secondary buds; bit 2 is case.
        boolean primaryCharging = (chargingFlags & 0x01) != 0;
        boolean secondaryCharging = (chargingFlags & 0x02) != 0;
        boolean leftCharging = primaryLeft ? primaryCharging : secondaryCharging;
        boolean rightCharging = primaryLeft ? secondaryCharging : primaryCharging;
        boolean caseCharging = (chargingFlags & 0x04) != 0;

        int lidByte = payload[8] & 0xFF;
        boolean lidClosed = (lidByte & 0x08) != 0;
        int connectionState = payload[10] & 0xFF;

        return new Snapshot(
                leftBattery,
                rightBattery,
                caseBattery,
                leftCharging,
                rightCharging,
                caseCharging,
                primaryLeft,
                lidClosed,
                connectionState,
                status,
                Arrays.copyOf(payload, payload.length)
        );
    }

    /**
     * Public BLE battery values are coarse estimates in 10-point steps.
     * 0x0..0xA map to 0..100; 0xB..0xE are reserved/over-range values
     * observed by some decoders and are conservatively clamped to 100.
     * 0xF means the component is not being reported.
     */
    public static int decodeBatteryNibble(int nibble) {
        if (nibble < 0 || nibble > 0x0F || nibble == 0x0F) return -1;
        if (nibble > 0x0A) return 100;
        return nibble * 10;
    }

    /**
     * Extract Apple manufacturer data from a raw BLE scan record. The raw
     * record is used as a fallback because some Android ROMs expose only one
     * manufacturer block per company ID through ScanRecord's SparseArray.
     */
    public static byte[] extractAppleManufacturerPayload(byte[] scanRecord) {
        if (scanRecord == null) return null;
        int index = 0;
        while (index < scanRecord.length) {
            int length = scanRecord[index] & 0xFF;
            if (length == 0) break;
            int end = index + 1 + length;
            if (end > scanRecord.length || length < 1) break;

            int type = scanRecord[index + 1] & 0xFF;
            if (type == 0xFF && length >= 3) {
                int companyId = (scanRecord[index + 2] & 0xFF)
                        | ((scanRecord[index + 3] & 0xFF) << 8);
                if (companyId == APPLE_COMPANY_ID) {
                    byte[] data = Arrays.copyOfRange(scanRecord, index + 4, end);
                    if (data.length > 0 && (data[0] & 0xFF) == 0x07) return data;
                }
            }
            index = end;
        }
        return null;
    }

    public static String connectionStateLabel(int state) {
        switch (state) {
            case 0x00: return "Disconnected";
            case 0x04: return "Idle";
            case 0x05: return "Music playing";
            case 0x06: return "Call";
            case 0x07: return "Ringing";
            case 0x09: return "Hanging up";
            default: return "Unknown";
        }
    }

    public static final class Snapshot {
        public final int leftBattery;
        public final int rightBattery;
        public final int caseBattery;
        public final boolean leftCharging;
        public final boolean rightCharging;
        public final boolean caseCharging;
        public final boolean primaryLeft;
        public final boolean lidClosed;
        public final int connectionState;
        public final int rawStatus;
        public final byte[] rawPayload;

        Snapshot(int leftBattery, int rightBattery, int caseBattery,
                 boolean leftCharging, boolean rightCharging, boolean caseCharging,
                 boolean primaryLeft, boolean lidClosed, int connectionState,
                 int rawStatus, byte[] rawPayload) {
            this.leftBattery = leftBattery;
            this.rightBattery = rightBattery;
            this.caseBattery = caseBattery;
            this.leftCharging = leftCharging;
            this.rightCharging = rightCharging;
            this.caseCharging = caseCharging;
            this.primaryLeft = primaryLeft;
            this.lidClosed = lidClosed;
            this.connectionState = connectionState;
            this.rawStatus = rawStatus;
            this.rawPayload = rawPayload;
        }
    }
}
