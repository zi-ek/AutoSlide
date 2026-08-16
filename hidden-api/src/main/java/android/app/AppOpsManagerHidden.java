package android.app;

import android.os.Build;

import androidx.annotation.RequiresApi;

import li.songe.remap.RemapType;

@RemapType(AppOpsManager.class)
public class AppOpsManagerHidden {
    public static int OP_POST_NOTIFICATION;

    public static int OP_SYSTEM_ALERT_WINDOW;

    // GKD 原文件没有这一项，是 AutoSlide 追加的。
    // 实测小米 HyperOS 会把本应用的 RUN_ANY_IN_BACKGROUND 设为 ignore，
    // 导致进程被杀后 AutoStartManagerService 直接驳回 START_STICKY 的服务重启
    // （logcat: "MIUILOG- Reject RestartService"），整条保活链路从此失效。
    public static int OP_RUN_ANY_IN_BACKGROUND;

    @RequiresApi(Build.VERSION_CODES.Q)
    public static int OP_ACCESS_ACCESSIBILITY;

    @RequiresApi(Build.VERSION_CODES.Q)
    public static String OPSTR_ACCESS_ACCESSIBILITY;

    // 14.0.0_r29 - 14.0.0_r37, 14.0.0_r50 - 17
    public static int OP_CREATE_ACCESSIBILITY_OVERLAY;

    // 14.0.0_r29 - 14.0.0_r37, 14.0.0_r50 - 17
    public static String OPSTR_CREATE_ACCESSIBILITY_OVERLAY;

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static int OP_ACCESS_RESTRICTED_SETTINGS;

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    public static String OPSTR_ACCESS_RESTRICTED_SETTINGS;

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static int OP_FOREGROUND_SERVICE_SPECIAL_USE;

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static String OPSTR_FOREGROUND_SERVICE_SPECIAL_USE;
}
