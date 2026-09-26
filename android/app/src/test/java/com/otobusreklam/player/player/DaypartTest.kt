package com.otobusreklam.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class DaypartTest {

    private fun at(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun `bos tanim gun boyu gecerli`() {
        assertTrue(Daypart.matches("", at(3)))
        assertTrue(Daypart.matches("   ", at(23, 59)))
    }

    @Test fun `normal aralik icinde`() {
        assertTrue(Daypart.matches("07:00-10:00", at(8, 30)))
    }

    @Test fun `normal aralik disinda`() {
        assertFalse(Daypart.matches("07:00-10:00", at(6, 59)))
        assertFalse(Daypart.matches("07:00-10:00", at(11)))
    }

    @Test fun `baslangic dahil bitis haric`() {
        // Aralik [start, end): bitis anini dahil etseydik ardisik araliklar
        // (07:00-10:00 ve 10:00-12:00) 10:00'da CAKISIRDI.
        assertTrue(Daypart.matches("07:00-10:00", at(7, 0)))
        assertFalse(Daypart.matches("07:00-10:00", at(10, 0)))
        assertTrue(Daypart.matches("10:00-12:00", at(10, 0)))
    }

    @Test fun `gece yarisini asan aralik`() {
        val spec = "22:00-02:00"
        assertTrue(Daypart.matches(spec, at(22, 0)))
        assertTrue(Daypart.matches(spec, at(23, 30)))
        assertTrue(Daypart.matches(spec, at(0, 15)))
        assertTrue(Daypart.matches(spec, at(1, 59)))
        assertFalse(Daypart.matches(spec, at(2, 0)))
        assertFalse(Daypart.matches(spec, at(12, 0)))
    }

    @Test fun `birden fazla aralik`() {
        val spec = "07:00-10:00,17:00-20:00"
        assertTrue(Daypart.matches(spec, at(8)))
        assertTrue(Daypart.matches(spec, at(18)))
        assertFalse(Daypart.matches(spec, at(12)))
        assertFalse(Daypart.matches(spec, at(21)))
    }

    @Test fun `bosluklu yazim kabul edilir`() {
        assertTrue(Daypart.matches(" 07:00 - 10:00 , 17:00-20:00 ", at(18)))
    }

    @Test fun `bozuk tanim reklami dusurmez`() {
        // Sahada bir yazim hatasi yuzunden ucretli reklamin hic donmemesi,
        // fazladan donmesinden daha pahali bir hatadir.
        assertTrue(Daypart.matches("sacmalik", at(12)))
        assertTrue(Daypart.matches("25:00-99:00", at(12)))
        assertTrue(Daypart.matches("07:00", at(12)))
    }

    @Test fun `ayni baslangic ve bitis gun boyu sayilir`() {
        assertTrue(Daypart.matches("09:00-09:00", at(3)))
    }
}
