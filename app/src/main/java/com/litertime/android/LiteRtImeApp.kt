package com.litertime.android

import android.app.Application
import com.litertime.android.accessibility.ScreenCaptureAccessibilityService
import com.litertime.android.settings.ImeSettingsStore
import com.litertime.android.storage.ModelStorage

class LiteRtImeApp : Application() {
    lateinit var modelStorage: ModelStorage
        private set

    lateinit var settingsStore: ImeSettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        modelStorage = ModelStorage(this)
        settingsStore = ImeSettingsStore(this)
        ScreenCaptureAccessibilityService.refreshSystemEnabledState(this)
    }
}
