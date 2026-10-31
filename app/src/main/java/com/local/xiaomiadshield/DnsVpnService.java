package com.local.xiaomiadshield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DnsVpnService extends VpnService {
    public static final String ACTION_STOP = "com.local.xiaomiadshield.STOP";
    public static final String ACTION_REFRESH_NOTIFICATION = "com.local.xiaomiadshield.REFRESH_NOTIFICATION";
    private static final int NOTIFICATION_ID = 7;
    private static final String CHANNEL_ID = "dns_filter";
    public static volatile boolean running;
    static final AtomicLong blockedThisSession = new AtomicLong();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private ParcelFileDescriptor tunnel;
    private Thread worker;
    private ScheduledExecutorService ruleScheduler;
    private volatile long lastNotificationUpdate;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_REFRESH_NOTIFICATION.equals(intent.getAction())) {
            if (active.get()) updateNotification(); else stopSelf();
            return active.get() ? START_STICKY : START_NOT_STICKY;
        }
        if (!ProtectionControl.isEnabled(this)) {
            stopVpn();
            return START_NOT_STICKY;
        }
        if (!active.get()) blockedThisSession.set(0);
        startForeground(NOTIFICATION_ID, notification());
        DomainBlocker.initialize(this);
        RuleUpdateJobService.schedule(this);
        startRuleUpdates();
        if (active.compareAndSet(false, true)) startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        try {
            Builder builder = new Builder()
                    .setSession("净界 DNS 过滤")
                    .setMtu(1500)
                    .addAddress("10.7.0.1", 32)
                    .addDnsServer("10.7.0.2")
                    .addRoute("10.7.0.2", 32)
                    .setBlocking(true);
            // 规则下载必须绕过本应用自己的 VPN，否则 HTTPS 流量会再次进入只处理
            // DNS 数据包的隧道，导致自动同步无法联网。
            excludeIfInstalled(builder, getPackageName());
            // 兼容优先的应用完全绕过净界，避免 DNS 规则影响其正常内容。
            for (CompatibilityControl.AppGroup group : CompatibilityControl.GROUPS) {
                if (!CompatibilityControl.isBypassed(this, group)) continue;
                for (String packageName : group.packages) excludeIfInstalled(builder, packageName);
            }
            tunnel = builder.establish();
            if (tunnel == null) throw new IllegalStateException("VPN permission missing");
            running = true;
            worker = new Thread(this::loop, "dns-filter");
            worker.start();
        } catch (Exception e) {
            stopVpn();
        }
    }

    private void loop() {
        byte[] buf = new byte[32767];
        try (FileInputStream in = new FileInputStream(tunnel.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(tunnel.getFileDescriptor())) {
            while (active.get()) {
                int n = in.read(buf);
                if (n <= 0) continue;
                DnsPacket.Query q = DnsPacket.parseIpv4Udp(buf, n);
                if (q == null) continue;
                boolean blocked = DomainBlocker.isBlocked(q.domain);
                byte[] dns = blocked ? DnsPacket.blockedResponse(q.dnsPayload) : resolve(q.dnsPayload);
                if (!blocked && DnsPacket.responseReferencesBlockedDomain(dns)) {
                    blocked = true;
                    dns = DnsPacket.blockedResponse(q.dnsPayload);
                }
                DnsActivityLog.record(q.domain, blocked);
                if (blocked) {
                    blockedThisSession.incrementAndGet();
                    updateNotificationIfDue();
                }
                if (dns != null) out.write(DnsPacket.wrapIpv4Udp(dns, q));
            }
        } catch (Exception ignored) {
        } finally {
            stopVpn();
        }
    }

    private byte[] resolve(byte[] request) {
        // 使用国内普通 DNS，上游不再二次过滤；只应用本地、可审计的广告域名规则。
        // 这避免公共广告 DNS 把美团等应用的共享 CDN 误判为广告。
        String[] upstreams = {"223.5.5.5", "119.29.29.29"};
        for (String host : upstreams) {
            try (DatagramSocket socket = new DatagramSocket()) {
                protect(socket);
                socket.setSoTimeout(2500);
                socket.send(new DatagramPacket(request, request.length, InetAddress.getByName(host), 53));
                byte[] response = new byte[4096];
                DatagramPacket packet = new DatagramPacket(response, response.length);
                socket.receive(packet);
                byte[] exact = new byte[packet.getLength()];
                System.arraycopy(response, 0, exact, 0, exact.length);
                return exact;
            } catch (Exception ignored) { }
        }
        return null;
    }

    private void excludeIfInstalled(Builder builder, String packageName) {
        try {
            builder.addDisallowedApplication(packageName);
        } catch (PackageManager.NameNotFoundException ignored) { }
    }

    private synchronized void startRuleUpdates() {
        if (ruleScheduler != null && !ruleScheduler.isShutdown()) return;
        ruleScheduler = Executors.newSingleThreadScheduledExecutor();
        ruleScheduler.scheduleWithFixedDelay(
                () -> RuleUpdater.updateAsync(this, false, null), 0, 6, TimeUnit.HOURS);
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "防护运行状态", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("净界本地 DNS 防护的常驻运行状态");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean details = NotificationControl.showPersistentDetails(this);
        String content = details
                ? "已拦截 " + blockedThisSession.get() + " 次 · " + profileName()
                : "本地 DNS 防护正在运行";
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.local.xiaomiadshield.R.drawable.ic_shield)
                .setContentTitle("净界防护运行中")
                .setContentText(content)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setLocalOnly(true)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private String profileName() {
        int profile = RuleUpdater.getProfile(this);
        if (profile == RuleUpdater.PROFILE_MAXIMUM) return "极强模式";
        if (profile == RuleUpdater.PROFILE_ENHANCED) return "增强模式";
        return "稳定模式";
    }

    private void updateNotificationIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastNotificationUpdate < 15_000L) return;
        lastNotificationUpdate = now;
        updateNotification();
    }

    private void updateNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification());
    }

    static void requestNotificationRefresh(android.content.Context context) {
        if (!running) return;
        try {
            Intent refresh = new Intent(context, DnsVpnService.class)
                    .setAction(ACTION_REFRESH_NOTIFICATION);
            context.startService(refresh);
        } catch (RuntimeException ignored) { }
    }

    private synchronized void stopVpn() {
        active.set(false);
        running = false;
        if (tunnel != null) {
            try { tunnel.close(); } catch (Exception ignored) { }
            tunnel = null;
        }
        if (ruleScheduler != null) {
            ruleScheduler.shutdownNow();
            ruleScheduler = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() { stopVpn(); super.onDestroy(); }
    @Override public void onRevoke() { stopVpn(); super.onRevoke(); }
}
