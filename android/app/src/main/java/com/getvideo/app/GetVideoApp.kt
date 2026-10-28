package com.getvideo.app

import android.app.Application
import com.getvideo.app.sync.SyncWorker

class GetVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.getvideo.app.data.repo.ApiProvider.initialize(this)
        com.getvideo.app.data.repo.DiagnosticsStore.initialize(this)
        // 周期性同步：每 6 小时，仅在 WiFi 下
        SyncWorker.schedule(this)
        // 提取失败报告：启动时补传上次没发出去的那些
        com.getvideo.app.sync.FailureReportWorker.schedule(this)
    }
}
