package com.local.xiaomiadshield;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

final public class RuleUpdateJobService extends JobService {
    private static final int JOB_ID = 407031;
    private static final long INTERVAL = 24L * 60 * 60 * 1000;

    static boolean schedule(Context context) {
        if (!ProtectionControl.isEnabled(context)) return false;
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler == null) return false;
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, RuleUpdateJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(INTERVAL, 4L * 60 * 60 * 1000)
                    .build();
            return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException | LinkageError ignored) {
            // 部分厂商 ROM 会限制第三方持久 Job。后台任务注册失败不能影响主界面、
            // VPN 或应用启动；打开应用及 VPN 运行期间仍会继续同步规则。
            return false;
        }
    }

    static void cancel(Context context) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancel(JOB_ID);
        } catch (RuntimeException | LinkageError ignored) { }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (!ProtectionControl.isEnabled(this)) {
            jobFinished(params, false);
            return false;
        }
        RuleUpdater.updateAsync(this, false, (success, message) -> jobFinished(params, false));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return true;
    }
}
