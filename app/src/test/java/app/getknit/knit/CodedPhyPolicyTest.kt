package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyMode
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.LinkPhy
import app.getknit.knit.mesh.bluetooth.PhyStepper
import app.getknit.knit.mesh.bluetooth.PhyStepper.Action
import app.getknit.knit.mesh.bluetooth.PhyStepper.StepUpHold
import app.getknit.knit.mesh.bluetooth.PhyTuning
import app.getknit.knit.mesh.bluetooth.PromotionPolicy
import app.getknit.knit.mesh.bluetooth.ScanPhys
import app.getknit.knit.mesh.link.LinkFraming
import app.getknit.knit.mesh.link.PaceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the BLE Coded PHY experiment (ADR 2026-10.yvn6): scoring, dial choice, and the link's PHY steps. */
class CodedPhyPolicyTest {
    private val tuning = PhyTuning()

    /** A stepper on [phy] (as its first read reported), with [reads] link-RSSI reads one second apart from t=0. */
    private fun stepper(
        phy: LinkPhy = LinkPhy.ONE_M,
        vararg reads: Int,
    ): PhyStepper =
        PhyStepper { tuning }.also { s ->
            s.onPhy(phy, succeeded = true, now = 0)
            reads.forEachIndexed { i, r -> s.onRssi(r, now = i * 1_000L) }
        }

    private fun sighting(
        rssi: Int,
        coded: Boolean,
    ) = BlePresenceTracker.Sighting("p", rssi, protoVersion = 1, capabilities = 0, psm = 128, digestCue = 0, coded = coded)

    // --- scoring ---

    @Test
    fun aCodedReadingIsCreditedOntoTheOneMScale() {
        assertEquals(-85.0, CodedPhyPolicy.effectiveRssi(null, -97.0)!!, 0.0)
        assertEquals(-80.0, CodedPhyPolicy.effectiveRssi(-80.0, -97.0)!!, 0.0) // the stronger wins
        assertEquals(-70.0, CodedPhyPolicy.effectiveRssi(-90.0, -82.0)!!, 0.0)
        assertEquals(-88.0, CodedPhyPolicy.effectiveRssi(-88.0, null)!!, 0.0) // the experiment off: unchanged
        assertNull(CodedPhyPolicy.effectiveRssi(null, null))
    }

