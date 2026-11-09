package com.kaogong.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 下载保活：把下载过程挂在「前台服务」上。
 *
 * 为什么需要它：更新包 80~135 MB，走公共反代只有几十到几百 KB/s，要跑几分钟到十几分钟。
 * 用户期间切出去（或按返回键）系统就把进程当后台任务回收，下载线程随之被杀 ——
 * 就是那个「一退出就失败」。前台服务带常驻通知，进程优先级等同可见界面，
 * 切后台、熄屏都不回收；通知里同步显示百分比，不打开 App 也能看到进度。
 *
 * 谁在用：UpdateManager 在真正开始下载前调 start()，进度变化调 update()，
 * 下载结束（成功/失败/放弃）由 set() 的终态统一调 stop()。
 */
public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    private static final String CHANNEL_ID = "shangan_download";
    private static final String CHANNEL_NAME = "下载";
    private static final int NOTI_ID = 10201;
    private static final String EXTRA_TITLE = "title";

    /** 当前实例；没有下载时是 null */
    private static volatile DownloadService cur;

    /**
     * 服务正在启动的路上（startForegroundService 已发出，onStartCommand 还没跑到）。
     *
     * 为什么需要这个标记：startForegroundService() 之后系统给 5 秒窗口要求
     * startForeground()。如果我们在窗口内就把任务干完并调 stop()，
     * 直接 stopSelf 会让系统判定「你起了前台服务却没 startForeground」，
     * 抛 ForegroundServiceDidNotStartInTimeException 弄崩整个进程。
     * 所以：还没起来的服务，stop() 只记一笔，等 onStartCommand 里
     * startForeground 之后再自我了断。
     */
    private static volatile boolean starting = false;
    private static volatile boolean stopWhenStarted = false;

    private String title = "正在下载";
    private NotificationManager nm;

    /** 幂等启动。必须从可见界面（用户点了按钮）调用，否则 Android 12+ 会抛后台启动限制。 */
    public static void start(Context ctx, String title) {
        if (ctx == null) return;
        try {
            starting = true;
            stopWhenStarted = false;
            Intent i = new Intent(ctx, DownloadService.class);
            i.putExtra(EXTRA_TITLE, title == null ? "正在下载" : title);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            // 起不来也不影响下载本身，只是没有保活
            starting = false;
            Log.w(TAG, "start fail: " + t.getMessage());
        }
    }

    /** 更新进度（0~100）。服务没起来时静默忽略。 */
    public static void update(int pct, String text) {
        DownloadService s = cur;
        if (s == null) return;
        try {
            s.notify(pct, text);
        } catch (Throwable t) {
            Log.w(TAG, "update fail: " + t.getMessage());
        }
    }

    /** 结束：撤掉通知、停掉服务。任何时候都可以调，重复调无害。 */
    public static void stop() {
        DownloadService s = cur;
        cur = null;
        if (s == null) {
            // 服务还在启动路上：别 stopSelf，否则会触发
            // ForegroundServiceDidNotStartInTimeException 崩进程
            if (starting) stopWhenStarted = true;
            return;
        }
        try {
            s.stopForeground(true);
        } catch (Throwable ignored) { }
        try {
            s.stopSelf();
        } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String t = intent.getStringExtra(EXTRA_TITLE);
            if (t != null && !t.isEmpty()) title = t;
        }
        cur = this;
        starting = false;
        try {
            ensureChannel();
            startForeground(NOTI_ID, build(0, "准备中…", true));
        } catch (Throwable t) {
            Log.w(TAG, "startForeground fail: " + t.getMessage());
        }
        // 启动之前就有人调过 stop()（任务瞬间完成的情形），现在补上
        if (stopWhenStarted) {
            stopWhenStarted = false;
            cur = null;
            try { stopForeground(true); } catch (Throwable ignored) { }
            try { stopSelf(); } catch (Throwable ignored) { }
            return START_NOT_STICKY;
        }
        // 下载由 UpdateManager 的线程负责，进程被杀就重来，不需要系统重启服务
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (cur == this) cur = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("题库和 App 更新包的下载进度");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    private void notify(int pct, String text) {
        if (nm == null) return;
        int p = Math.max(0, Math.min(100, pct));
        nm.notify(NOTI_ID, build(p, text == null ? "" : text, false));
    }

    private Notification build(int pct, String text, boolean indeterminate) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, pct, indeterminate);
        // 点通知回到 App（单实例，不会新开一个界面）
        try {
            Intent it = new Intent(this, MainActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(
                    this, 0, it,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            b.setContentIntent(pi);
        } catch (Throwable ignored) { }
        return b.build();
    }
}
