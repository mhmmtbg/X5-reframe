package com.mhmmtbg.x5reframe

import android.app.Application
import com.mhmmtbg.x5reframe.ui.theme.ThemeMode

class X5App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Kayıtlı tema (Otomatik / Açık / Koyu) ilk ekrandan önce uygulanır.
        ThemeMode.apply(this)
    }
}
