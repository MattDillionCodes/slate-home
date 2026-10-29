package com.slatehome.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SkyTest {

    @Test fun `maps every WMO code group`() {
        assertEquals(Sky.Clear, Sky.fromWmo(0))
        assertEquals(Sky.PartlyCloudy, Sky.fromWmo(2))
        assertEquals(Sky.Cloudy, Sky.fromWmo(3))
        assertEquals(Sky.Fog, Sky.fromWmo(45))
        assertEquals(Sky.Drizzle, Sky.fromWmo(53))
        assertEquals(Sky.Rain, Sky.fromWmo(63))
        assertEquals(Sky.Rain, Sky.fromWmo(81))
        assertEquals(Sky.Snow, Sky.fromWmo(73))
        assertEquals(Sky.Snow, Sky.fromWmo(86))
        assertEquals(Sky.Storm, Sky.fromWmo(95))
    }

    @Test fun `unknown codes fall back to cloudy`() {
        assertEquals(Sky.Cloudy, Sky.fromWmo(42))
        assertEquals(Sky.Cloudy, Sky.fromWmo(-1))
    }

    @Test fun `night label differs only for clear skies`() {
        assertEquals("Sunny", Sky.Clear.dayLabel)
        assertEquals("Clear", Sky.Clear.nightLabel)
        assertEquals(Sky.Rain.dayLabel, Sky.Rain.nightLabel)
    }
}
