package com.henrybeevers.birdy.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.henrybeevers.birdy.data.PreferencesRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic refresh. WorkManager minimum period is 15 minutes; Doze/OEM
 * battery savers may delay further. We schedule in hours (user setting),
 * floored at 15 minutes internally.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = PreferencesRepository(applicationContext)
        return try {
            val message = Refresher.run(applicationContext, prefs, prefs.current())
            Log.i(TAG, message)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Refresh failed", e)
            prefs.setStatus("Last refresh failed", e.message ?: e.javaClass.simpleName)
            // Back off and try again, but don't retry forever: the next
            // scheduled run will come round anyway.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE = "birdy_periodic_refresh"
        private const val TAG = "BirdyRefresh"

        fun enqueue(context: Context, refreshHours: Int) {
            val hours = refreshHours.coerceIn(1, 168)
            // WorkManager enforces >= 15 minutes
            val periodMinutes = (hours * 60L).coerceAtLeast(15L)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(periodMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }

        fun runOnceNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<RefreshWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
