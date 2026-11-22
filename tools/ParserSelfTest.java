import com.airpods.manager.AirPodsBleParser;

public final class ParserSelfTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] packet(int status, int pods, int caseAndFlags) {
        byte[] p = new byte[27];
        p[0] = 0x07;
        p[1] = 0x19;
        p[2] = 0x01;
        p[3] = 0x27;
        p[4] = 0x20;
        p[5] = (byte) status;
        p[6] = (byte) pods;
        p[7] = (byte) caseAndFlags;
        p[8] = 0x11;
        p[9] = 0x00;
        p[10] = 0x05;
        return p;
    }

    public static void main(String[] args) {
        AirPodsBleParser.Snapshot sample = AirPodsBleParser.parse(packet(0x0B, 0xA9, 0x8F));
        check(sample != null, "Pro 3 sample should parse");
        check(sample.rightBattery == 100, "right primary battery should be 100");
        check(sample.leftBattery == 90, "left secondary battery should be 90");
        check(sample.caseBattery == -1, "case 0xF should be unavailable");
        check(sample.connectionState == 0x05, "connection state should be music");

        AirPodsBleParser.Snapshot flipped = AirPodsBleParser.parse(packet(0x2B, 0x75, 0xD3));
        check(flipped != null, "flipped Pro 3 sample should parse");
        check(flipped.leftBattery == 70, "left primary battery should be 70");
        check(flipped.rightBattery == 50, "right secondary battery should be 50");
        check(flipped.caseBattery == 30, "case low nibble should be 30");
        check(flipped.leftCharging, "primary charging flag should map to left");
        check(flipped.caseCharging, "case charging flag should be set");
        check(!flipped.rightCharging, "secondary charging flag should be clear");

        AirPodsBleParser.Snapshot secondaryCharge = AirPodsBleParser.parse(packet(0x0B, 0xA9, 0x23));
        check(secondaryCharge != null && secondaryCharge.leftCharging,
                "secondary charge flag should map to the left bud when right is primary");

        check(AirPodsBleParser.decodeBatteryNibble(0x00) == 0, "0x0 should be 0%");
        check(AirPodsBleParser.decodeBatteryNibble(0x09) == 90, "0x9 should be 90%");
        check(AirPodsBleParser.decodeBatteryNibble(0x0A) == 100, "0xA should be 100%");
        check(AirPodsBleParser.decodeBatteryNibble(0x0B) == 100, "reserved high nibble should clamp to 100%");
        check(AirPodsBleParser.decodeBatteryNibble(0x0E) == 100, "0xE should clamp to 100%");
        check(AirPodsBleParser.decodeBatteryNibble(0x0F) == -1, "0xF should be unknown");
        check(AirPodsBleParser.decodeBatteryNibble(-1) == -1, "negative nibble should be rejected");
        check(AirPodsBleParser.parse(new byte[]{0x07, 0x19}) == null, "truncated packet should be rejected");
        byte[] wrongModel = packet(0x0B, 0xA9, 0x8F);
        wrongModel[3] = 0x14;
        check(AirPodsBleParser.parse(wrongModel) == null, "other models should be rejected");

        byte[] adv = new byte[]{
                0x02, 0x01, 0x06,
                0x1E, (byte) 0xFF, 0x4C, 0x00,
                0x07, 0x19, 0x01, 0x27, 0x20, 0x0B, (byte) 0xA9,
                (byte) 0x8F, 0x11, 0x00, 0x05,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] extracted = AirPodsBleParser.extractAppleManufacturerPayload(adv);
        check(extracted != null && extracted.length == 27, "raw BLE manufacturer data should extract");
        check(AirPodsBleParser.parse(extracted) != null, "extracted payload should parse");

        System.out.println("AirPods BLE parser self-test: PASS");
    }
}
