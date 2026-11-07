package com.mhmmtbg.x5reframe.media

/** Önizleme kare zamanlaması: gösterilen kareler arası süreler, atlanan kareler. */
class FrameStats(private val capacity: Int = 240) {
    private val intervals = LongArray(capacity)
    private var count = 0
    private var head = 0
    private var lastNs = 0L
    @Volatile var shown = 0L
        private set
    @Volatile var dropped = 0L
        private set

    data class Snapshot(val fps: Double, val p95Ms: Double, val maxMs: Double, val shown: Long, val dropped: Long) {
        override fun toString() = "%.1f fps · p95 %.0f ms · en uzun %.0f ms · atlanan %d".format(fps, p95Ms, maxMs, dropped)
    }

    @Synchronized
    fun frameShown() {
        val now = System.nanoTime()
        if (lastNs != 0L) {
            intervals[head] = now - lastNs
            head = (head + 1) % capacity
            if (count < capacity) count++
        }
        lastNs = now
        shown++
    }

    @Synchronized
    fun frameDropped() { dropped++ }

    /** Oynatma durunca bir sonraki aralık sayılmasın. */
    @Synchronized
    fun pause() { lastNs = 0L }

    @Synchronized
    fun reset() { count = 0; head = 0; lastNs = 0L; shown = 0; dropped = 0 }

    @Synchronized
    fun snapshot(): Snapshot {
        if (count == 0) return Snapshot(0.0, 0.0, 0.0, shown, dropped)
        val a = LongArray(count) { intervals[it] }
        a.sort()
        val mean = a.average()
        return Snapshot(1e9 / mean, a[(count * 95 / 100).coerceAtMost(count - 1)] / 1e6, a[count - 1] / 1e6, shown, dropped)
    }
}
