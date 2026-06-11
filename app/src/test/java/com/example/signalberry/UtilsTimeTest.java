package com.example.signalberry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/**
 * The conversation-list time ladder: now → Xm → HH:mm → EEE → M/d → M/d/yy.
 * Anchored to System.currentTimeMillis(), so tests assert relative rungs.
 */
public class UtilsTimeTest {

    @Test public void zeroIsBlank() {
        assertEquals("", Utils.formatShortTime(0));
    }

    @Test public void underAMinuteIsNow() {
        assertEquals("now", Utils.formatShortTime(System.currentTimeMillis() - 5_000));
    }

    @Test public void minutesAgoShowsMinutes() {
        assertEquals("18m", Utils.formatShortTime(System.currentTimeMillis() - 18 * 60_000L));
    }

    @Test public void earlierTodayShowsClock() {
        // 2h ago, but force it to still be "today" by clamping to this morning
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 30);
        c.set(Calendar.SECOND, 0);
        long t = c.getTimeInMillis();
        // only meaningful when it's past ~10:30 and the clip is >1h old
        if (System.currentTimeMillis() - t > 3600_000) {
            assertEquals("09:30", Utils.formatShortTime(t));
        }
    }

    @Test public void withinWeekShowsWeekday() {
        String s = Utils.formatShortTime(System.currentTimeMillis() - 3L * 24 * 3600_000);
        assertEquals("3-day-old should be a 3-letter weekday", 3, s.length());
        assertTrue("alphabetic weekday", Character.isLetter(s.charAt(0)));
    }

    @Test public void olderThisYearShowsNumericDate() {
        String s = Utils.formatShortTime(System.currentTimeMillis() - 30L * 24 * 3600_000);
        assertTrue("contains a slash: " + s, s.contains("/"));
        assertFalse("no year for this-year dates: " + s, s.matches(".*/\\d{2}$") && s.length() > 5 && countSlashes(s) == 2);
    }

    @Test public void previousYearAppendsTwoDigitYear() {
        String s = Utils.formatShortTime(System.currentTimeMillis() - 400L * 24 * 3600_000);
        assertEquals("over a year old should carry M/d/yy", 2, countSlashes(s));
    }

    private static int countSlashes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '/') n++;
        return n;
    }

    private static void assertFalse(String m, boolean b) { assertTrue(m, !b); }
}
