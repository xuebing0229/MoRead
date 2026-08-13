package com.mozhi.reader

import android.app.Application
import android.os.Process
import com.mozhi.reader.core.backup.BackupRestoreBootstrap
import com.mozhi.reader.feature.importer.BookTextMaterializeWorker
import dagger.hilt.android.HiltAndroidApp
@HiltAndroidApp
class MoReadApplication : Application() {
    override fun onCreate() {
        if (Process.isIsolated()) {
            // AndroidX PDF 的文档服务运行在 isolatedProcess。这里没有普通应用的用户存储
            // 与 WorkManager 环境，只需让 Application/Hilt 完成最小初始化。
            super.onCreate()
            return
        }
        // 待恢复包必须先于 Hilt component 创建；否则 Room/DataStore 可能已经持有旧文件句柄。
        BackupRestoreBootstrap.applyPending(this)
        super.onCreate()
        // 正文补齐仍需启动兜底；向量索引改为按需（首次检索时按书触发），不再全库补扫。
        BookTextMaterializeWorker.enqueueStartup(this)
    }
}
