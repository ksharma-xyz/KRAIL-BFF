package app.krail.bff.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionTest {
    @Test
    fun `parses MAJOR_MINOR_PATCH`() {
        val v = Version.parse("1.5.3")
        assertNotNull(v)
        assertEquals(Version(1, 5, 3), v)
        assertEquals("1.5.3", v.toString())
    }

    @Test
    fun `strips pre-release suffix`() {
        assertEquals(Version(1, 5, 3), Version.parse("1.5.3-beta.1"))
    }

    @Test
    fun `strips build metadata`() {
        assertEquals(Version(1, 5, 3), Version.parse("1.5.3+build.42"))
    }

    @Test
    fun `strips both suffix and metadata`() {
        assertEquals(Version(1, 5, 3), Version.parse("1.5.3-rc.1+build.42"))
    }

    @Test
    fun `returns null for non-three-part input`() {
        assertNull(Version.parse(""))
        assertNull(Version.parse("1"))
        assertNull(Version.parse("1.5"))
        assertNull(Version.parse("1.5.3.4"))
    }

    @Test
    fun `returns null for non-numeric components`() {
        assertNull(Version.parse("a.b.c"))
        assertNull(Version.parse("1.x.3"))
        assertNull(Version.parse("1..3"))
    }

    @Test
    fun `returns null for negative components`() {
        assertNull(Version.parse("-1.0.0"))
        assertNull(Version.parse("1.-2.0"))
    }

    @Test
    fun `compareTo orders semver-like`() {
        assertTrue(Version(1, 0, 0) < Version(1, 0, 1))
        assertTrue(Version(1, 0, 0) < Version(1, 1, 0))
        assertTrue(Version(1, 0, 0) < Version(2, 0, 0))
        assertTrue(Version(1, 5, 0) < Version(1, 5, 1))
        assertEquals(Version(1, 5, 0), Version(1, 5, 0))
        assertTrue(Version(2, 0, 0) > Version(1, 99, 99))
    }
}
