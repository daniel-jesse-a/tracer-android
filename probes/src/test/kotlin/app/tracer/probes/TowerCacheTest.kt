package app.tracer.probes

import app.tracer.core.runtime.TowerLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TowerCacheTest {

    @Test fun cachesHitsAndMissesAndPersists() {
        val dir = Files.createTempDirectory("tower").toFile()
        val f = File(dir, "tower_cache.json")
        val c = TowerCache(f)
        val hit = TowerLocation(25.2, 55.3, 500.0, "opencellid")
        c.put("LTE:424:2:100:1", hit)
        c.put("LTE:424:2:100:2", null)
        assertEquals(hit, c.get("LTE:424:2:100:1")?.location)
        val miss = c.get("LTE:424:2:100:2")
        assertNotNull(miss)
        assertNull(miss!!.location)
        assertNull(c.get("LTE:424:2:100:3"))

        val reloaded = TowerCache(f)
        assertEquals(hit, reloaded.get("LTE:424:2:100:1")?.location)
        assertNotNull(reloaded.get("LTE:424:2:100:2"))
        dir.deleteRecursively()
    }

    @Test fun lruEvictsOldest() {
        val c = TowerCache(null, capacity = 2)
        c.put("a", null); c.put("b", null)
        c.get("a")
        c.put("c", null)
        assertNotNull(c.get("a"))
        assertNull(c.get("b"))
        assertEquals(2, c.size())
    }

    @Test fun openCellIdParsing() {
        val hit = parseOpenCellIdResponse("""{"lat":25.1,"lon":55.2,"mcc":424,"mnc":2,"lac":1,"cellid":2,"range":1200,"radio":"LTE"}""")
        assertEquals(TowerLocation(25.1, 55.2, 1200.0, "opencellid"), hit.getOrNull())
        val notFound = parseOpenCellIdResponse("""{"error":"Cell not found","code":1}""")
        assertTrue(notFound.isSuccess)
        assertNull(notFound.getOrNull())
        assertTrue(parseOpenCellIdResponse("""{"error":"Invalid token","code":2}""").isFailure)
        assertTrue(parseOpenCellIdResponse("<html>").isFailure)
        assertEquals("UMTS", openCellIdRadio("WCDMA"))
        assertEquals("NR", openCellIdRadio("NR"))
        assertNull(openCellIdRadio("IWLAN"))
    }
}
