package com.lv999call.app.audio

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * 「边收边播」用的有界字节管道：单写者（TTS 解码协程）+ 单读者（AudioPlayer 播放协程）。
 *
 * ## 为什么不用 java.io.PipedInputStream
 *
 * PipedInputStream 的写端在缓冲区**没写满**时不会唤醒阻塞中的读端 ——
 * `receive()` 里只在「缓冲区已满」(awaitSpace) 和 `receivedLast()`（写端关闭）两处
 * 有 notifyAll，正常写入路径一句都没有。读端只能靠 `wait(1000)` 超时才发现有新数据。
 *
 * 这个坑在本项目的 Android 源码里可以直接翻到
 * (`$SDK/sources/android-36.1/java/io/PipedInputStream.java`)，JDK21 同样如此；
 * 实测（tools/audio_pipe_test）「写入很小一段、缓冲区远没满」时读端要 ~800ms 才被唤醒。
 * 对 TTS 来说这意味着音频会被切成 ~1 秒一顿的节奏，比「等整段合成完再播」还糟。
 *
 * 所以这里用 ReentrantLock + Condition 自己实现，写入即 signal，读写都立刻被唤醒。
 *
 * ## 语义
 * - [write]：容量满时阻塞（背压），读端 [close] 后抛 IOException
 * - [read]：无数据时阻塞；[closeWriter] 后再读完剩余数据返回 -1
 * - [closeWriter]：写端结束（正常 EOF 路径）
 * - [close]：读端提前中断（挂断/打断），让正在阻塞的写端和读端都立刻返回
 */
class AudioPipe(private val capacity: Int = DEFAULT_CAPACITY) : InputStream() {

    companion object {
        /** 64KB ≈ 1.3 秒 @24kHz/mono/16bit，只用来抹平网络抖动，不做整段缓存 */
        const val DEFAULT_CAPACITY = 64 * 1024
    }

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lock = ReentrantLock()
    private val canRead = lock.newCondition()
    private val canWrite = lock.newCondition()

    private val buffer = ByteArray(capacity)
    private var head = 0   // 下一个待读字节的位置
    private var size = 0   // 已写入未读的字节数

    private var writerDone = false   // 写端收工：读完后返回 EOF
    private var closed = false       // 读端中断：两边都立刻收摊

    /** 写入一段数据；缓冲区满时阻塞，直到读端消费出空间。 */
    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) { "invalid range" }
        if (length == 0) return

        lock.lock()
        try {
            var off = offset
            var remaining = length
            while (remaining > 0) {
                if (closed) throw IOException("AudioPipe 已被读端关闭")
                if (size == capacity) {
                    canWrite.await()   // 背压：播放多快就解码多快
                    continue
                }
                val n = minOf(capacity - size, remaining)
                val pos = (head + size) % capacity
                val first = minOf(n, capacity - pos)
                System.arraycopy(data, off, buffer, pos, first)
                if (n > first) System.arraycopy(data, off + first, buffer, 0, n - first)

                size += n
                off += n
                remaining -= n
                canRead.signalAll()    // 关键：一有数据就唤醒读端，不等超时
            }
        } finally {
            lock.unlock()
        }
    }

    /** 写端正常结束：读端把剩余数据读完后再读到 -1。 */
    fun closeWriter() {
        lock.lock()
        try {
            writerDone = true
            canRead.signalAll()
        } finally {
            lock.unlock()
        }
    }

    override fun read(): Int {
        lock.lock()
        try {
            while (size == 0) {
                if (writerDone || closed) return -1
                canRead.await()
            }
            val value = buffer[head].toInt() and 0xFF
            advance(1)
            canWrite.signalAll()
            return value
        } finally {
            lock.unlock()
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        require(off >= 0 && len >= 0 && off + len <= b.size) { "invalid range" }

        lock.lock()
        try {
            while (size == 0) {
                if (writerDone || closed) return -1
                canRead.await()
            }
            val n = minOf(len, size)
            val first = minOf(n, capacity - head)
            System.arraycopy(buffer, head, b, off, first)
            if (n > first) System.arraycopy(buffer, 0, b, off + first, n - first)
            advance(n)
            canWrite.signalAll()
            return n
        } finally {
            lock.unlock()
        }
    }

    override fun available(): Int {
        lock.lock()
        try {
            return size
        } finally {
            lock.unlock()
        }
    }

    /**
     * 读端中断（挂断/切页/播放被打断）。
     *
     * 必须能被跨线程调用：播放线程可能正阻塞在 [read] 里等数据，
     * 写线程可能正阻塞在 [write] 里等空间，这里一次性把两边都放出来，
     * 否则挂断后两个协程会一直挂在 Condition 上。
     */
    override fun close() {
        lock.lock()
        try {
            closed = true
            canRead.signalAll()
            canWrite.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /** 调用方必须持有 [lock]。 */
    private fun advance(count: Int) {
        head = (head + count) % capacity
        size -= count
    }

    override fun toString(): String {
        lock.lock()
        try {
            return "AudioPipe(size=$size/$capacity, writerDone=$writerDone, closed=$closed)"
        } finally {
            lock.unlock()
        }
    }
}
