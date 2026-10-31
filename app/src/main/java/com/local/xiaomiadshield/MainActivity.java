package com.local.xiaomiadshield;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int VPN_REQUEST = 100;
    private TextView ruleStatus;
    private TextView batteryStatus;
    private TextView advancedStatus;
    private TextView masterStatus;
    private TextView notificationStatus;
    private TextView diagnosticStatus;
    private TextView compatibilityStatus;
    private Button masterButton;
    private Switch notificationSwitch;
    private boolean refreshingNotificationSwitch;
    private final Handler stateHandler = new Handler(Looper.getMainLooper());
    private final Runnable statePoll = new Runnable() {
        @Override public void run() {
            refresh();
            stateHandler.postDelayed(this, 500);
        }
    };
    private final int green = Color.rgb(23, 107, 91);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        if (NotificationControl.showPersistentDetails(this)) {
            try { requestNotifications(); } catch (RuntimeException ignored) { }
        }
        try { DomainBlocker.initialize(this); } catch (RuntimeException ignored) { }
        if (ProtectionControl.isEnabled(this)) {
            try { RuleUpdateJobService.schedule(this); } catch (RuntimeException ignored) { }
            try {
                RuleUpdater.updateAsync(this, false, (ok, message) -> runOnUiThread(this::refresh));
            } catch (RuntimeException ignored) { }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        stateHandler.removeCallbacks(statePoll);
        stateHandler.post(statePoll);
    }

    @Override protected void onPause() {
        stateHandler.removeCallbacks(statePoll);
        super.onPause();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 247, 245));
        LinearLayout root = column(18);
        root.setPadding(dp(20), dp(28), dp(20), dp(32));
        scroll.addView(root);

        TextView title = text("净界", 30, Color.rgb(22, 38, 34));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        root.addView(text("小米 / 澎湃 OS 广告过滤工具", 14, Color.DKGRAY));

        LinearLayout master = card("总开关", "统一控制本地 VPN 和全部净界网络拦截。关闭后所有档位均停止，但规则与设置会完整保留。");
        masterStatus = statusView(); master.addView(masterStatus);
        masterButton = button("启动全部防护", v -> toggleMasterProtection());
        master.addView(masterButton); root.addView(master);

        LinearLayout notification = card("状态栏常驻", "只显示防护状态、当前档位和本次拦截次数。开启后通知不能侧滑清除；关闭详情后，防护运行期间仍保留 Android 要求的最简 VPN 服务提示。");
        notificationStatus = statusView(); notification.addView(notificationStatus);
        notificationSwitch = new Switch(this);
        notificationSwitch.setText("显示常驻状态详情");
        notificationSwitch.setTextSize(15);
        notificationSwitch.setTextColor(Color.rgb(22, 38, 34));
        notificationSwitch.setPadding(dp(4), dp(8), dp(4), dp(8));
        notificationSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!refreshingNotificationSwitch) setPersistentNotification(checked);
        });
        notification.addView(notificationSwitch);
        root.addView(notification);

        LinearLayout profile = card("① 过滤档位", "稳定模式误伤最低；增强模式加入弹窗、中国 App 和小米系统规则；极强模式覆盖最多，但更容易影响登录、支付或图片。");
        advancedStatus = statusView(); profile.addView(advancedStatus);
        profile.addView(button("选择过滤档位", v -> chooseProfile()));
        profile.addView(button("切换视频广告实验模式", v -> toggleVideoBoost()));
        root.addView(profile);

        LinearLayout rules = card("② 规则管理", "规则每天自动检查；同步失败继续使用上一次成功规则。自定义白名单优先级最高，可恢复被误伤的正常域名。");
        ruleStatus = statusView(); rules.addView(ruleStatus);
        rules.addView(button("立即同步规则", v -> syncNow()));
        rules.addView(button("编辑自定义黑名单 / 白名单", v -> editCustomRules()));
        root.addView(rules);

        LinearLayout diagnostic = card("③ 广告诊断", "记录本机最近 DNS 域名。启动 30 秒诊断后立即打开出现广告的 APP；净界会挑出疑似广告域名供你勾选测试，测试规则可一键撤销。");
        diagnosticStatus = statusView(); diagnostic.addView(diagnosticStatus);
        diagnostic.addView(button("开始 30 秒广告诊断", v -> startAdDiagnostic()));
        diagnostic.addView(button("查看诊断结果并测试拦截", v -> showDiagnosticResults()));
        diagnostic.addView(button("查看最近 DNS 请求", v -> showRecentDns()));
        diagnostic.addView(button("撤销诊断测试规则", v -> clearDiagnosticRules()));
        root.addView(diagnostic);

        LinearLayout compatibility = card("④ 应用兼容", "为常用购物、支付、社交、视频、地图和音乐 APP 单独选择策略。勾选“兼容优先”的应用将完全绕过净界；未勾选的应用正常参与 DNS 过滤。");
        compatibilityStatus = statusView(); compatibility.addView(compatibilityStatus);
        compatibility.addView(button("管理应用兼容策略", v -> manageAppCompatibility()));
        compatibility.addView(button("恢复推荐兼容设置", v -> resetAppCompatibility()));
        root.addView(compatibility);

        LinearLayout miui = card("⑤ 后台运行设置", "请允许净界后台自启动，并将电池策略设为无限制，避免澎湃 OS 清理过滤服务。");
        batteryStatus = statusView(); miui.addView(batteryStatus);
        miui.addView(button("允许后台自启动", v -> openMiuiAutostart()));
        miui.addView(button("设置电池无限制", v -> requestBatteryExemption()));
        miui.addView(button("打开净界应用详情", v -> openAppDetails()));
        root.addView(miui);

        root.addView(card("隐私与边界", "无障碍功能已完全移除。规则匹配、CNAME 检查和自定义名单都在手机本地完成；无账号、无统计、不上传浏览记录。"));
        TextView device = text("设备：" + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE, 12, Color.GRAY);
        device.setPadding(0, dp(10), 0, 0); root.addView(device);
        return scroll;
    }

    private LinearLayout card(String heading, String body) {
        LinearLayout box = column(10);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(18), 0, 0); box.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1), Color.rgb(220, 229, 225));
        box.setBackground(bg); box.setElevation(dp(2));
        TextView h = text(heading, 19, Color.rgb(22, 38, 34)); h.setTypeface(Typeface.DEFAULT, Typeface.BOLD); box.addView(h);
        box.addView(text(body, 14, Color.rgb(74, 86, 82)));
        return box;
    }

    private LinearLayout column(int gap) {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        android.graphics.drawable.ColorDrawable d = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        d.setBounds(0, 0, 0, dp(gap)); l.setDividerDrawable(d); return l;
    }

    private TextView text(String value, int sp, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setLineSpacing(0, 1.15f); return t;
    }

    private TextView statusView() { TextView t = text("", 14, green); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setTextSize(14); b.setAllCaps(false); b.setOnClickListener(listener);
        b.setTextColor(Color.WHITE); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(green));
        b.setMinHeight(dp(48)); return b;
    }

    @android.annotation.SuppressLint("SetTextI18n")
    private void refresh() {
        try {
            boolean masterEnabled = ProtectionControl.isEnabled(this);
            boolean vpn = DnsVpnService.running;
            if (!masterEnabled) {
                masterStatus.setText("○ 全部防护已关闭");
                masterButton.setText("恢复全部防护");
            } else if (vpn) {
                masterStatus.setText(String.format(Locale.getDefault(), "● 防护运行中 · 本次拦截 %d 次", DnsVpnService.blockedThisSession.get()));
                masterButton.setText("一键关闭全部防护");
            } else {
                masterStatus.setText("○ VPN 尚未启动，当前没有网络拦截");
                masterButton.setText("启动全部防护");
            }
            masterButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    masterEnabled && vpn ? Color.rgb(181, 47, 47) : green));
            boolean persistentDetails = NotificationControl.showPersistentDetails(this);
            refreshingNotificationSwitch = true;
            notificationSwitch.setChecked(persistentDetails);
            refreshingNotificationSwitch = false;
            boolean notificationAllowed = notificationsAllowed();
            if (persistentDetails && notificationAllowed) {
                notificationStatus.setText(vpn ? "● 常驻详情已显示" : "● 已开启，启动防护后显示");
            } else if (persistentDetails) {
                notificationStatus.setText("○ 已开启，但系统通知权限未允许");
            } else {
                notificationStatus.setText("○ 详情已关闭，仅保留最简 VPN 提示");
            }
            long last = RuleUpdater.lastSuccess(this);
            int count = RuleUpdater.savedCount(this);
            if (RuleUpdater.isUpdating()) {
                ruleStatus.setText("● 正在同步规则…");
            } else if (last > 0) {
                String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(last));
                ruleStatus.setText(String.format(Locale.getDefault(), "● %d 条 · 上次成功：%s", count, when));
            } else {
                ruleStatus.setText("○ 尚未同步，当前使用内置基础规则");
            }
            int profile = RuleUpdater.getProfile(this);
            String profileName = profile == RuleUpdater.PROFILE_MAXIMUM ? "极强模式"
                    : profile == RuleUpdater.PROFILE_ENHANCED ? "增强模式" : "稳定模式";
            String video = RuleUpdater.isVideoBoost(this) ? " · 视频实验已开" : "";
            advancedStatus.setText(String.format(Locale.getDefault(), "● %s%s · 自定义拦截 %d 条 · 放行 %d 条",
                    profileName, video, DomainBlocker.customBlockedCount(), DomainBlocker.customAllowedCount()));
            if (DnsActivityLog.isDiagnosticActive()) {
                long seconds = (DnsActivityLog.diagnosticRemainingMs() + 999) / 1000;
                diagnosticStatus.setText(String.format(Locale.getDefault(), "● 正在诊断 · 剩余 %d 秒 · 测试规则 %d 条",
                        seconds, DomainBlocker.diagnosticBlockedCount()));
            } else if (DnsActivityLog.hasDiagnostic()) {
                diagnosticStatus.setText(String.format(Locale.getDefault(), "● 诊断已完成 · 捕获 %d 次请求 · 测试规则 %d 条",
                        DnsActivityLog.diagnosticQueryCount(), DomainBlocker.diagnosticBlockedCount()));
            } else {
                diagnosticStatus.setText(String.format(Locale.getDefault(), "○ 尚未诊断 · 测试规则 %d 条",
                        DomainBlocker.diagnosticBlockedCount()));
            }
            int bypassed = CompatibilityControl.bypassedGroupCount(this);
            compatibilityStatus.setText(String.format(Locale.getDefault(),
                    "● 已支持 %d 组常用 APP · %d 组兼容优先 · 其余正常过滤",
                    CompatibilityControl.GROUPS.length, bypassed));
            PowerManager pm = getSystemService(PowerManager.class);
            boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            batteryStatus.setText(ignored ? "● 已允许后台持续运行" : "○ 仍可能被系统清理");
        } catch (RuntimeException ignored) { }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == VPN_REQUEST && result == RESULT_OK) startVpn();
    }

    private void startVpn() {
        ProtectionControl.setEnabled(this, true);
        RuleUpdateJobService.schedule(this);
        Intent i = new Intent(this, DnsVpnService.class);
        startForegroundService(i);
        masterStatus.postDelayed(this::refresh, 500);
    }

    private void toggleMasterProtection() {
        if (ProtectionControl.isEnabled(this) && DnsVpnService.running) {
            ProtectionControl.setEnabled(this, false);
            RuleUpdateJobService.cancel(this);
            DnsVpnService.running = false;
            try {
                Intent stop = new Intent(this, DnsVpnService.class).setAction(DnsVpnService.ACTION_STOP);
                startService(stop);
            } catch (RuntimeException ignored) { }
            refresh();
            new AlertDialog.Builder(this).setTitle("全部防护已关闭")
                    .setMessage("本地 VPN、DNS 拦截和后台更新任务均已停止。原有规则和各项模式设置已保留，重启手机后也不会自动恢复。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        ProtectionControl.setEnabled(this, true);
        RuleUpdateJobService.schedule(this);
        refresh();
        Intent prepare = VpnService.prepare(this);
        if (prepare != null) startActivityForResult(prepare, VPN_REQUEST); else startVpn();
    }

    private void syncNow() {
        ruleStatus.setText("● 正在同步规则…");
        RuleUpdater.updateAsync(this, true, (success, message) -> runOnUiThread(() -> {
            refresh();
            new AlertDialog.Builder(this).setTitle(success ? "同步完成" : "同步未完成")
                    .setMessage(message).setPositiveButton("知道了", null).show();
        }));
    }

    private void chooseProfile() {
        String[] profiles = {
                "稳定模式 — 基础广告与追踪规则，误伤最低",
                "增强模式 — 增加弹窗、中国 App 和小米系统规则",
                "极强模式 — 综合覆盖最大，误伤风险最高"
        };
        int current = RuleUpdater.getProfile(this);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择过滤档位")
                .setSingleChoiceItems(profiles, current, null)
                .setNegativeButton("取消", null)
                .setPositiveButton("应用并同步", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int selected = dialog.getListView().getCheckedItemPosition();
            if (selected < 0) return;
            dialog.dismiss();
            applyProfile(selected);
        }));
        dialog.show();
    }

    private void applyProfile(int profile) {
        RuleUpdater.setProfile(this, profile);
        DnsVpnService.requestNotificationRefresh(this);
        refresh();
        RuleUpdater.updateAsync(this, true, (success, message) -> runOnUiThread(() -> {
            refresh();
            new AlertDialog.Builder(this).setTitle(success ? "过滤档位已更新" : "规则同步未完成")
                    .setMessage(message).setPositiveButton("知道了", null).show();
        }));
    }

    private void toggleAggressive() {
        boolean enabling = !RuleUpdater.isAggressive(this);
        Runnable apply = () -> {
            RuleUpdater.setAggressive(this, enabling);
            refresh();
            RuleUpdater.updateAsync(this, true, (success, message) -> runOnUiThread(() -> {
                refresh();
                new AlertDialog.Builder(this).setTitle(success ? "防护模式已更新" : "规则同步未完成")
                        .setMessage(message).setPositiveButton("知道了", null).show();
            }));
        };
        if (!enabling) {
            apply.run();
            return;
        }
        new AlertDialog.Builder(this).setTitle("开启增强模式？")
                .setMessage("将额外启用 AdGuard 官方弹窗/强制打开域名规则，拦截更积极，也更可能误伤少数登录、支付或图片域名。遇到问题可加入白名单或切回均衡模式。")
                .setNegativeButton("取消", null).setPositiveButton("开启并同步", (d, w) -> apply.run()).show();
    }

    private void toggleChinaBoost() {
        boolean enabling = !RuleUpdater.isChinaBoost(this);
        Runnable apply = () -> {
            RuleUpdater.setChinaBoost(this, enabling);
            refresh();
            RuleUpdater.updateAsync(this, true, (success, message) -> runOnUiThread(() -> {
                refresh();
                new AlertDialog.Builder(this).setTitle(success ? "中国 App 防护已更新" : "规则同步未完成")
                        .setMessage(message).setPositiveButton("知道了", null).show();
            }));
        };
        if (!enabling) {
            apply.run();
            return;
        }
        new AlertDialog.Builder(this).setTitle("开启中国 App 广告加强？")
                .setMessage("将加入 AWAvenue 移动广告 SDK 规则和 AdRules 中国区域 DNS 规则，能补充国内 App 的开屏、横幅、插屏和部分视频广告域名，但误伤风险也会增加。")
                .setNegativeButton("取消", null).setPositiveButton("开启并同步", (d, w) -> apply.run()).show();
    }

    private void toggleXiaomiBoost() {
        boolean enabling = !RuleUpdater.isXiaomiBoost(this);
        confirmAndApplyMode(enabling, "小米系统广告加强",
                "将加入 HaGeZi 小米原生追踪规则，拦截小米系统组件、应用商店等使用的部分广告和追踪域名。可能影响云服务或内容推荐，请用白名单恢复误伤域名。",
                () -> RuleUpdater.setXiaomiBoost(this, enabling));
    }

    private void toggleVideoBoost() {
        boolean enabling = !RuleUpdater.isVideoBoost(this);
        confirmAndApplyMode(enabling, "视频广告实验模式",
                "将加入流媒体和智能电视广告域名规则。它能拦截部分贴片、开屏和视频广告，但无法区分与正片共用服务器的广告，可能导致某些视频加载失败。",
                () -> RuleUpdater.setVideoBoost(this, enabling));
    }

    private void toggleMaximum() {
        boolean enabling = !RuleUpdater.isMaximum(this);
        confirmAndApplyMode(enabling, "极强全局模式",
                "将基础规则切换为覆盖更广的 HaGeZi Multi NORMAL 综合列表，包含广告、追踪和部分恶意域名。覆盖率更高，但登录、支付、分享或验证码更可能受影响。",
                () -> RuleUpdater.setMaximum(this, enabling));
    }

    private void confirmAndApplyMode(boolean enabling, String title, String warning, Runnable change) {
        Runnable apply = () -> {
            change.run();
            refresh();
            RuleUpdater.updateAsync(this, true, (success, message) -> runOnUiThread(() -> {
                refresh();
                new AlertDialog.Builder(this).setTitle(success ? title + "已更新" : "规则同步未完成")
                        .setMessage(message).setPositiveButton("知道了", null).show();
            }));
        };
        if (!enabling) {
            apply.run();
            return;
        }
        new AlertDialog.Builder(this).setTitle("开启" + title + "？").setMessage(warning)
                .setNegativeButton("取消", null).setPositiveButton("开启并同步", (d, w) -> apply.run()).show();
    }

    private void editCustomRules() {
        LinearLayout content = column(6);
        int padding = dp(20); content.setPadding(padding, dp(8), padding, 0);
        content.addView(text("黑名单：每行一个域名，会同时拦截其子域名", 13, Color.DKGRAY));
        EditText blocked = new EditText(this);
        blocked.setHint("例如：ads.example.com"); blocked.setMinLines(4);
        blocked.setText(DomainBlocker.customBlockText(this)); content.addView(blocked);
        content.addView(text("白名单：优先级最高，用于恢复被误伤的正常域名", 13, Color.DKGRAY));
        EditText allowed = new EditText(this);
        allowed.setHint("例如：images.example.com"); allowed.setMinLines(4);
        allowed.setText(DomainBlocker.customAllowText(this)); content.addView(allowed);
        new AlertDialog.Builder(this).setTitle("自定义域名规则").setView(content)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    int[] count = DomainBlocker.saveCustom(this, blocked.getText().toString(), allowed.getText().toString());
                    refresh();
                    new AlertDialog.Builder(this).setTitle("已保存")
                            .setMessage(String.format(Locale.getDefault(), "黑名单 %d 条，白名单 %d 条。正在运行的过滤立即生效。", count[0], count[1]))
                            .setPositiveButton("知道了", null).show();
                }).show();
    }

    private void startAdDiagnostic() {
        if (!DnsVpnService.running) {
            new AlertDialog.Builder(this).setTitle("请先启动全部防护")
                    .setMessage("广告诊断依赖净界的本地 DNS 服务。先启动总开关，再开始诊断。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        DnsActivityLog.beginDiagnostic(30_000L);
        refresh();
        new AlertDialog.Builder(this).setTitle("30 秒诊断已开始")
                .setMessage("现在立即打开出现开屏或弹窗广告的 APP，并尽量不要同时打开其他 APP。30 秒后回到净界查看结果。\n\n记录只有域名、时间和是否被拦截，保存在内存中，重启净界后自动消失。")
                .setPositiveButton("去测试", null).show();
    }

    private void showDiagnosticResults() {
        if (!DnsActivityLog.hasDiagnostic()) {
            new AlertDialog.Builder(this).setTitle("还没有诊断结果")
                    .setMessage("点击“开始 30 秒广告诊断”，随后打开出现广告的 APP。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        if (DnsActivityLog.isDiagnosticActive()) {
            long seconds = (DnsActivityLog.diagnosticRemainingMs() + 999) / 1000;
            new AlertDialog.Builder(this).setTitle("诊断仍在进行")
                    .setMessage("还需等待约 " + seconds + " 秒。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        int queries = DnsActivityLog.diagnosticQueryCount();
        List<DnsActivityLog.Candidate> candidates = DnsActivityLog.diagnosticCandidates();
        if (queries < 3) {
            showNoDiagnosticCandidates("30 秒内只捕获到 " + queries + " 次 DNS 请求。广告可能已缓存、使用硬编码 IP，或使用自带 DoH 绕过系统 DNS。");
            return;
        }
        if (candidates.isEmpty()) {
            showNoDiagnosticCandidates("捕获到 " + queries + " 次请求，但没有高可信的独立广告域名。该广告可能与正常内容共域、已缓存，或由 APP 自己绘制，强行封域名容易导致功能异常。");
            return;
        }
        String[] labels = new String[candidates.size()];
        boolean[] selected = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            DnsActivityLog.Candidate c = candidates.get(i);
            labels[i] = c.domain + "  （请求 " + c.requests + " 次）";
        }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("选择要测试拦截的域名")
                .setMessage("候选项只是根据域名特征推测，并不保证都是广告。建议每次只勾选 1–2 项，异常时立即撤销。")
                .setMultiChoiceItems(labels, selected, null)
                .setNegativeButton("取消", null)
                .setPositiveButton("测试拦截所选", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            ArrayList<String> chosen = new ArrayList<>();
            for (int i = 0; i < selected.length; i++) if (selected[i]) chosen.add(candidates.get(i).domain);
            if (chosen.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("尚未选择")
                        .setMessage("请先勾选至少一个域名。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            int count = DomainBlocker.saveDiagnosticBlocks(this, chosen);
            dialog.dismiss();
            refresh();
            new AlertDialog.Builder(this).setTitle("测试拦截已生效")
                    .setMessage("已临时拦截 " + count + " 个域名。请彻底退出目标 APP 后重新打开测试；若广告仍出现，可能需要清理该 APP 缓存。出现图片、登录或支付异常时，点击“撤销诊断测试规则”。")
                    .setPositiveButton("知道了", null).show();
        }));
        dialog.show();
    }

    private void showNoDiagnosticCandidates(String reason) {
        new AlertDialog.Builder(this).setTitle("没有可安全推荐的域名")
                .setMessage(reason + "\n\nDNS 无法读取 HTTPS 内容，也不能关闭 APP 自己绘制的弹窗。可以先清理目标 APP 缓存，再重新诊断一次。")
                .setPositiveButton("知道了", null).show();
    }

    private void showRecentDns() {
        List<DnsActivityLog.Entry> recent = DnsActivityLog.recent(60);
        if (recent.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("暂无 DNS 请求")
                    .setMessage("启动全部防护并使用几个 APP 后再查看。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        StringBuilder text = new StringBuilder();
        for (DnsActivityLog.Entry entry : recent) {
            String time = android.text.format.DateFormat.format("HH:mm:ss", entry.time).toString();
            text.append(entry.blocked ? "拦截  " : "放行  ").append(time).append("  ")
                    .append(entry.domain).append('\n');
        }
        new AlertDialog.Builder(this).setTitle("最近 DNS 请求（仅本机内存）")
                .setMessage(text.toString()).setNegativeButton("关闭", null)
                .setPositiveButton("清除记录", (d, w) -> {
                    DnsActivityLog.clear();
                    refresh();
                }).show();
    }

    private void clearDiagnosticRules() {
        int count = DomainBlocker.diagnosticBlockedCount();
        DomainBlocker.clearDiagnosticBlocks(this);
        refresh();
        new AlertDialog.Builder(this).setTitle("诊断测试规则已撤销")
                .setMessage(count > 0 ? "已恢复 " + count + " 个临时域名。" : "当前没有诊断测试规则。")
                .setPositiveButton("知道了", null).show();
    }

    private void manageAppCompatibility() {
        CompatibilityControl.AppGroup[] groups = CompatibilityControl.GROUPS;
        String[] labels = new String[groups.length];
        boolean[] bypassed = new boolean[groups.length];
        for (int i = 0; i < groups.length; i++) {
            labels[i] = groups[i].label + " — 兼容优先（绕过过滤）";
            bypassed[i] = CompatibilityControl.isBypassed(this, groups[i]);
        }
        new AlertDialog.Builder(this).setTitle("选择兼容优先应用")
                .setMultiChoiceItems(labels, bypassed, (dialog, which, checked) -> bypassed[which] = checked)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存并应用", (d, w) -> {
                    for (int i = 0; i < groups.length; i++) {
                        CompatibilityControl.setBypassed(this, groups[i], bypassed[i]);
                    }
                    restartVpnForCompatibility();
                }).show();
    }

    private void resetAppCompatibility() {
        new AlertDialog.Builder(this).setTitle("恢复推荐设置？")
                .setMessage("只让美团、美团外卖和大众点评兼容优先；京东、淘宝、拼多多、支付、社交、视频、地图和音乐 APP 恢复正常过滤。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, w) -> {
                    CompatibilityControl.resetDefaults(this);
                    restartVpnForCompatibility();
                }).show();
    }

    private void restartVpnForCompatibility() {
        if (DnsVpnService.running) {
            try {
                Intent stop = new Intent(this, DnsVpnService.class).setAction(DnsVpnService.ACTION_STOP);
                startService(stop);
                stateHandler.postDelayed(this::startVpn, 700);
            } catch (RuntimeException ignored) { }
        }
        refresh();
    }

    private void openMiuiAutostart() {
        Intent[] options = {
                new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))
        };
        for (Intent i : options) {
            try { startActivity(i); return; } catch (Exception ignored) { }
        }
    }

    private void requestBatteryExemption() {
        new AlertDialog.Builder(this).setTitle("设置为无限制")
                .setMessage("在接下来的应用详情中进入“电量和性能 / 省电策略”，选择“无限制”。澎湃 OS 的这项设置无法由普通应用直接修改。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开应用详情", (d, w) -> openAppDetails()).show();
    }

    private void openAppDetails() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        i.setData(Uri.fromParts("package", getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) {
            Intent fallback = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
            startActivity(fallback);
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
        }
    }

    private void setPersistentNotification(boolean enabled) {
        NotificationControl.setPersistentDetails(this, enabled);
        if (enabled && !notificationsAllowed()) {
            requestNotifications();
            new AlertDialog.Builder(this).setTitle("允许状态栏通知")
                    .setMessage("常驻详情需要系统通知权限。若授权窗口没有出现，请在净界的通知设置中允许“防护运行状态”。系统级关闭后，净界无法自行绕过。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("打开通知设置", (d, w) -> openOwnNotificationSettings()).show();
        }
        DnsVpnService.requestNotificationRefresh(this);
        refresh();
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        NotificationChannel channel = nm.getNotificationChannel("dns_filter");
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private void openOwnNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try { startActivity(i); }
        catch (Exception ignored) { openAppDetails(); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 9) {
            DnsVpnService.requestNotificationRefresh(this);
            refresh();
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
