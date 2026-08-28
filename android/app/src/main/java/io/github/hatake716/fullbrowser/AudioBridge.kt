package io.github.hatake716.fullbrowser

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.File
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * ゲストの音声を Android で再生するブリッジ。
 *
 * ゲスト側では fb-session が PulseAudio を起動し、module-pipe-sink が
 * 生 PCM (s16le / 48kHz / 2ch) を FIFO <rootfs>/tmp/.fb-audio へ書く。
 * ここではその FIFO を読み、AudioTrack (USAGE_MEDIA) へ流すだけ。
 *
 * - FIFO がまだ無い/PA 未起動 → 1 秒間隔でリトライ
 * - PA が終了 (セッション終了) → read が EOF → 再オープン待ちに戻る
 * - 無音時は PA がサスペンドしデータが来ない → read がブロック (CPU 消費なし)
 */
class AudioBridge(private val fifo: File) {
    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        stop()
        running = true
        worker = thread(name = "fb-audio", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        val rate = 48000
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(rate, channelMask, encoding).coerceAtLeast(4096)
        var announced = false
        while (running) {
            try {
                FileInputStream(fifo).use { input ->
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setEncoding(encoding)
                                .setChannelMask(channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(minBuf * 4)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    track.play()
                    val buf = ByteArray(minBuf)
                    try {
                        while (running) {
                            val n = input.read(buf)
                            if (n < 0) break   // PA 終了 → 再オープン待ちへ
                            if (!announced && n > 0) {
                                announced = true
                                Log.i(App.TAG, "audio: stream started (${fifo.name})")
                            }
                            var off = 0
                            while (off < n && running) {
                                val w = track.write(buf, off, n - off)
                                if (w < 0) break
                                off += w
                            }
                        }
                    } finally {
                        runCatching { track.stop() }
                        track.release()
                    }
                }
            } catch (e: Exception) {
                // FIFO 未作成 (PA 起動前) など。セッション稼働中は静かに待つ
                if (running) runCatching { Thread.sleep(1000) }
            }
        }
    }
}
