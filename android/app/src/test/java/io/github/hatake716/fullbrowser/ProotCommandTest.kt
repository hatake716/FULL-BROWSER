package io.github.hatake716.fullbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProotCommandTest {
    private val p = ProotCommand.Paths(
        nativeLibDir = File("/data/app/x/lib/arm64"),
        filesDir = File("/data/data/io.github.hatake716.fullbrowser/files"),
        rootfs = File("/data/data/io.github.hatake716.fullbrowser/files/rootfs/firefox"),
    )

    @Test
    fun sessionArgvHasExpectedShape() {
        val a = ProotCommand.sessionArgv(p, Browser.FIREFOX, "Asia/Tokyo")
        assertEquals("/data/app/x/lib/arm64/libproot.so", a[0])
        assertTrue(a.contains("--kill-on-exit"))
        assertTrue(a.contains("--link2symlink"))
        assertTrue(a.contains("--sysvipc"))
        assertTrue(a.contains("--rootfs=/data/data/io.github.hatake716.fullbrowser/files/rootfs/firefox"))
        assertTrue(a.contains("--bind=/system/fonts:/system/fonts"))
        assertTrue(a.contains("--bind=/data/data/io.github.hatake716.fullbrowser/files/rootfs/firefox/etc/fullbrowser/proc/stat:/proc/stat"))
        val envIdx = a.indexOf("/usr/bin/env")
        assertEquals("-i", a[envIdx + 1])
        assertTrue(a.subList(envIdx, a.size).contains("DISPLAY=:0"))
        assertTrue(a.subList(envIdx, a.size).contains("TZ=Asia/Tokyo"))
        assertEquals(listOf("/usr/local/bin/fb-session", "firefox"), a.takeLast(2))
    }

    @Test
    fun environmentPointsToLoaderAndLibs() {
        val e = ProotCommand.environment(p)
        assertEquals("/data/app/x/lib/arm64/libloader.so", e["PROOT_LOADER"])
        assertEquals("/data/data/io.github.hatake716.fullbrowser/files/tmp", e["PROOT_TMP_DIR"])
        assertEquals("/data/data/io.github.hatake716.fullbrowser/files/lib", e["LD_LIBRARY_PATH"])
        assertEquals(null, e["PROOT_NO_SECCOMP"])
        assertEquals("1", ProotCommand.environment(p, noSeccomp = true)["PROOT_NO_SECCOMP"])
    }

    @Test
    fun installChromeDoesNotNeedDisplay() {
        val a = ProotCommand.installChromeArgv(p, "Asia/Tokyo")
        assertTrue(a.none { it.startsWith("DISPLAY=") })
        assertEquals("/usr/local/bin/fb-install-chrome", a.last())
    }
}
