package com.alanbeidanci.app.audio

import android.content.Context
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeechPlaybackFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun executor(owner: Any) = owner.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(owner) as ExecutorService
    private fun track() = BundledEnglishSpeaker.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(BundledEnglishSpeaker) as? AudioTrack
    private fun player(): PronunciationPlayer {
        lateinit var value: PronunciationPlayer
        instrumentation.runOnMainSync { value = PronunciationPlayer(context) }
        return value
    }
    private fun awaitStarted(player: PronunciationPlayer) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < until && !player.status.value.contains("已开始播放")) Thread.sleep(50)
        assertTrue(player.status.value, player.status.value.contains("离线英文已开始播放") || player.status.value.contains("离线英文 已开始播放"))
        val audio = track()
        assertNotNull("必须创建实际 AudioTrack", audio)
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (audio!!.playbackHeadPosition == 0 && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("必须实际消耗 PCM，不能只检查缓存文件", audio.playbackHeadPosition > 0)
    }

    @Test fun stalledOnlineWorkStillStartsOfflinePlaybackWithinTheWaitLimit() {
        val player = player()
        executor(BundledEnglishSpeaker).submit {}.get(40, TimeUnit.SECONDS)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        executor(VolcengineSpeaker).execute { entered.countDown(); unblock.await(20, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            val started = System.nanoTime()
            instrumentation.runOnMainSync { player.speak("apple") }
            awaitStarted(player)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("联网等待不能拖到原来的网络超时：$elapsed ms", elapsed < 7000)
            assertTrue(player.status.value, player.status.value.contains("3.5 秒"))
        } finally {
            unblock.countDown()
            instrumentation.runOnMainSync { player.release() }
            executor(VolcengineSpeaker).submit {}.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun disposingAnOlderScreenMustNotStopTheNewScreensAudio() {
        val old = player()
        val current = player()
        try {
            instrumentation.runOnMainSync { current.speak("apple", offlineOnly = true) }
            awaitStarted(current)
            val playing = track()!!
            instrumentation.runOnMainSync { old.release() }
            executor(BundledEnglishSpeaker).submit {}.get(10, TimeUnit.SECONDS)
            assertSame(playing, track())
            assertEquals(AudioTrack.STATE_INITIALIZED, playing.state)
            assertTrue(playing.playbackHeadPosition > 0)
        } finally { instrumentation.runOnMainSync { old.release(); current.release() } }
    }

    @Test fun chinesePreviewMustImmediatelyReplaceThePreviousEnglishResult() {
        val player = player()
        try {
            instrumentation.runOnMainSync { player.speak("apple", offlineOnly = true) }
            awaitStarted(player)
            instrumentation.runOnMainSync {
                player.speak("你好", chinese = true)
                assertTrue("中文等待时不能保留上一次英文结果：${player.status.value}", player.status.value.contains("中文"))
            }
        } finally { instrumentation.runOnMainSync { player.release() } }
    }

    @Test fun chineseEngineErrorsMustBeVisibleAndCancelledCallbacksMustBeIgnored() {
        val player = player()
        try {
            val owner = PronunciationPlayer::class.java
            val requestClass = Class.forName(owner.name + "\$SystemSpeech")
            val constructor = requestClass.getDeclaredConstructor(String::class.java, String::class.java,
                Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType).apply { isAccessible = true }
            val tts = owner.getDeclaredField("tts").apply { isAccessible = true }.get(player) as TextToSpeech
            val method = owner.getDeclaredMethod("utteranceListener", TextToSpeech::class.java, requestClass,
                Locale::class.java, String::class.java).apply { isAccessible = true }
            val request = constructor.newInstance("你好", "us", 0.95f, true, 0L)
            val listener = method.invoke(player, tts, request, Locale.SIMPLIFIED_CHINESE, null) as UtteranceProgressListener
            listener.onError("test", TextToSpeech.ERROR_NETWORK)
            instrumentation.waitForIdleSync()
            assertTrue(player.notice.value.orEmpty(), player.notice.value.orEmpty().contains("中文语音没有播放成功"))
            instrumentation.runOnMainSync { player.speak("apple", offlineOnly = true) }
            awaitStarted(player)
            listener.onError("old-test", TextToSpeech.ERROR_NETWORK)
            instrumentation.waitForIdleSync()
            assertNull(player.notice.value)
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
