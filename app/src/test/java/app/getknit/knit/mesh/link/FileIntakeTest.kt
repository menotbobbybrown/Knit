package app.getknit.knit.mesh.link

import app.getknit.knit.mesh.ArrivingFile
import app.getknit.knit.mesh.FileKind
import app.getknit.knit.mesh.PartialBlobs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * [FileIntake]: one link's receive side, driven record by record on one thread — the cut that keeps an attachment's
 * prefix and the resumed header that splices the rest onto it (work item #116, ADR 2026-10.wtyc). The threading
 * around it is [FramedLink]'s, covered in `FramedLinkTest`.
 */
class FileIntakeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val key = "a".repeat(64)
    private val body = ByteArray(1000) { (it % 251).toByte() }
    private val log = mutableListOf<String>()

    private val partials by lazy { PartialBlobs(File(tmp.root, "parts"), minBytes = 1) }

    private fun intake(store: PartialBlobs = partials) = FileIntake(tmp.root, store, "peer0001", now = { 0L }, log = { log += it })

    private fun header(
        kind: FileKind = FileKind.ATTACHMENT,
        size: Long? = null,
        offset: Long? = null,
        key: String = this.key,
    ) = LinkFraming.encodeFileHeader(FileHeaderWire(kind.wire, key, "image/jpeg", size, offset))

    /** Keeps the first [n] bytes of [body] as a cut transfer's prefix, as a closed link would. */
    private fun keepPrefix(n: Int) {
        val cut = tmp.newFile().apply { writeBytes(body.copyOf(n)) }
        assertTrue(partials.keep(key, cut, basedOn = null))
    }

    /** Takes a resumed header through the owner's copy and install. */
    private fun FileIntake.resume(
        offset: Long,
        size: Long?,
    ): Boolean {
        val resume = header(header(size = size, offset = offset)) ?: return false
        resume.copy()
        return install(resume)
    }

    private fun rxFiles() = tmp.root.listFiles()!!.filter { it.name.startsWith("link-rx-") }

    @Test
    fun aResumedStreamSplicesOntoTheKeptPrefixByteForByte() {
        keepPrefix(600)
        val intake = intake()
        assertTrue(intake.resume(offset = 600, size = 400))
        assertEquals("the ring resumes where it was", ArrivingFile(key, 600, 1000), intake.arrivingFile)
        intake.chunk(body.copyOfRange(600, 1000))
        assertEquals(ArrivingFile(key, 1000, 1000), intake.arrivingFile)
        val finished = checkNotNull(intake.end())
        val received = checkNotNull(intake.finalize(finished))
        assertEquals(600L, received.resumedFrom)
        assertArrayEquals(body, File(received.path).readBytes())
        assertTrue("the offset rides last on the header's line", log.any { it.endsWith("← peer0001 from 600") })
        assertTrue("no temp outlives the file", rxFiles().isEmpty())
    }

    @Test
    fun aPrefixThatIsAlreadyCompleteFinishesOnAnEmptyTail() {
        // A cut can land between the last chunk and the end: the holder answers the whole length with no bytes.
        keepPrefix(1000)
        val intake = intake()
        assertTrue(intake.resume(offset = 1000, size = 0))
        val received = checkNotNull(intake.finalize(checkNotNull(intake.end())))
        assertArrayEquals(body, File(received.path).readBytes())
    }

    @Test
    fun aStreamFromBeforeTheEndOfTheKeptPrefixCopiesOnlyWhatItNeeds() {
        // A serve queued before another link got further: the stream starts at 400 though 600 are kept.
        keepPrefix(600)
        val intake = intake()
        assertTrue(intake.resume(offset = 400, size = 600))
        intake.chunk(body.copyOfRange(400, 1000))
        assertArrayEquals(body, File(checkNotNull(intake.finalize(checkNotNull(intake.end()))).path).readBytes())
    }

    @Test
    fun anOffsetPastTheKeptPrefixSkipsTheStream() {
        keepPrefix(600)
        val intake = intake()
        assertNull("nothing to splice onto", intake.header(header(size = 200, offset = 800)))
        assertNull(intake.arrivingFile)
        intake.chunk(body.copyOfRange(800, 1000))
        assertNull("its chunks and its end come to nothing", intake.end())
        assertTrue(rxFiles().isEmpty())
        assertEquals("and the prefix is untouched", 600L, partials.length(key))
    }

    @Test
    fun aResumedHeaderForAnAvatarOrAMalformedKeyOrAWildOffsetIsRefused() {
        keepPrefix(600)
        val intake = intake()
        assertNull(intake.header(header(kind = FileKind.AVATAR, size = 400, offset = 600)))
        assertNull(intake.header(header(size = 400, offset = 600, key = "../evil")))
        assertNull(intake.header(header(size = 400, offset = -5)))
        assertNull(intake.header(header(size = 400, offset = FileIntake.MAX_INCOMING_FILE_BYTES + 1)))
        assertNull(intake.arrivingFile)
        assertTrue(rxFiles().isEmpty())
    }

    @Test
    fun anExplicitZeroOffsetIsAWholeFile() {
        keepPrefix(600)
        val intake = intake()
        assertNull("taken here, nothing to splice", intake.header(header(size = 1000, offset = 0)))
        assertEquals(ArrivingFile(key, 0, 1000), intake.arrivingFile)
        intake.chunk(body)
        val received = checkNotNull(intake.finalize(checkNotNull(intake.end())))
        assertEquals(0L, received.resumedFrom)
        assertArrayEquals(body, File(received.path).readBytes())
    }

    @Test
    fun theCeilingCountsTheKeptPrefix() {
        val nearCeiling = (FileIntake.MAX_INCOMING_FILE_BYTES - 10).toInt()
        val cut = tmp.newFile().apply { writeBytes(ByteArray(nearCeiling)) }
        assertTrue(partials.keep(key, cut, basedOn = null))
        val intake = intake()
        assertTrue(intake.resume(offset = nearCeiling.toLong(), size = 100))
        intake.chunk(ByteArray(20))
        assertNull("past the ceiling the file is gone", intake.arrivingFile)
        assertNull(intake.end())
        assertTrue(rxFiles().isEmpty())
    }

    @Test
    fun anAttachmentCutMidStreamKeepsItsPrefixAndACutResumeKeepsTheLongerOne() {
        val intake = intake()
        assertNull(intake.header(header(size = 1000)))
        intake.chunk(body.copyOf(300))
        intake.cut()
        assertEquals(300L, partials.length(key))
        assertNull("nothing is arriving on a closed link", intake.arrivingFile)

        val next = intake()
        assertTrue(next.resume(offset = 300, size = 700))
        next.chunk(body.copyOfRange(300, 800))
        next.cut()
        assertEquals("the second cut got further", 800L, partials.length(key))
        assertTrue(rxFiles().isEmpty())
    }

    @Test
    fun aWholeFileStreamNeverCostsALongerKeptPrefix() {
        // An older holder ignores the offset and streams from byte 0; cut short, it must not undo an 80 % prefix.
        keepPrefix(800)
        val intake = intake()
        assertNull(intake.header(header(size = 1000)))
        assertEquals("from nothing, whatever is kept", ArrivingFile(key, 0, 1000), intake.arrivingFile)
        intake.chunk(body.copyOf(200))
        intake.cut()
        assertEquals(800L, partials.length(key))
    }

    @Test
    fun aResumeWhosePrefixWasDroppedMeanwhileKeepsNothingWhenItIsCut() {
        // A sibling copy failed its hash and dropped the prefix this one was spliced onto.
        keepPrefix(600)
        val intake = intake()
        assertTrue(intake.resume(offset = 600, size = 400))
        partials.drop(key)
        intake.chunk(body.copyOfRange(600, 900))
        intake.cut()
        assertEquals(0L, partials.length(key))
        assertTrue(rxFiles().isEmpty())
    }

    @Test
    fun anAvatarOrAShortAttachmentCutMidStreamKeepsNothing() {
        val strict = PartialBlobs(File(tmp.root, "strict"), minBytes = 500)
        val avatar = intake(strict)
        assertNull(avatar.header(header(kind = FileKind.AVATAR, size = 1000)))
        avatar.chunk(body.copyOf(900))
        avatar.cut()
        assertEquals("an avatar is pushed, never asked for", 0L, strict.length(key))

        val short = intake(strict)
        assertNull(short.header(header(size = 1000)))
        short.chunk(body.copyOf(100))
        short.cut()
        assertEquals("under the minimum", 0L, strict.length(key))
        assertTrue(rxFiles().isEmpty())
    }

    @Test
    fun aReplacingHeaderDiscardsTheFileItInterrupts() {
        // A sender that moves on without an end abandoned the file — not a cut, so nothing is kept (as on iOS).
        val other = "b".repeat(64)
        val intake = intake()
        assertNull(intake.header(header(size = 1000)))
        intake.chunk(body.copyOf(500))
        assertNull(intake.header(header(size = 10, key = other)))
        assertEquals(ArrivingFile(other, 0, 10), intake.arrivingFile)
        assertEquals(0L, partials.length(key))
        assertEquals("only the new file's temp", 1, rxFiles().size)
    }

    @Test
    fun aCutWhileTheResumeCopiesLeavesItToTheReaderToGiveUp() {
        keepPrefix(600)
        val intake = intake()
        val resume = checkNotNull(intake.header(header(size = 400, offset = 600)))
        intake.cut() // the link closed on the owner's thread while the reader was about to copy
        resume.copy()
        assertFalse("superseded: never installed", intake.install(resume))
        assertNull(intake.arrivingFile)
        assertTrue("its temp went with it", rxFiles().isEmpty())
        assertEquals("the prefix it read from is untouched", 600L, partials.length(key))
    }

    @Test
    fun aPrefixThatComesUpShortWhileCopyingSkipsTheStream() {
        keepPrefix(600)
        val intake = intake()
        val resume = checkNotNull(intake.header(header(size = 400, offset = 600)))
        // Damaged under the reader (a kept prefix is never written to, so only the disk could do this).
        RandomAccessFile(File(tmp.root, "parts/$key.part"), "rw").use { it.setLength(100) }
        resume.copy()
        assertFalse(intake.install(resume))
        assertNull(intake.arrivingFile)
        intake.chunk(body.copyOfRange(600, 1000))
        assertNull("its chunks and its end come to nothing", intake.end())
        assertTrue(rxFiles().isEmpty())
    }
}
