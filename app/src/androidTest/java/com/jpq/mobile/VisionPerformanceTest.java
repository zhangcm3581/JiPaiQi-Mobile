package com.jpq.mobile;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Same-input recognition is repeatable; timings are diagnostic, not a device speed promise. */
@RunWith(AndroidJUnit4.class)
public class VisionPerformanceTest {
    @Test public void repeatedFramesPreserveCardinalityAndIdentity() throws Exception {
        var fixtures = new ShisanshuiDeviceTest();
        try (Vision vision = new Vision(fixtures.pack())) {
            for (String name : new String[]{"predeal", "golden_round_4", "missing_12", "extra_14"}) {
                Bitmap bitmap = fixtures.image(name);
                try {
                    for (int repeat = 0; repeat < 3; repeat++) {
                        long before = System.nanoTime();
                        Vision.Frame frame = vision.analyze(bitmap, new double[]{0, 0, 1, 1});
                        var cards = FullVideoAuditTest.actual(frame);
                        int expected = name.equals("predeal") ? 0 : name.equals("missing_12") ? 12 : name.equals("extra_14") ? 14 : 13;
                        assertEquals(name + " repeat=" + repeat, expected, cards.size());
                        if (name.equals("golden_round_4")) assertEquals(FullVideoAuditTest.expected(4), cards);
                        System.out.println("recognition image=" + name + " repeat=" + repeat + " ms=" + (System.nanoTime() - before) / 1_000_000.0);
                    }
                } finally { bitmap.recycle(); }
            }
        }
    }
}
