package com.mhmmtbg.x5reframe.insv

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kameranın IMU kaydı (1 kHz). Zaman: videoya göre saniye. Jiroskop: rad/s, ivmeölçer: g.
 * Eksenler IMU'nun kendi eksenleridir; gövde (rig) eksenine [Stabilizer] çevirir.
 */
class Imu(val t: DoubleArray, val gyro: Array<DoubleArray>, val acc: Array<DoubleArray>) {
    val size get() = t.size

    companion object {
        // ExtraMetadata alanları
        private const val TAG_FIRST_FRAME_TS = 24
        private const val TAG_GYRO_TS = 28
        private const val TAG_RAW_GYRO = 62
        private const val TAG_GYRO_CFG = 65

        fun parse(record: ByteArray, meta: Map<Int, Proto.Field>): Imu {
            val raw = (meta.long(TAG_RAW_GYRO) ?: 0L) != 0L
            val cfg = meta.message(TAG_GYRO_CFG)
            val accRange = (cfg?.long(1) ?: 16L).toDouble()
            val gyroRange = (cfg?.long(2) ?: 2000L).toDouble()
            val fft = (meta.long(TAG_FIRST_FRAME_TS) ?: 0L).toDouble()
            val gyroTsMs = meta.double(TAG_GYRO_TS) ?: 0.0

            val bb = ByteBuffer.wrap(record).order(ByteOrder.LITTLE_ENDIAN)
            val item = if (raw) 8 + 6 * 2 else 8 + 6 * 8
            val n = record.size / item
            val t = DoubleArray(n)
            val g = Array(n) { DoubleArray(3) }
            val a = Array(n) { DoubleArray(3) }
            val gScale = Math.toRadians(1.0) * gyroRange / 32768.0
            val aScale = accRange / 32768.0
            for (i in 0 until n) {
                val ts = bb.long.toDouble()
                if (raw) {
                    for (k in 0..2) a[i][k] = ((bb.short.toInt() and 0xffff) - 32768) * aScale
                    for (k in 0..2) g[i][k] = ((bb.short.toInt() and 0xffff) - 32768) * gScale
                    t[i] = (ts - fft) / 1e6 - gyroTsMs / 1000.0
                } else {
                    for (k in 0..2) a[i][k] = bb.double / 9.80665
                    for (k in 0..2) g[i][k] = bb.double
                    // İşlenmiş kayıtta zaman damgası ms biriminde tutulur.
                    t[i] = (ts - fft) / 1000.0 - gyroTsMs / 1000.0
                }
            }
            return Imu(t, g, a)
        }
    }
}
