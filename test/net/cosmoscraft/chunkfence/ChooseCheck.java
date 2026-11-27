package net.cosmoscraft.chunkfence;

public final class ChooseCheck {
    static int fails;
    static void eq(HeightSync.Heights got, int min, int max, int logical, String what) {
        boolean ok = got.minY() == min && got.maxY() == max && got.logical() == logical;
        if (!ok) fails++;
        System.out.println((ok ? "  PASS " : "  FAIL ") + what + " -> " + got);
    }
    public static void main(String[] a) throws Exception {
        var vNether = HeightSync.vanilla("1.21.6", HeightSync.Dim.NETHER);
        var vOver = HeightSync.vanilla("1.21.6", HeightSync.Dim.OVERWORLD);
        var irisNether = new HeightSync.Heights(-64, 448, 448);
        var irisOver = new HeightSync.Heights(-64, 1024, 1024);
        var storedVanillaNether = new HeightProbe.Result(0, 15, 10, 0);
        var storedTallNether = new HeightProbe.Result(-4, 27, 10, 0);
        var storedTallOver = new HeightProbe.Result(-4, 63, 10, 0);
        eq(HeightSync.choose("auto", HeightSync.Dim.NETHER, vNether, storedVanillaNether, irisNether, null), 0, 256, 128,
            "auto: nether saved at vanilla height stays vanilla even if the Iris pack says 448");
        eq(HeightSync.choose("auto", HeightSync.Dim.NETHER, vNether, storedTallNether, irisNether, null), -64, 448, 448,
            "auto: nether saved at Iris height gets the Iris height");
        eq(HeightSync.choose("auto", HeightSync.Dim.OVERWORLD, vOver, storedTallOver, null, null), -64, 1024, 1088,
            "auto without Iris: height from saved chunks");
        eq(HeightSync.choose("iris", HeightSync.Dim.OVERWORLD, vOver, storedTallOver, new HeightSync.Heights(-64, 320, 384), null), -64, 1024, 384,
            "iris mode never cuts below the saved chunks");
        eq(HeightSync.choose("manual", HeightSync.Dim.OVERWORLD, vOver, null, null, new HeightSync.Heights(-128, 512, 512)), -128, 512, 512,
            "manual heights used when there are no saved chunks");
        System.out.println(HeightSync.pickVersion("1.21.6", HeightSync.bundledVersions()) + " / " + HeightSync.pickVersion("1.21.12", HeightSync.bundledVersions())
            + " / " + HeightSync.pickVersion("26.4", HeightSync.bundledVersions()) + " / " + HeightSync.pickVersion("1.20.4", HeightSync.bundledVersions()));
        System.out.println(fails == 0 ? "OK" : fails + " FAILURES");
        System.exit(fails);
    }
}
