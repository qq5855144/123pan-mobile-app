package com.pan.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台保活服务：下载进行中提升进程优先级，避免挂后台被系统冻结/杀死。
 * 实际下载逻辑仍在 MainActivity 的 executor 线程池执行，本服务仅负责：
 *   1) startForeground 维持常驻通知，告知系统该进程正在执行用户可感知的前台任务；
 *   2) 进程被杀前由系统尽力保持存活（Android 对前台服务的进程优先级远高于普通后台）。
 * 目的：修复「下载文件时若 APP 挂到后台，HttpURLConnection 读流被中断导致下载失败」的问题。
 */
public class DownloadService extends Service {
    public static final String ACTION_START = "com.pan.mobile.action.DOWNLOAD_START";
    public static final String ACTION_STOP = "com.pan.mobile.action.DOWNLOAD_STOP";
    private static final String CHANNEL_ID = "pan_download_channel";
    private static final int NOTIFICATION_ID = 3001;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("下载进行中的保活通知");
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, notifyIntent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Notification n = b
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载")
            .setContentText("123云盘正在后台下载文件")
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
        startForeground(NOTIFICATION_ID, n);
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }
}
