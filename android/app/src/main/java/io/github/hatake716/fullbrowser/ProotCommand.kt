package io.github.hatake716.fullbrowser

import java.io.File

/**
 * proot の argv / 環境変数を組み立てる純粋関数 (docs/ARCHITECTURE.md §3)。
 * Android API を使わないので JVM 単体テストで検証できる。
 */
object ProotCommand {

    data class Paths(
        /** ApplicationInfo.nativeLibraryDir。libproot.so / libloader.so がある */
        val nativeLibDir: File,
        /** context.filesDir */
        val filesDir: File,
        /** rootfs のルート (files/rootfs/<variant>) */
        val rootfs: File,
    ) {
        val proot get() = File(nativeLibDir, "libproot.so")
        val loader get() = File(nativeLibDir, "libloader.so")
        val libDir get() = File(filesDir, "lib")
        val tmpDir get() = File(filesDir, "tmp")
    }

    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    private const val KERNEL_RELEASE = "\\Linux\\localhost\\6.6.0-fullbrowser\\#1 SMP PREEMPT\\aarch64\\localdomain\\-1\\"

    /** proot 自身のための環境変数 (ProcessBuilder.environment に入れる) */
    fun environment(p: Paths, noSeccomp: Boolean = false): Map<String, String> {
        val env = linkedMapOf(
            "PROOT_LOADER" to p.loader.absolutePath,
            "PROOT_TMP_DIR" to p.tmpDir.absolutePath,
            "PROOT_L2S_DIR" to File(p.tmpDir, "l2s").absolutePath,
            "LD_LIBRARY_PATH" to p.libDir.absolutePath,
            "HOME" to p.filesDir.absolutePath,
            "TMPDIR" to p.tmpDir.absolutePath,
            "LANG" to "C.UTF-8",
        )
        if (noSeccomp) env["PROOT_NO_SECCOMP"] = "1"
        return env
    }

    /**
     * rootfs 内でコマンドを実行する argv。
     * @param guestEnv  /usr/bin/env -i に渡すゲスト側の環境変数 (DISPLAY, TZ など)
     * @param guestArgv ゲスト側で実行するコマンド (絶対パス推奨)
     */
    fun argv(
        p: Paths,
        guestArgv: List<String>,
        guestEnv: Map<String, String> = emptyMap(),
        extraBinds: List<Pair<String, String>> = emptyList(),
    ): List<String> {
        val r = p.rootfs.absolutePath
        val stub = "$r/etc/fullbrowser/proc"
        val a = mutableListOf(
            p.proot.absolutePath,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=$KERNEL_RELEASE",
            "-L",
            "--change-id=0:0",
            "--rootfs=$r",
            "--cwd=/root",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=$r/tmp/.shm:/dev/shm",
            "--bind=$stub/stat:/proc/stat",
            "--bind=$stub/version:/proc/version",
            "--bind=$stub/loadavg:/proc/loadavg",
            "--bind=$stub/uptime:/proc/uptime",
            "--bind=$stub/vmstat:/proc/vmstat",
            "--bind=$stub/cap_last_cap:/proc/sys/kernel/cap_last_cap",
            "--bind=$stub/max_user_watches:/proc/sys/fs/inotify/max_user_watches",
            "--bind=/system/fonts:/system/fonts",
        )
        extraBinds.forEach { (src, dst) -> a += "--bind=$src:$dst" }
        a += "/usr/bin/env"
        a += "-i"
        val env = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "LANGUAGE" to "ja",
            "PATH" to GUEST_PATH,
            "TMPDIR" to "/tmp",
            "PULSE_SERVER" to "unix:/tmp/.fb-pulse",   // fb-session が起動するゲスト内 PulseAudio
        )
        env.putAll(guestEnv)
        env.forEach { (k, v) -> a += "$k=$v" }
        a += guestArgv
        return a
    }

    /** ブラウザセッション: fb-session <browser> */
    fun sessionArgv(p: Paths, browser: Browser, timeZone: String, display: Int = 0): List<String> =
        argv(
            p,
            guestArgv = listOf("/usr/local/bin/fb-session", browser.id),
            guestEnv = mapOf("DISPLAY" to ":$display", "TZ" to timeZone, "FB_BROWSER" to browser.id, "MOZ_USE_XINPUT2" to "1"),
        )

    /** Chrome 本体の取得 (X 不要) */
    fun installChromeArgv(p: Paths, timeZone: String): List<String> =
        argv(p, guestArgv = listOf("/usr/local/bin/fb-install-chrome"), guestEnv = mapOf("TZ" to timeZone))

    /** ブラウザ更新 */
    fun updateArgv(p: Paths, browser: Browser, timeZone: String): List<String> =
        argv(p, guestArgv = listOf("/usr/local/bin/fb-browser-update", browser.id), guestEnv = mapOf("TZ" to timeZone))
}
