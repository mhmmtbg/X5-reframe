package com.mhmmtbg.x5reframe.media

import android.content.Context
import com.mhmmtbg.x5reframe.gl.ReframeProgram
import com.mhmmtbg.x5reframe.insv.ClipInfo
import com.mhmmtbg.x5reframe.insv.ClipKind
import com.mhmmtbg.x5reframe.insv.MediaProbe
import java.io.FileDescriptor

/** Klibin türüne göre doğru çizim programını ve kare kaynağını seçer (360°, düz video, fotoğraf). */
object Sources {
    fun program(clip: ClipInfo, highQuality: Boolean = false): ReframeProgram =
        ReframeProgram(clip.rig, highQuality).apply { flatAspect = clip.displayAspect }

    /**
     * GL bağlamı geçerli iş parçacığından çağrılmalı.
     * @param fd [ClipInfo.uri] dosyası
     * @param lrvFd varsa 360° klibin hafif önizleme dosyası
     */
    fun open(context: Context, clip: ClipInfo, fd: FileDescriptor, realtime: Boolean, lrvFd: FileDescriptor? = null): FrameSource =
        when (clip.kind) {
            ClipKind.INSV360 -> {
                val lrv = clip.lrv
                if (lrvFd != null && lrv != null) SideBySideFrameSource(lrvFd, lrv.videoTrack, lrv.width, lrv.height)
                else DualFrameSource(fd, clip, realtime)
            }
            ClipKind.FLAT -> FlatFrameSource(fd, clip, realtime)
            ClipKind.IMAGE -> {
                // Bit eşlem kaynak tarafından tutulur: donanım tuvali onu eşzamansız çizer, kare dokuya ulaşınca bırakılır
                ImageFrameSource(MediaProbe.decodeImage(context, clip.uri), clip.durationUs, (1_000_000 / clip.frameRate).toLong())
            }
        }
}
