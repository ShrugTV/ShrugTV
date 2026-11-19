package com.shrug.shrugtv.ui.tv.clips

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** The text helpers of the clips page. */
class ClipsFormatTest {
    @Test fun durationUnderAnHour() {
        assertEquals("0:18", ClipsFormat.duration(18))
        assertEquals("1:05", ClipsFormat.duration(65))
        assertEquals("0:00", ClipsFormat.duration(0))
        assertEquals("0:00", ClipsFormat.duration(-3))
    }

    @Test fun durationOverAnHour() {
        assertEquals("1:02:03", ClipsFormat.duration(3723))
    }

    @Test fun compactCountEnglish() {
        assertEquals("950", ClipsFormat.compactCount(950, Locale.US))
        assertEquals("1.2K", ClipsFormat.compactCount(1234, Locale.US))
        assertEquals("2K", ClipsFormat.compactCount(2000, Locale.US))
        assertEquals("28K", ClipsFormat.compactCount(28271, Locale.US))
        assertEquals("1.2M", ClipsFormat.compactCount(1_234_567, Locale.US))
    }

    @Test fun compactCountGermanDecimalComma() {
        assertEquals("1,2K", ClipsFormat.compactCount(1234, Locale.GERMANY))
    }
}
