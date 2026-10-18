package com.jasongzy.medialab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NcmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var task: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var latestStartId = 0
    private var latestRunId = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.unlock),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        scope.launch {
            NcmBatch.state.collect { state ->
                if (state.busy)
                    getSystemService(NotificationManager::class.java)
                        .notify(ID, notification(state))
                delay(250)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == CANCEL) {
            if (NcmBatch.state.value.busy) NcmBatch.cancel() else stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(
                ID,
                notification(NcmBatch.state.value),
                if (Build.VERSION.SDK_INT >= 35)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (error: Exception) {
            NcmBatch.fail(this, error)
            stopSelf()
            return START_NOT_STICKY
        }
        latestRunId = startId
        getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        val previous = task
        task = scope.launch {
            previous?.join()
            wakeLock =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaLab:Ncm")
                    .apply {
                        acquire(6 * 60 * 60 * 1000L)
                    }
            try {
                withContext(Dispatchers.IO) { NcmBatch.execute(applicationContext) }
            } finally {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                if (latestRunId == startId) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val state = NcmBatch.state.value
                    if (
                        state.total > 0 &&
                            (Build.VERSION.SDK_INT < 33 ||
                                checkSelfPermission(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED)
                    ) {
                        getSystemService(NotificationManager::class.java)
                            .notify(
                                RESULT_ID,
                                NotificationCompat.Builder(this@NcmService, CHANNEL)
                                    .setSmallIcon(R.drawable.ic_audio)
                                    .setContentTitle(
                                        getString(
                                            if (state.completed == state.total)
                                                R.string.batch_finished
                                            else R.string.batch_stopped
                                        )
                                    )
                                    .setContentText(
                                        getString(
                                            R.string.batch_summary,
                                            state.items.count { it.status == BatchStatus.SUCCESS },
                                            state.items.count { it.status == BatchStatus.SKIPPED },
                                            state.items.count { it.status == BatchStatus.FAILED },
                                        )
                                    )
                                    .setContentIntent(openIntent())
                                    .setAutoCancel(true)
                                    .setSilent(true)
                                    .build(),
                            )
                    }
                    stopSelf(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun openIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra("batch", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notification(state: BatchState): android.app.Notification {
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, NcmService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle(getString(if (state.cancelling) R.string.cancelling else state.stage))
            .setContentText(
                if (state.total > 0)
                    getString(R.string.batch_progress, state.completed, state.total) +
                        " · " +
                        state.current
                else state.current
            )
            .setProgress(state.total, state.completed, state.total == 0)
            .setContentIntent(openIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.batch_stop), cancel)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        NcmBatch.cancel()
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ncm_processing"
        private const val ID = 2
        private const val RESULT_ID = 3
        private const val CANCEL = "com.jasongzy.medialab.CANCEL_NCM"
    }
}
