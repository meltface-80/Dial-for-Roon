package com.roondial

import com.roondial.ui.SpokenCommand
import com.roondial.ui.SpokenCommand.Amount
import com.roondial.ui.SpokenCommand.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenCommandTest {

    private fun parse(spoken: String) = SpokenCommand.parse(spoken, isPlaying = true)
    private fun parseSilent(spoken: String) = SpokenCommand.parse(spoken, isPlaying = false)

    // ------------------------------------------------------------- the bug

    @Test
    fun volumeCommandsAreCommandsNotSearches() {
        // The reported bug: this searched the library and played whatever came
        // closest to the words.
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("turn down volume"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("turn up volume"))
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("turn down the volume"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("turn the volume up"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("louder"))
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("quieter"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("turn it up"))
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("turn it down"))
    }

    @Test
    fun transportCommandsAreCommands() {
        assertEquals(Intent.Pause, parse("pause"))
        assertEquals(Intent.Pause, parse("stop the music"))
        assertEquals(Intent.Play, parse("resume"))
        assertEquals(Intent.Next, parse("next track"))
        assertEquals(Intent.Next, parse("skip"))
        assertEquals(Intent.Previous, parse("go back"))
        assertEquals(Intent.Mute, parse("mute"))
        assertEquals(Intent.Unmute, parse("unmute"))
    }

    // -------------------------------------------------- content stays content

    @Test
    fun aPlayVerbAlwaysMeansSearchEvenWhenTheRestSoundsLikeACommand() {
        // The rule that keeps records called Volume 1 or Stop reachable.
        assertEquals(Intent.Search("Volume 1"), parse("play Volume 1"))
        assertEquals(Intent.Search("Stop"), parse("play Stop"))
        assertEquals(Intent.Search("Louder"), parse("play Louder"))
        assertEquals(Intent.Search("Turn It Up"), parse("play Turn It Up"))
        assertEquals(Intent.Search("Quiet"), parse("play the album Quiet"))
    }

    @Test
    fun ordinaryRequestsSearch() {
        assertEquals(Intent.Search("Iron Maiden"), parse("play Iron Maiden"))
        assertEquals(Intent.Search("Agnes Obel"), parse("play Agnes Obel"))
        assertEquals(Intent.Search("Agnes Obel"), parse("Agnes Obel"))
        assertEquals(Intent.Search("Massive Attack"), parse("put on some Massive Attack"))
        assertEquals(Intent.Search("Radiohead"), parse("listen to Radiohead"))
        assertEquals(Intent.Search("The Number of the Beast"), parse("play The Number of the Beast"))
    }

    @Test
    fun keepsTheCasingOfWhatWasSaidForTheSearch() {
        // Roon is searching a library of proper nouns; lowercase is only for
        // matching commands.
        assertEquals(Intent.Search("Neil Young"), parse("play Neil Young"))
    }

    @Test
    fun aLongerPhraseThatMerelyContainsACommandWordIsStillASearch() {
        assertEquals(Intent.Search("Stop Making Sense"), parse("play Stop Making Sense"))
        assertEquals(Intent.Search("Pause"), parse("play Pause"))
    }

    // ---------------------------------------------------------------- extras

    @Test
    fun understandsHowMuch() {
        assertEquals(Intent.VolumeUp(Amount.SMALL), parse("turn it up a bit"))
        assertEquals(Intent.VolumeDown(Amount.SMALL), parse("turn the volume down a little"))
        assertEquals(Intent.VolumeUp(Amount.LARGE), parse("turn it up a lot"))
    }

    @Test
    fun ignoresPoliteness() {
        assertEquals(Intent.Pause, parse("please pause"))
        assertEquals(Intent.Next, parse("can you skip this"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("turn it up please"))
        assertEquals(Intent.Search("Iron Maiden"), parse("please play Iron Maiden"))
    }

    @Test
    fun copesWithHowARecogniserActuallyReturnsText() {
        // Lowercase, no punctuation, and the missing "the" the user reported.
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("turn down volume"))
        assertEquals(Intent.Pause, parse("Pause."))
        assertEquals(Intent.Next, parse("next!"))
    }

    @Test
    fun sayingNothingDoesNothing() {
        assertEquals(Intent.Nothing, parse(""))
        assertEquals(Intent.Nothing, parse("   "))
    }

    @Test
    fun aBarePlayVerbResumesRatherThanSearchingForNothing() {
        assertEquals(Intent.Play, parse("play"))
        assertEquals(Intent.Play, parse("play the music"))
    }

    // ------------------------------------ commands must not become playback

    @Test
    fun realTitlesThatSoundLikeCommandsAreNotCommands() {
        // Every one of these is a real record. Mistaking a command for one of
        // them replaces the queue and starts playing, which is the expensive
        // direction of this problem.
        assertEquals(Intent.Search("Turn Down for What"), parse("Turn Down for What"))
        assertEquals(Intent.Search("Next to Me"), parse("Next to Me"))
        assertEquals(Intent.Search("Louder Than Bombs"), parse("Louder Than Bombs"))
        assertEquals(Intent.Search("Stop Making Sense"), parse("Stop Making Sense"))
        assertEquals(Intent.Search("Pump Up the Volume"), parse("Pump Up the Volume"))
        assertEquals(Intent.Search("Turn Up the Radio"), parse("Turn Up the Radio"))
        assertEquals(Intent.Search("Quiet Is the New Loud"), parse("Quiet Is the New Loud"))
        assertEquals(Intent.Search("Skip to the Good Bit"), parse("Skip to the Good Bit"))
    }

    @Test
    fun commandsSurviveTheWordsARecogniserDrops() {
        // The vocabulary rule exists because exact phrases do not survive a
        // recogniser: articles vanish and word order moves.
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("turn down volume"))
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("turn the volume down"))
        assertEquals(Intent.VolumeDown(Amount.NORMAL), parse("volume down"))
        assertEquals(Intent.VolumeDown(Amount.SMALL), parse("turn it down a bit"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("turn up the sound"))
        assertEquals(Intent.Next, parse("next one"))
        assertEquals(Intent.Pause, parse("pause the music"))
    }

    @Test
    fun aBareWordIsACommandOnlyWhenThereIsSomethingToCommand() {
        // Nobody says "pause" to a silent room; but "Pause" is a Four Tet
        // album, and "Stop" is a Spice Girls single.
        assertEquals(Intent.Pause, parse("stop"))
        assertEquals(Intent.Search("Stop"), parseSilent("Stop"))
        assertEquals(Intent.Pause, parse("pause"))
        assertEquals(Intent.Search("Pause"), parseSilent("Pause"))
        assertEquals(Intent.Next, parse("next"))
        assertEquals(Intent.Search("Next"), parseSilent("Next"))
        assertEquals(Intent.VolumeUp(Amount.NORMAL), parse("louder"))
        assertEquals(Intent.Search("Louder"), parseSilent("Louder"))
    }

    @Test
    fun politenessIsStrippedOnlyAtTheEdges() {
        // "Please Please Me" and "Can You Feel It" are records; stripping
        // politeness from the middle of a title destroys it.
        assertEquals(Intent.Search("Please Please Me"), parse("play Please Please Me"))
        assertEquals(Intent.Search("Can You Feel It"), parse("play Can You Feel It"))
        assertEquals(Intent.Search("Please Mr Postman"), parse("play Please Mr Postman"))
    }

    @Test
    fun thankYouIsNeverTreatedAsPoliteness() {
        // "thank u, next" is a number one single, not gratitude followed by a
        // skip command.
        assertEquals(Intent.Search("thank u next"), parse("thank u next"))
        assertEquals(Intent.Search("thank you next"), parse("thank you next"))
    }

    @Test
    fun theShortPlayTransportFormsGoToTransport() {
        // "play next" is a documented transport phrase in the open grammars,
        // and the band Next is the cheaper thing to get wrong: a mistaken skip
        // costs one tap, a mistaken search replaces the queue. Anything longer
        // is unambiguous content again.
        assertEquals(Intent.Next, parse("play next"))
        assertEquals(Intent.Search("Next to Me"), parse("play Next to Me"))
        assertEquals(Intent.Search("Next"), parseSilent("Next"))
    }

    @Test
    fun transportPhrasesThatStartWithPlayAreStillTransport() {
        // Otherwise the play-verb rule sends these to the library as searches.
        assertEquals(Intent.Previous, parse("play the previous song"))
        assertEquals(Intent.Previous, parse("play the last track again"))
        assertEquals(Intent.Previous, parse("replay"))
        assertEquals(Intent.Next, parse("play the next track"))
    }

    @Test
    fun aPlayRequestWithNoContentStartsSomething() {
        assertEquals(Intent.Play, parse("play some music"))
        assertEquals(Intent.Play, parse("play something"))
    }

    @Test
    fun aBareDirectionIsNotAVolumeCommand() {
        // Neither open grammar surveyed accepts "up" alone as volume.
        assertEquals(Intent.Search("up"), parse("up"))
        assertEquals(Intent.Search("down"), parse("down"))
    }

    @Test
    fun toMeansAnAbsoluteLevelWhateverVerbPrecedesIt() {
        // "turn the volume down to 90 percent" sets 90; it does not subtract 90.
        assertEquals(Intent.VolumePercent(90), parse("turn the volume down to 90 percent"))
        assertEquals(Intent.VolumePercent(90), parse("increase the volume to 90 percent"))
        assertEquals(Intent.VolumePercent(50), parse("set the volume to 50 per cent"))
    }

    @Test
    fun aVolumeNumberNeedsAVerbAndAPreposition() {
        // "Volume Two" is She & Him, Sleep and Jay-Z; reading it as a level
        // would drop the output to near silence.
        assertEquals(Intent.Search("Volume Two"), parse("Volume Two"))
        assertEquals(Intent.Search("Vol 4"), parse("Vol 4"))
        assertEquals(Intent.Search("Volume Four"), parse("play Volume Four"))
        assertEquals(Intent.VolumePercent(40), parse("set the volume to 40"))
        assertEquals(Intent.VolumePercent(50), parse("set volume to fifty"))
    }

    @Test
    fun aPlayVerbBeatsEveryOtherRule() {
        assertEquals(Intent.Search("Pause"), parse("play Pause"))
        assertEquals(Intent.Search("Stop"), parse("play Stop"))
        assertEquals(Intent.Search("Mute"), parse("play Mute"))
        assertEquals(Intent.Search("Skip James"), parse("play Skip James"))
    }
}
