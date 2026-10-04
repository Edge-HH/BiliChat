package com.bilichat.app.notify

import android.app.job.JobParameters
import android.app.job.JobService
import com.bilichat.app.BiliChatApplication
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 省电模式的定期任务：由系统安排时间执行一次新消息检查。 */
class MessageCheckJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as BiliChatApplication).container
        running = container.appScope.launch {
            try {
                container.scheduler.onJob()
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }
}
