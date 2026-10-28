package ir.cibuilder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** وضعیت مشترک بین سرویس و صفحهٔ اپ */
object BuildState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val log = MutableStateFlow("")
    val outputs = MutableStateFlow<List<Pair<String, Uri>>>(emptyList())
}

/**
 * سرویس foreground: کامپایل حتی با بستن اپ یا خاموش شدن صفحه ادامه پیدا می‌کند
 * و در پایان یک نوتیفیکیشن با صدای آلارم و ویبره نشان می‌دهد.
 */
class BuildService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (job?.isActive == true) return START_NOT_STICKY

        val app = intent.getStringExtra("app") ?: ""
        val zip = Uri.parse(intent.getStringExtra("zip"))
        val target = intent.getStringExtra("target") ?: "apk"

        createChannels()
        startForeground(
            NOTIF_PROGRESS,
            progressNotification("شروع…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        BuildState.status.value = ""
        BuildState.log.value = ""
        BuildState.outputs.value = emptyList()
        BuildState.running.value = true

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cibuilder:build")
            .apply { acquire(6 * 60 * 60 * 1000L) }

        job = scope.launch {
            val result = try {
                BuildRunner.run(applicationContext, Store(applicationContext), app, zip, target) { line ->
                    BuildState.status.update { it + line + "\n" }
                    notify(NOTIF_PROGRESS, progressNotification(line))
                }
            } catch (e: Exception) {
                BuildResult(false, e.toString(), emptyList())
            }

            BuildState.log.value = result.log
            BuildState.outputs.value = result.files
            BuildState.running.value = false

            releaseWake()
            stopForeground(STOP_FOREGROUND_REMOVE)
            notify(NOTIF_DONE, doneNotification(app, result))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        BuildState.running.value = false
        releaseWake()
        super.onDestroy()
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    private fun notify(id: Int, n: Notification) =
        getSystemService(NotificationManager::class.java).notify(id, n)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotification(text: String): Notification =
        Notification.Builder(this, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("در حال کامپایل…")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun doneNotification(app: String, r: BuildResult): Notification =
        Notification.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (r.ok) "کامپایل $app تمام شد ✅" else "کامپایل $app با خطا تمام شد ❌")
            .setContentText(
                if (r.ok) "${r.files.size} فایل در Downloads ذخیره شد"
                else "برای دیدن خطا اپ را باز کنید"
            )
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "پیشرفت کامپایل", NotificationManager.IMPORTANCE_LOW)
        )
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val done = NotificationChannel(CH_DONE, "پایان کامپایل (آلارم)", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
        }
        nm.createNotificationChannel(done)
    }

    companion object {
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done_alarm"
        private const val NOTIF_PROGRESS = 1
        private const val NOTIF_DONE = 2

        fun start(ctx: Context, app: String, zip: Uri, target: String) {
            val i = Intent(ctx, BuildService::class.java)
                .putExtra("app", app)
                .putExtra("zip", zip.toString())
                .putExtra("target", target)
            BuildState.running.value = true
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
