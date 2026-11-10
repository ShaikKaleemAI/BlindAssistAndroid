package com.kaleem.blindassist

import org.junit.Assert.*
import org.junit.Test

class I18nAndMetricsTest {
    @Test fun allLanguagesHaveTheSameKeysAndPlaceholders() {
        val en = I18n.S.getValue("en")
        val ph = Regex("\\{(\\w+)\\}")
        for (lang in listOf("hi", "te")) {
            val m = I18n.S.getValue(lang)
            assertEquals("keys differ for $lang", en.keys, m.keys)
            for ((k, v) in en) assertEquals("$lang/$k placeholders", ph.findAll(v).map { it.value }.toSet(), ph.findAll(m.getValue(k)).map { it.value }.toSet())
        }
    }

    @Test fun neverClaimsThePathIsClearOrSafe() {
        val en = I18n.S.getValue("en")
        for ((k, v) in en) {
            val s = v.lowercase()
            assertFalse("$k: '$v'", s.contains("path is clear") || s.contains("way is clear") || s.contains("all clear"))
            if ("safe" in s) assertEquals("only the 'nothing' message may mention safety", "nothing", k)
        }
        assertTrue(I18n.t("en", "nothing").contains("not"))
    }

    @Test fun distanceIsSpokenInStepsAndDirectionIsLocalised() {
        assertEquals("1 step", I18n.dist("en", 0.75)); assertEquals("2 steps", I18n.dist("en", 1.5))
        assertEquals("4 అడుగులు", I18n.dist("te", 3.0))
        assertEquals("12 o'clock", I18n.direction("en", 0.0)); assertEquals("सामने", I18n.direction("hi", 0.0))
        assertEquals("बाएं", I18n.direction("hi", -25.0))
    }

    @Test fun frameStatsFpsAndPercentiles() {
        val s = FrameStats()
        for (i in 0..10) s.record(i * 0.1, 10.0)
        assertEquals(10.0, s.fps(), 0.01)
        val p = FrameStats()
        for (i in 1..100) p.record(i * 0.1, i.toDouble())
        assertEquals(50.0, p.percentile(50.0), 1e-9); assertEquals(95.0, p.percentile(95.0), 1e-9)
    }

    @Test fun frameStatsWindowIsBounded() {
        val s = FrameStats(5)
        for (i in 0 until 10) s.record(i.toDouble(), 1.0)
        assertEquals(5, s.count())
    }

    @Test fun csvEscapeDoublesQuotes() { assertEquals("\"a\"\"b\"", csvEscape("a\"b")) }
}
