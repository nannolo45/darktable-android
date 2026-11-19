// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.darkroom.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Brief P2b: photos picked in Android's file picker are copied into the working folder. */
class WorkFolderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun incoming(name: String, text: String) = WorkFolder.Incoming(name) { text.byteInputStream() }

    @Test
    fun copiesWithTheOriginalNameAndMatchesSidecars() {
        val w = WorkFolder(File(tmp.root, "work"))
        val r = w.import(listOf(incoming("A.RAF", "raw a"), incoming("A.RAF.xmp", "xmp a"), incoming("B.CR2", "raw b"), incoming("B.xmp", "xmp b")))
        assertEquals(listOf("A.RAF", "B.CR2"), w.items().map { it.fileName })
        assertEquals(listOf("A.RAF", "B.CR2"), r.imported.map { it.fileName })
        assertEquals("xmp a", File(w.dir, "A.RAF.xmp").readText())
        assertEquals("darktable's sidecar name for B.xmp", "xmp b", File(w.dir, "B.CR2.xmp").readText())
        // permission bits, not canWrite(): the build workspace runs tests as root, who may write anyway
        val perms = java.nio.file.Files.getPosixFilePermissions(File(w.dir, "A.RAF").toPath())
        assertFalse("the copy of a photo is read-only", java.nio.file.attribute.PosixFilePermission.OWNER_WRITE in perms)
        assertTrue(r.messages.toString(), r.messages.isEmpty())
        assertTrue("no temporary file left", w.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun sameNameSameBytesKeepsTheEditOtherBytesGetANewName() {
        val w = WorkFolder(File(tmp.root, "work"))
        w.import(listOf(incoming("A.RAF", "raw a"), incoming("A.RAF.xmp", "edit 1")))
        // the same photo again: kept, with its edit
        val again = w.import(listOf(incoming("A.RAF", "raw a")))
        assertEquals(listOf("A.RAF"), w.items().map { it.fileName })
        assertEquals("edit 1", File(w.dir, "A.RAF.xmp").readText())
        assertTrue(again.messages.single().contains("already in the working folder"))
        // another photo with the same name: a new name, the first one untouched
        val other = w.import(listOf(incoming("A.RAF", "another raw"), incoming("A.RAF.xmp", "edit of the other")))
        assertEquals(listOf("A.RAF", "A_2.RAF"), w.items().map { it.fileName })
        assertEquals("raw a", File(w.dir, "A.RAF").readText())
        assertEquals("another raw", File(w.dir, "A_2.RAF").readText())
        assertEquals("the picked sidecar follows its photo", "edit of the other", File(w.dir, "A_2.RAF.xmp").readText())
        assertEquals("edit 1", File(w.dir, "A.RAF.xmp").readText())
        assertTrue(other.messages.any { it.contains("copied as A_2.RAF") })
    }

    @Test
    fun aPickedSidecarReplacesTheEditButKeepsABackup() {
        val w = WorkFolder(File(tmp.root, "work"))
        w.import(listOf(incoming("A.RAF", "raw a"), incoming("A.RAF.xmp", "edit 1")))
        val r = w.import(listOf(incoming("A.RAF.xmp", "edit from the pc")))
        assertEquals("edit from the pc", File(w.dir, "A.RAF.xmp").readText())
        assertEquals("edit 1", File(w.dir, "A.RAF.xmp.bak").readText())
        assertTrue(r.messages.single().contains("kept as A.RAF.xmp.bak"))
        assertEquals("a backup is not a photo", listOf("A.RAF"), w.items().map { it.fileName })
    }

    @Test
    fun sidecarWithoutPhotoAndBadNamesAreReported() {
        val w = WorkFolder(File(tmp.root, "work"))
        val r = w.import(listOf(incoming("C.NEF.xmp", "x"), incoming("../..", "y"), incoming("dir/D.ARW", "raw d")))
        assertEquals(listOf("D.ARW"), w.items().map { it.fileName })
        assertEquals(2, r.messages.size)
        assertFalse(File(tmp.root, "C.NEF.xmp").exists())
    }

    @Test
    fun aFailedCopyLeavesNothing() {
        val w = WorkFolder(File(tmp.root, "work"))
        val broken = WorkFolder.Incoming("E.RAF") {
            object : java.io.InputStream() {
                var n = 0
                override fun read(): Int = if (n++ < 10) 1 else throw java.io.IOException("connection lost")
            }
        }
        val r = w.import(listOf(broken))
        assertTrue(r.messages.single().contains("could not copy E.RAF"))
        assertTrue(w.dir.listFiles()!!.isEmpty())
    }

    @Test
    fun numberedNames() {
        assertEquals("IMG_1_2.RAF", WorkFolder.numbered("IMG_1.RAF", 2))
        assertEquals("noext_3", WorkFolder.numbered("noext", 3))
    }
}
