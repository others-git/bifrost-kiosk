package com.whispercpp.whisper

import android.os.Build
import android.util.Log
import java.io.File

private const val LOG_TAG = "LibWhisper"

/**
 * Thin wrapper over the whisper.cpp JNI ([WhisperLib]). One context owns a loaded
 * model; whisper.cpp requires a context be touched from only one thread at a
 * time, so every call is serialized on [lock]. (The official sample uses a
 * single-thread coroutine dispatcher for this; we use a lock to avoid pulling in
 * kotlinx-coroutines.)
 *
 * Used only for **command** transcription — a one-shot decode of the captured
 * utterance — never the always-on wake loop (that stays on the light Vosk model).
 */
class WhisperContext private constructor(private var ptr: Long) {
    private val lock = Any()

    /** Transcribe 16 kHz mono float PCM (-1..1) to text. Blocking; call off the UI/audio thread. */
    fun transcribe(audio: FloatArray): String = synchronized(lock) {
        check(ptr != 0L) { "whisper context already released" }
        WhisperLib.fullTranscribe(ptr, WhisperCpuConfig.preferredThreadCount, audio)
        val n = WhisperLib.getTextSegmentCount(ptr)
        buildString {
            for (i in 0 until n) append(WhisperLib.getTextSegment(ptr, i))
        }.trim()
    }

    fun release() = synchronized(lock) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        release()
    }

    companion object {
        /** Load a ggml whisper model from a filesystem path (e.g. base.en). */
        fun fromFile(path: String): WhisperContext {
            val ptr = WhisperLib.initContext(path)
            if (ptr == 0L) throw RuntimeException("whisper: couldn't load model at $path")
            return WhisperContext(ptr)
        }

        fun systemInfo(): String = WhisperLib.getSystemInfo()
    }
}

/**
 * JNI surface. The symbol names (`Java_com_whispercpp_whisper_WhisperLib_...`)
 * are why this lives in `com.whispercpp.whisper` and matches the vendored
 * whisper_jni.c verbatim. Loads the fp16-optimized variant when the CPU supports
 * it (the wall tablet's Snapdragon does), else the baseline build.
 */
private class WhisperLib {
    companion object {
        init {
            Log.d(LOG_TAG, "Primary ABI: ${Build.SUPPORTED_ABIS[0]}")
            var loadV8fp16 = false
            if (Build.SUPPORTED_ABIS[0].equals("arm64-v8a")) {
                cpuInfo()?.let { if (it.contains("fphp")) loadV8fp16 = true }
            }
            if (loadV8fp16) {
                Log.d(LOG_TAG, "Loading libwhisper_v8fp16_va.so")
                System.loadLibrary("whisper_v8fp16_va")
            } else {
                Log.d(LOG_TAG, "Loading libwhisper.so")
                System.loadLibrary("whisper")
            }
        }

        external fun initContext(modelPath: String): Long
        external fun freeContext(contextPtr: Long)
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)
        external fun getTextSegmentCount(contextPtr: Long): Int
        external fun getTextSegment(contextPtr: Long, index: Int): String
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long
        external fun getSystemInfo(): String
        external fun benchMemcpy(nthread: Int): String
        external fun benchGgmlMulMat(nthread: Int): String

        private fun cpuInfo(): String? = try {
            File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
            null
        }
    }
}
