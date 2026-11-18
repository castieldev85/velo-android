package eu.siacs.conversations.velo;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

import eu.siacs.conversations.R;

/**
 * Velo: the second half of a panic PIN typed offline. Android runs it when there is a network: the
 * purges leave and Velo wipes itself. A second run at the deadline wipes whatever happened.
 */
public class VeloPanicWorker extends Worker {

    private static final String SEND = "velo_panic_send";
    private static final String DEADLINE = "velo_panic_deadline";
    private static final int NOTIFICATION_ID = 31;

    public VeloPanicWorker(@NonNull final Context context, @NonNull final WorkerParameters parameters) {
        super(context, parameters);
    }

    static void schedule(final Context context, final long untilDeadlineMs) {
        final WorkManager workManager = WorkManager.getInstance(context);
        workManager.enqueueUniqueWork(
                SEND,
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(VeloPanicWorker.class)
                        .setConstraints(
                                new Constraints.Builder()
                                        .setRequiredNetworkType(NetworkType.CONNECTED)
                                        .build())
                        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                        .build());
        workManager.enqueueUniqueWork(
                DEADLINE,
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(VeloPanicWorker.class)
                        .setInitialDelay(untilDeadlineMs, TimeUnit.MILLISECONDS)
                        .build());
    }

    @NonNull
    @Override
    public Result doWork() {
        return VeloPanic.finishWaiting(getApplicationContext()) ? Result.success() : Result.retry();
    }

    @NonNull
    @Override
    public ForegroundInfo getForegroundInfo() {
        final Notification notification =
                new NotificationCompat.Builder(getApplicationContext(), "backup")
                        .setContentTitle(getApplicationContext().getString(R.string.app_name))
                        .setSmallIcon(R.drawable.ic_archive_24dp)
                        .setOngoing(true)
                        .setLocalOnly(true)
                        .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new ForegroundInfo(
                    NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        }
        return new ForegroundInfo(NOTIFICATION_ID, notification);
    }
}
