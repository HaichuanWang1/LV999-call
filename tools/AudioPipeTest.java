import com.lv999call.app.audio.AudioPipe;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM-side test for the real compiled Kotlin class (no Android APIs involved). */
public class AudioPipeTest {

    static int failures = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.printf("%s %-28s %s%n", ok ? "[PASS]" : "[FAIL]", name, detail);
        if (!ok) failures++;
    }

    static long now() { return System.currentTimeMillis(); }

    /** 1. 写入即唤醒：读端不能靠超时才发现数据（PipedInputStream 就会） */
    static void firstByteLatency() throws Exception {
        AudioPipe pipe = new AudioPipe(64 * 1024);
        final long[] wokeAt = new long[1];
        Thread reader = new Thread(() -> {
            try { pipe.read(new byte[44], 0, 44); wokeAt[0] = now(); } catch (Exception e) { }
        });
        reader.start();
        Thread.sleep(200);                       // 确保读端已经阻塞在 await 上
        long wrote = now();
        pipe.write(new byte[44], 0, 44);
        reader.join(5000);
        long latency = wokeAt[0] - wrote;
        check("write_wakes_reader", latency >= 0 && latency < 100, "reader woken " + latency + "ms after write");
    }

    /** 2. 完整性与 EOF */
    static void integrityAndEof() throws Exception {
        AudioPipe pipe = new AudioPipe(1024);
        final int chunk = 100, count = 200;
        final AtomicInteger got = new AtomicInteger();
        final byte[] firstByte = new byte[1], lastByte = new byte[1];
        final boolean[] eof = new boolean[1];
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[256];
            int n, i = 0;
            try {
                while ((n = pipe.read(buf, 0, buf.length)) != -1) {
                    for (int k = 0; k < n; k++, i++) {
                        if (i == 0) firstByte[0] = buf[k];
                        lastByte[0] = buf[k];
                    }
                    got.addAndGet(n);
                }
                eof[0] = true;
            } catch (Exception e) { }
        });
        reader.start();
        byte[] data = new byte[chunk];
        for (int i = 0; i < count; i++) {
            java.util.Arrays.fill(data, (byte) (i & 0xFF));
            pipe.write(data, 0, chunk);
        }
        pipe.closeWriter();
        reader.join(5000);
        check("integrity", got.get() == chunk * count && firstByte[0] == 0 && lastByte[0] == (byte) ((count - 1) & 0xFF),
              "received " + got.get() + "/" + (chunk * count) + " bytes, first=" + firstByte[0] + " last=" + (lastByte[0] & 0xFF));
        check("eof_after_closeWriter", eof[0], "reader saw EOF");
    }

    /** 3. 背压：容量写满后写端必须阻塞，不能把整段音频攒在内存里 */
    static void backpressure() throws Exception {
        AudioPipe pipe = new AudioPipe(4096);
        final int total = 100 * 1024;
        final long[] writerDone = new long[1];
        final AtomicInteger written = new AtomicInteger();
        Thread writer = new Thread(() -> {
            byte[] chunk = new byte[1024];
            try { for (int i = 0; i < 100; i++) { pipe.write(chunk, 0, chunk.length); written.addAndGet(chunk.length); } }
            catch (Exception e) { }
            writerDone[0] = now();
            pipe.closeWriter();
        });
        long t0 = now();
        writer.start();
        Thread.sleep(300);                        // 读端慢启动
        long beforeRead = writerDone[0];
        int producedAtMark = written.get();       // 必须先快照，后面会被读端追平
        byte[] buf = new byte[1024];
        int n; long read = 0;
        while ((n = pipe.read(buf, 0, buf.length)) != -1) read += n;
        check("backpressure_blocks", beforeRead == 0 && producedAtMark <= 4096 + 1024,
              "after 300ms writer was still blocked, produced " + producedAtMark + "B (capacity 4096B)");
        check("backpressure_all_bytes", read == total, "reader got " + read + "/" + total);
        check("writer_joined", !writer.isAlive(), "writer thread finished");
        check("elapsed_bounded", now() - t0 < 5000, "elapsed " + (now() - t0) + "ms");
    }

    /** 4. 读端 close（挂断）必须立刻放掉被阻塞的写端，不能让线程挂在锁上 */
    static void closeUnblocksWriter() throws Exception {
        final AudioPipe pipe = new AudioPipe(4096);
        final long[] excAt = new long[1];
        final String[] excMsg = new String[1];
        Thread writer = new Thread(() -> {
            byte[] chunk = new byte[1024];
            try { while (true) pipe.write(chunk, 0, chunk.length); }
            catch (Exception e) { excAt[0] = now(); excMsg[0] = e.getClass().getSimpleName() + ": " + e.getMessage(); }
        });
        writer.start();
        Thread.sleep(200);                       // 写端此刻阻塞在「缓冲区满」
        long closed = now();
        pipe.close();
        writer.join(3000);
        long wake = excAt[0] - closed;
        check("close_unblocks_writer", excAt[0] != 0 && wake < 200,
              "writer threw after " + (excAt[0] == 0 ? ">3000" : String.valueOf(wake)) + "ms, " + excMsg[0]);
        check("writer_thread_exits", !writer.isAlive(), "writer thread dead");
    }

    /** 5. 读端 close 也要能叫醒阻塞在 read 上的播放线程 */
    static void closeUnblocksReader() throws Exception {
        final AudioPipe pipe = new AudioPipe(4096);
        final long[] retAt = new long[1];
        final int[] ret = new int[1];
        Thread reader = new Thread(() -> {
            try { ret[0] = pipe.read(new byte[256], 0, 256); retAt[0] = now(); } catch (Exception e) { retAt[0] = now(); ret[0] = -2; }
        });
        reader.start();
        Thread.sleep(200);
        long closed = now();
        pipe.close();
        reader.join(3000);
        long wake = retAt[0] - closed;
        check("close_unblocks_reader", retAt[0] != 0 && wake < 200 && ret[0] == -1,
              "read returned " + ret[0] + " after " + (retAt[0] == 0 ? ">3000" : String.valueOf(wake)) + "ms");
    }

    /** 6. 有数据就立刻返回，不等读满 */
    static void partialRead() throws Exception {
        AudioPipe pipe = new AudioPipe(4096);
        final int[] n = new int[1];
        final long[] at = new long[1];
        Thread reader = new Thread(() -> {
            try { n[0] = pipe.read(new byte[4096], 0, 4096); at[0] = now(); } catch (Exception e) { }
        });
        reader.start();
        Thread.sleep(200);
        long wrote = now();
        pipe.write(new byte[100], 0, 100);
        reader.join(5000);
        check("partial_read_immediate", n[0] == 100 && at[0] - wrote < 100,
              "read returned " + n[0] + " bytes after " + (at[0] - wrote) + "ms (requested 4096)");
    }

    /** 7. 环形缓冲区回绕后数据仍然正确 */
    static void wrapAround() throws Exception {
        AudioPipe pipe = new AudioPipe(32);
        final byte[] out = new byte[8 * 100];
        final AtomicInteger got = new AtomicInteger();
        Thread reader = new Thread(() -> {
            try {
                int n, off = 0;
                while (off < out.length && (n = pipe.read(out, off, out.length - off)) != -1) off += n;
                got.set(off);
            } catch (Exception e) { }
        });
        reader.start();
        for (int i = 0; i < 100; i++) {
            byte[] b = new byte[8];
            for (int k = 0; k < 8; k++) b[k] = (byte) (i * 8 + k);
            pipe.write(b, 0, 8);
        }
        pipe.closeWriter();
        reader.join(5000);
        boolean ok = got.get() == out.length;
        for (int i = 0; ok && i < out.length; i++) ok = out[i] == (byte) i;
        check("wrap_around_ring", ok, "got " + got.get() + "/" + out.length + " bytes, ring data intact=" + ok);
    }

    public static void main(String[] args) throws Exception {
        firstByteLatency();
        integrityAndEof();
        backpressure();
        closeUnblocksWriter();
        closeUnblocksReader();
        partialRead();
        wrapAround();
        System.out.println();
        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILURES"));
        if (failures != 0) System.exit(1);
    }
}