    @Test
    fun aPeerHeardOnlyOnCodedAtMinus97IsPromotable() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-97, coded = true), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 13_000 - 7_000)
        t.onSighting(sighting(-97, coded = true), now = 13_000)
        val snap = t.snapshots(13_000).single()
        assertEquals(-85.0, snap.smoothedRssi, 0.0001)
        assertNull(snap.oneMSeenAgoMs)
        assertEquals(0L, snap.codedSeenAgoMs)
        // −85 clears PromotionConfig's −90 floor, which the raw −97 would not.
        assertEquals(listOf("p"), PromotionPolicy.decide(listOf(snap), emptyList(), emptySet()).promote)
    }

    @Test
    fun theTwoPhysKeepSeparateAverages() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-70, coded = false), now = 0)
        t.onSighting(sighting(-100, coded = true), now = 500)
        // The Coded reading never drags the 1M one down: −70 vs −100+12.
        assertEquals(-70.0, t.snapshots(500).single().smoothedRssi, 0.0001)
    }

    @Test
    fun aPhyNotHeardInTheLatestBurstStopsCounting() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-60, coded = false), now = 0) // close, on 1M
        t.onSighting(sighting(-100, coded = true), now = 7_000)
        t.onSighting(sighting(-100, coded = true), now = 14_000) // walked off: only Coded hears it now
        assertEquals(-88.0, t.snapshots(14_000).single().smoothedRssi, 0.0001)
    }

    @Test
    fun codedHitsSecondsApartAreContinuousPresence() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-97, coded = true), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 20_000) // a far peer's sparse Coded hits
        val snap = t.snapshots(20_000).single()
        assertEquals(20_000L, snap.dwellMs)
        assertEquals(listOf("p"), PromotionPolicy.decide(listOf(snap), emptyList(), emptySet()).promote)
    }

    @Test
    fun oneMHitsKeepTheirEightSecondGap() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-70, coded = false), now = 0)
        t.onSighting(sighting(-70, coded = false), now = 10_000)
        assertEquals(0L, t.snapshots(10_000).single().dwellMs)
    }

    @Test
    fun aCodedHitAfterTheCodedGapStartsOver() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-97, coded = true), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 40_000)
        assertEquals(0L, t.snapshots(40_000).single().dwellMs)
    }

    @Test
    fun onlyCodedAfterCodedGetsTheWideGap() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-70, coded = false), now = 0)
        t.onSighting(sighting(-97, coded = true), now = 20_000) // the last sighting was 1M: its 8 s gap applies
        assertEquals(0L, t.snapshots(20_000).single().dwellMs)
    }

    @Test
    fun theCodedCreditIsTunable() {
        assertEquals(-79.0, CodedPhyPolicy.effectiveRssi(null, -97.0, creditDb = 18.0)!!, 0.0)
        val t = BlePresenceTracker(codedCreditDb = { 18.0 })
        t.onSighting(sighting(-97, coded = true), now = 0)
        val snap = t.snapshots(0).single()
        assertEquals(-79.0, snap.smoothedRssi, 0.0001)
        assertEquals(-97.0, snap.rssiCoded!!, 0.0001) // the raw reading stays on its own scale
        assertNull(snap.rssi1m)
    }

    // --- dialing and admission ---

    @Test
    fun aDialPrefersAFreshOneMAdvert() {
        assertFalse(CodedPhyPolicy.dialCoded(codedLagMs = 1_000))
        assertTrue(CodedPhyPolicy.dialCoded(codedLagMs = 29_000))
        assertFalse(CodedPhyPolicy.dialCoded(codedLagMs = null))
    }

    @Test
    fun codedOnlyIsMoreThanEightSecondsOfOneMListening() {
        assertTrue(CodedPhyPolicy.codedOnly(codedLagMs = 8_001))
        assertFalse(CodedPhyPolicy.codedOnly(codedLagMs = 8_000))
        assertFalse(CodedPhyPolicy.codedOnly(codedLagMs = 0))
        assertFalse(CodedPhyPolicy.codedOnly(codedLagMs = null)) // no Coded hit in this presence
    }

    @Test
    fun aCodedOnlyWindowDoesNotMakeAClosePeerCodedOnly() {
        // The 2026-10-02 bench: phones 4 m apart, the scan alone, so every other window Coded-only. An all-PHY window
        // hears the peer on both PHYs; the Coded-only window after it hears Coded alone for 12 s, which on the wall
        // clock was a 12 s lag, and the dial went to the Coded address.
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        t.onSighting(sighting(-60, coded = false), now = 11_000)
        t.onSighting(sighting(-60, coded = true), now = 11_500)
        t.onOneMListening(false, 12_000) // then a 12 s gap
        t.onSighting(sighting(-60, coded = true), now = 24_500) // the Coded-only window
        t.onSighting(sighting(-60, coded = true), now = 35_500)
        val snap = t.snapshots(36_000).single()
        assertEquals(1_000L, snap.codedLagMs) // the all-PHY window's last second, nothing more
        assertFalse(CodedPhyPolicy.dialCoded(snap.codedLagMs))
        assertTrue(CodedPhyPolicy.sightedForAdmission(snap, codedOn = true))
    }

    @Test
    fun aPeerFirstHeardInACodedOnlyWindowIsNotCodedOnly() {
        // A bring-up's first window is Coded-only when the phone has no link: every peer is first heard there.
        val t = BlePresenceTracker()
        t.onSighting(sighting(-60, coded = true), now = 500)
        t.onSighting(sighting(-60, coded = true), now = 11_500)
        assertEquals(0L, t.snapshots(12_000).single().codedLagMs)
    }

    @Test
    fun aPeerWhose1MAdvertAnAllPhyWindowMissesIsCodedOnly() {
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        t.onSighting(sighting(-95, coded = false), now = 1_000) // walking off: the last 1M hit
        t.onSighting(sighting(-100, coded = true), now = 6_000)
        assertFalse(CodedPhyPolicy.codedOnly(t.snapshots(6_000).single().codedLagMs))
        t.onSighting(sighting(-100, coded = true), now = 10_000)
        assertEquals(9_000L, t.snapshots(10_000).single().codedLagMs)
        assertTrue(CodedPhyPolicy.codedOnly(t.snapshots(10_000).single().codedLagMs))
        // A 1M hit puts it back.
        t.onSighting(sighting(-94, coded = false), now = 11_000)
        assertFalse(CodedPhyPolicy.codedOnly(t.snapshots(11_000).single().codedLagMs))
    }

    @Test
    fun aFarPeerHeardOnlyInCodedOnlyWindowsBecomesCodedOnlyAfterAnAllPhyWindowMissesIt() {
        val t = BlePresenceTracker()
        t.onSighting(sighting(-100, coded = true), now = 5_000) // a Coded-only window
        t.onOneMListening(true, 24_000) // an all-PHY window hears nothing of it
        t.onOneMListening(false, 36_000)
        t.onSighting(sighting(-100, coded = true), now = 50_000) // the next Coded-only window
        val snap = t.snapshots(50_000).single()
        assertEquals(12_000L, snap.codedLagMs)
        assertTrue(CodedPhyPolicy.dialCoded(snap.codedLagMs))
        assertFalse(CodedPhyPolicy.sightedForAdmission(snap, codedOn = true))
    }

    @Test
    fun theLastOneMHitOutlivesThePresenceEntry() {
        // A screen-off scan: windows two minutes apart, so presence starts over every window. The peer was heard on 1M
        // when it was close; walked off, it is heard on Coded alone in the next all-PHY window.
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        t.onSighting(sighting(-70, coded = false), now = 4_000)
        t.onOneMListening(false, 8_000)
        t.onOneMListening(true, 128_000)
        t.onSighting(sighting(-100, coded = true), now = 128_500) // a fresh presence entry
        val snap = t.snapshots(128_500).single()
        assertEquals(0L, snap.dwellMs)
        assertEquals(4_500L, snap.codedLagMs) // 4 s left of the first window, half a second of this one
        t.onSighting(sighting(-100, coded = true), now = 132_500)
        assertTrue(CodedPhyPolicy.codedOnly(t.snapshots(132_500).single().codedLagMs))
    }

    @Test
    fun aClosePeerKeepsItsVerdictOnceItGoesQuiet() {
        // Lag, not age: a peer that went quiet keeps the verdict its last hits gave it, whatever the scan does after.
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        t.onSighting(sighting(-60, coded = false), now = 1_000)
        t.onSighting(sighting(-60, coded = true), now = 1_500)
        t.onOneMListening(false, 60_000)
        assertFalse(CodedPhyPolicy.codedOnly(t.snapshots(60_000).single().codedLagMs))
    }

    @Test
    fun clearStopsTheClockAndForgetsTheMarks() {
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        t.onSighting(sighting(-70, coded = false), now = 1_000)
        t.clear()
        t.onSighting(sighting(-100, coded = true), now = 30_000)
        assertEquals(0L, t.snapshots(30_000).single().codedLagMs)
    }

    private fun snapshot(codedLagMs: Long?) =
        BlePresenceTracker.Snapshot(
            nodeId = "p",
            protoVersion = 1,
            capabilities = 0,
            psm = 128,
            digestCue = 0,
            smoothedRssi = -95.0,
            dwellMs = 30_000,
            lastSeenAgoMs = 2_000,
            codedLagMs = codedLagMs,
        )

    @Test
    fun aDialerHeardOnCodedAloneIsAdmittedAsUnsighted() {
        val far = snapshot(codedLagMs = 20_000)
        assertFalse(CodedPhyPolicy.sightedForAdmission(far, codedOn = true))
        // The experiment off: judged by presence alone, as always (ADR 2026-09.shzv).
        assertTrue(CodedPhyPolicy.sightedForAdmission(far, codedOn = false))
        // Heard on 1M too: the old tie-break.
        assertTrue(CodedPhyPolicy.sightedForAdmission(snapshot(codedLagMs = 500), codedOn = true))
        assertTrue(CodedPhyPolicy.sightedForAdmission(snapshot(codedLagMs = null), codedOn = true))
        assertFalse(CodedPhyPolicy.sightedForAdmission(null, codedOn = true))
    }

    @Test
    fun aDropAtRangeMakesTheDialedSidesAdvertFast() {
        // "a" sorts below "b": "b" dials, so it is "a"'s advert that matters.
        assertTrue(CodedPhyPolicy.fastAdvertAfterDrop("a", "b", LinkPhy.CODED, linkRssi = -90, tuning = tuning))
        assertFalse(
            "the dialer's own advert is not what brings the link back",
            CodedPhyPolicy.fastAdvertAfterDrop("b", "a", LinkPhy.CODED, linkRssi = -90, tuning = tuning),
        )
        // On 1M at the step-down edge: it died before it could step.
        assertTrue(CodedPhyPolicy.fastAdvertAfterDrop("a", "b", LinkPhy.ONE_M, linkRssi = tuning.stepDownDbm, tuning = tuning))
        assertFalse(CodedPhyPolicy.fastAdvertAfterDrop("a", "b", LinkPhy.ONE_M, linkRssi = -60, tuning = tuning))
        assertFalse(CodedPhyPolicy.fastAdvertAfterDrop("a", "b", LinkPhy.TWO_M, linkRssi = null, tuning = tuning))
    }

    @Test
    fun advertIntervalsAreInStackUnits() {
        assertEquals(400, CodedPhyPolicy.advertIntervalUnits(250))
        assertEquals(1600, CodedPhyPolicy.advertIntervalUnits(1_000))
        assertEquals("never under the stack's 100 ms", 160, CodedPhyPolicy.advertIntervalUnits(20))
    }

    @Test
    fun aCodedDialGetsTheLongerWatchdog() {
        assertEquals(12_000L, CodedPhyPolicy.connectTimeoutMs(viaCoded = false))
        assertEquals(25_000L, CodedPhyPolicy.connectTimeoutMs(viaCoded = true))
        // Under the stack's own 30 s direct-connect timeout, so the watchdog is still what ends a stalled dial.
        assertTrue(CodedPhyPolicy.CODED_CONNECT_TIMEOUT_MS < 30_000L)
    }

    // --- scan windows ---

    @Test
    fun aLonePhoneAlternatesCodedOnlyWindows() {
        assertEquals(ScanPhys.CODED, CodedPhyPolicy.scanPhys(codedOn = true, alone = true, codedOnlyUnlinked = false, lastWasCoded = false))
        assertEquals(ScanPhys.ALL, CodedPhyPolicy.scanPhys(codedOn = true, alone = true, codedOnlyUnlinked = false, lastWasCoded = true))
        assertEquals(ScanPhys.CODED, CodedPhyPolicy.scanPhys(codedOn = true, alone = false, codedOnlyUnlinked = true, lastWasCoded = false))
    }

    @Test
    fun aLinkedPhoneWithNoFarPeerScansAllPhys() {
        assertEquals(ScanPhys.ALL, CodedPhyPolicy.scanPhys(codedOn = true, alone = false, codedOnlyUnlinked = false, lastWasCoded = false))
        assertEquals(ScanPhys.ALL, CodedPhyPolicy.scanPhys(codedOn = true, alone = false, codedOnlyUnlinked = false, lastWasCoded = true))
    }

    @Test
    fun theExperimentOffScansLegacy() {
        assertEquals(ScanPhys.ONE_M, CodedPhyPolicy.scanPhys(codedOn = false, alone = true, codedOnlyUnlinked = true, lastWasCoded = false))
    }

    @Test
    fun theLargerIdDrives() {
        assertTrue(CodedPhyPolicy.drives("zz", "aa"))
        assertFalse(CodedPhyPolicy.drives("aa", "zz"))
    }

    // --- steps ---

    @Test
    fun nothingIsAskedBeforeTheFirstPhyRead() {
        val s = PhyStepper { tuning }
        repeat(5) { s.onRssi(-95, now = it * 1_000L) }
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 5_000))
    }

    @Test
    fun offNeverActs() {
        assertEquals(Action.STAY, stepper(LinkPhy.ONE_M, -95, -95, -95, -95).decide(CodedPhyMode.OFF, now = 10_000))
    }

    @Test
    fun threeWeakReadsStepDownAndTwoDoNot() {
        assertEquals(Action.STAY, stepper(LinkPhy.ONE_M, -90, -90).decide(CodedPhyMode.AUTO, now = 10_000))
        assertEquals(Action.REQUEST_CODED, stepper(LinkPhy.ONE_M, -90, -90, -90).decide(CodedPhyMode.AUTO, now = 10_000))
    }

    @Test
    fun aStrongReadResetsTheWeakStreak() {
        // Smoothed (α 0.5): −90, −90, −70, −80 — the −70 ends the streak and −80 is not weak enough to restart it.
        val s = stepper(LinkPhy.ONE_M, -90, -90, -50, -90)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 10_000))
    }

    @Test
    fun aCodedLinkJustOverTheStepUpLineWaitsTheSlowHold() {
        val s = stepper(LinkPhy.CODED, -68)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 29_000))
        s.onRssi(-68, now = 30_000)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
    }

    @Test
    fun aStrongCodedLinkStepsUpAfterTheFastHold() {
        // #113: a link back at −55 waited out the 30 s hold meant for the band just over −72.
        val s = stepper(LinkPhy.CODED, -55)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 9_999))
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 10_000))
    }

    @Test
    fun aReadUnderTheFastTierFallsBackToTheSlowHold() {
        val s = stepper(LinkPhy.CODED, -55) // both holds from 0
        assertEquals(StepUpHold.SLOW, s.onRssi(-77, now = 5_000)) // smoothed −66: under −62, still over −72
        s.onRssi(-66, now = 10_000)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 10_000))
        s.onRssi(-66, now = 25_000)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 29_999))
        // The slow hold ran from 0, not from the dip.
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
    }

    @Test
    fun theFastTierStillWaitsOutTheMinimumGap() {
        val s = stepper(LinkPhy.ONE_M, -90, -90, -90)
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 3_000))
        assertTrue(s.onPhy(LinkPhy.CODED, succeeded = true, now = 3_200))
        // Straight back in: smoothed −70, −60, −55, … — fast from 5 s, its hold met at 15 s.
        (4_000L..23_000L step 1_000L).forEach { s.onRssi(-50, now = it) }
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 15_000))
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 23_199))
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 23_200))
    }

    @Test
    fun aReadReportsTheStepUpHoldItMovesInto() {
        val s = PhyStepper { tuning }.also { it.onPhy(LinkPhy.CODED, succeeded = true, now = 0) }
        assertNull(s.onRssi(-90, now = 0)) // none, as before
        assertEquals(StepUpHold.SLOW, s.onRssi(-50, now = 1_000)) // smoothed −70
        assertEquals(StepUpHold.FAST, s.onRssi(-50, now = 2_000)) // −60
        assertNull(s.onRssi(-50, now = 3_000)) // −55, still fast
        assertEquals(StepUpHold.SLOW, s.onRssi(-80, now = 4_000)) // −67.5
        assertEquals(StepUpHold.NONE, s.onRssi(-100, now = 5_000)) // −83.75
    }

    @Test
    fun aStepUpAnsweredWith2MIsTakenNotGivenUp() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
        assertTrue(s.onPhy(LinkPhy.TWO_M, succeeded = true, now = 30_400)) // the controller picked 2M
        assertFalse(s.gaveUp)
        assertEquals(LinkPhy.TWO_M, s.phy)
        // A strong 2M link is already fast: AUTO asks nothing more of it.
        s.onRssi(-55, now = 60_000)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 60_000))
    }

    @Test
    fun aStepUpAnsweredWith1MIsTakenToo() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
        assertTrue(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 30_400)) // the peer has no 2M
        assertFalse(s.gaveUp)
    }

    @Test
    fun thePinnedOneMModeStaysStrict() {
        val s = stepper(LinkPhy.TWO_M, -60)
        assertEquals(Action.REQUEST_ONE_M, s.decide(CodedPhyMode.ONE_M, now = 1_000))
        assertFalse(s.onPhy(LinkPhy.TWO_M, succeeded = true, now = 1_200)) // 1M alone was asked
        assertTrue(s.gaveUp)
    }

    @Test
    fun aWeakDipRestartsTheStepUpHold() {
        val s = stepper(LinkPhy.CODED, -60)
        s.onRssi(-120, now = 20_000) // smoothed −90: no longer strong
        s.onRssi(-50, now = 25_000) // smoothed −70: strong again from here
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 40_000))
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 55_000))
    }

    @Test
    fun noAutomaticSwitchInsideTheMinimumGap() {
        val s = stepper(LinkPhy.CODED, -60)
        assertEquals(Action.REQUEST_FAST, s.decide(CodedPhyMode.AUTO, now = 30_000))
        assertTrue(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 30_500))
        // Straight back out of range: weak enough to step down (smoothed −80, −90, −95, −97.5)…
        listOf(31_000L, 32_000L, 33_000L, 34_000L).forEach { s.onRssi(-100, now = it) }
        // …but not within 20 s of the last switch.
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 35_000))
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 50_500))
        assertEquals(1, s.switches)
    }

    @Test
    fun anUnansweredRequestIsGivenUpAndNeverRepeated() {
        val s = stepper(LinkPhy.TWO_M, -90, -90, -90)
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 3_000))
        assertEquals(Action.STAY, s.decide(CodedPhyMode.AUTO, now = 5_999))
        assertEquals(Action.GIVE_UP, s.decide(CodedPhyMode.AUTO, now = 6_000))
        assertTrue(s.gaveUp)
        assertEquals(Action.STAY, s.decide(CodedPhyMode.CODED, now = 60_000))
    }

    @Test
    fun aRequestAnsweredWithAnotherPhyIsGivenUp() {
        val s = stepper(LinkPhy.ONE_M, -90, -90, -90)
        assertEquals(Action.REQUEST_CODED, s.decide(CodedPhyMode.AUTO, now = 3_000))
        assertFalse(s.onPhy(LinkPhy.ONE_M, succeeded = true, now = 3_200)) // the peer cannot do Coded
        assertTrue(s.gaveUp)
        assertEquals(LinkPhy.ONE_M, s.phy)
    }

    @Test
    fun aPeerDrivenUpdateIsFollowedNotGivenUp() {
        val s = stepper(LinkPhy.ONE_M)
        assertTrue(s.onPhy(LinkPhy.CODED, succeeded = true, now = 1_000))
        assertFalse(s.gaveUp)
        assertEquals(LinkPhy.CODED, s.phy)
    }

    @Test
    fun pinnedModesHoldTheirPhyWhateverTheRssi() {
        assertEquals(Action.REQUEST_CODED, stepper(LinkPhy.ONE_M, -40).decide(CodedPhyMode.CODED, now = 1_000))
        assertEquals(Action.REQUEST_ONE_M, stepper(LinkPhy.CODED, -100).decide(CodedPhyMode.ONE_M, now = 1_000))
        assertEquals(Action.STAY, stepper(LinkPhy.CODED, -40).decide(CodedPhyMode.CODED, now = 1_000))
    }

    @Test
    fun theReadCadenceTightensAtTheEdge() {
        assertEquals(tuning.readMs, stepper(LinkPhy.ONE_M, -60).nextReadMs())
        assertEquals(tuning.edgeReadMs, stepper(LinkPhy.ONE_M, -80).nextReadMs())
    }

    @Test
    fun modesRoundTripTheirSpelling() {
        CodedPhyMode.entries.forEach { assertEquals(it, CodedPhyMode.parse(it.wire)) }
        assertNull(CodedPhyMode.parse("long"))
    }

    @Test
    fun aFileOnACodedLinkIsFedAtTheCodedPaceInSmallChunks() {
        // #114: 28 KiB/s handed a 200 KB photo to a Coded link's stack in 7 s, and the text after it waited 3 min.
        assertEquals(PaceConfig(1024, 2048), CodedPhyPolicy.pace(LinkPhy.CODED, tuning))
    }

    @Test
    fun everyOtherPhyKeepsTheOneMPace() {
        val oneM = CodedPhyPolicy.pace(LinkPhy.ONE_M, tuning)
        for (phy in listOf(LinkPhy.TWO_M, LinkPhy.UNKNOWN)) {
            assertEquals("$phy", oneM, CodedPhyPolicy.pace(phy, tuning))
        }
    }

    @Test
    fun aLinkAloneOnOneMIsFedFourKibPerSecondInEightKibChunks() {
        // #117: 1M links drained 4.8–10 KB/s on 2026-10-03; at 28 KiB/s a text sent mid-photo waited one to four minutes.
        assertEquals(PaceConfig(4096, 8192), CodedPhyPolicy.pace(LinkPhy.ONE_M, PhyTuning()))
    }

    @Test
    fun theCodedPaceIsTunableAndItsChunkCappedAtTheCodecs() {
        val tuned = tuning.copy(codedPaceBytesPerSec = 512, codedChunkBytes = 1 shl 20)
        assertEquals(PaceConfig(512, LinkFraming.FILE_CHUNK_BYTES), CodedPhyPolicy.pace(LinkPhy.CODED, tuned))
    }

    @Test
    fun theOneMPaceIsTunableAndItsChunkIsTwoSecondsOfIt() {
        // #117: the trial sweeps `filePace`; each chunk carries about two seconds of the feed, as Coded's 2 KiB at 1 KiB/s.
        assertEquals(PaceConfig(8192, 16384), CodedPhyPolicy.pace(LinkPhy.ONE_M, tuning.copy(filePaceBytesPerSec = 8192)))
        assertEquals(PaceConfig(6144, 12288), CodedPhyPolicy.pace(LinkPhy.TWO_M, tuning.copy(filePaceBytesPerSec = 6144)))
    }

    @Test
    fun theOneMChunkIsClampedToTheCodedChunkAndTheCodecs() {
        assertEquals(PaceConfig(512, 2048), CodedPhyPolicy.pace(LinkPhy.ONE_M, tuning.copy(filePaceBytesPerSec = 512)))
        val fast = tuning.copy(filePaceBytesPerSec = Int.MAX_VALUE)
        assertEquals(PaceConfig(Int.MAX_VALUE, LinkFraming.FILE_CHUNK_BYTES), CodedPhyPolicy.pace(LinkPhy.ONE_M, fast))
    }

    @Test
    fun anUnboundedOneMPaceStaysUnboundedInWholeChunks() {
        val unbounded = PaceConfig(0, LinkFraming.FILE_CHUNK_BYTES)
        assertEquals(unbounded, CodedPhyPolicy.pace(LinkPhy.ONE_M, tuning.copy(filePaceBytesPerSec = 0)))
        assertEquals(unbounded, CodedPhyPolicy.pace(LinkPhy.UNKNOWN, tuning.copy(filePaceBytesPerSec = -1), feeding = 3))
    }

    @Test
    fun theOneMBudgetIsSplitAmongTheLinksFeeding() {
        // #117: one controller's air carries every link, so three photos fed at once share the budget.
        val budget = tuning.copy(filePaceBytesPerSec = 8192)
        assertEquals(8192, CodedPhyPolicy.pace(LinkPhy.ONE_M, budget, feeding = 1).bytesPerSec)
        assertEquals(4096, CodedPhyPolicy.pace(LinkPhy.ONE_M, budget, feeding = 2).bytesPerSec)
        assertEquals(2730, CodedPhyPolicy.pace(LinkPhy.TWO_M, budget, feeding = 3).bytesPerSec)
    }

    @Test
    fun aSplitShareTakesChunksOfTwoSecondsOfIt() {
        val budget = tuning.copy(filePaceBytesPerSec = 8192)
        assertEquals(PaceConfig(2730, 5460), CodedPhyPolicy.pace(LinkPhy.ONE_M, budget, feeding = 3))
        assertEquals(PaceConfig(1024, 2048), CodedPhyPolicy.pace(LinkPhy.ONE_M, budget, feeding = 8))
    }

    @Test
    fun aFeedingCountUnderOneIsTheLinkAlone() {
        // The link asking is always feeding; a count that missed it (a race at the start) never divides by zero.
        val budget = tuning.copy(filePaceBytesPerSec = 8192)
        assertEquals(CodedPhyPolicy.pace(LinkPhy.ONE_M, budget), CodedPhyPolicy.pace(LinkPhy.ONE_M, budget, feeding = 0))
    }

    @Test
    fun aCodedLinkIgnoresTheSplit() {
        assertEquals(PaceConfig(1024, 2048), CodedPhyPolicy.pace(LinkPhy.CODED, tuning, feeding = 5))
    }
}
