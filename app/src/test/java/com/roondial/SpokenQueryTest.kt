package com.roondial

import com.roondial.ui.SpokenQuery
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenQueryTest {

    @Test
    fun stripsTheVerbPeopleActuallySay() {
        assertEquals("Iron Maiden", SpokenQuery.clean("play Iron Maiden"))
        assertEquals("Iron Maiden", SpokenQuery.clean("Play Iron Maiden"))
        assertEquals("Mezzanine", SpokenQuery.clean("play the album Mezzanine"))
        assertEquals("Massive Attack", SpokenQuery.clean("put on some Massive Attack"))
        assertEquals("Radiohead", SpokenQuery.clean("listen to Radiohead"))
    }

    @Test
    fun prefersTheLongerPhrasingWhenBothMatch() {
        // "play some " must win over "play ", or the search keeps the word some.
        assertEquals("Neil Young", SpokenQuery.clean("play some Neil Young"))
        assertEquals("Neil Young", SpokenQuery.clean("play me some Neil Young"))
        assertEquals("Neil Young", SpokenQuery.clean("play some music by Neil Young"))
    }

    @Test
    fun leavesAPlainNameAlone() {
        assertEquals("Iron Maiden", SpokenQuery.clean("Iron Maiden"))
        assertEquals("The Number of the Beast", SpokenQuery.clean("The Number of the Beast"))
    }

    @Test
    fun doesNotEatAPlayInTheMiddleOfATitle() {
        assertEquals("Playing with Fire", SpokenQuery.clean("Playing with Fire"))
        assertEquals("Play", SpokenQuery.clean("Play"))
    }

    @Test
    fun tidiesTrailingPunctuationTheRecogniserAdds() {
        assertEquals("Iron Maiden", SpokenQuery.clean("play Iron Maiden."))
        assertEquals("Iron Maiden", SpokenQuery.clean("  play Iron Maiden?  "))
    }
}
