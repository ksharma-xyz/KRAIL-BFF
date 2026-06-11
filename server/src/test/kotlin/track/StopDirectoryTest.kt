package track

import app.krail.bff.track.StopDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StopDirectoryTest {

    private val dir = StopDirectory()

    @Test
    fun `resolves parent stations and bus stops from the bundled dataset`() {
        val central = assertNotNull(dir.find("200060"), "Central parent station")
        assertEquals("Central Station", central.name)
        assertTrue(central.lat < -33.0 && central.lon > 151.0, "has coordinates")
    }

    @Test
    fun `unknown ids resolve to null, never a guess`() {
        // Rail platform child ids are absent from the search dataset —
        // resolving them must return null (prefix-guessing gives wrong
        // stations, e.g. 206142's prefix is Kirribilli Wharf).
        assertNull(dir.find("2000336"))
        assertNull(dir.find("definitely-not-a-stop"))
    }
}
