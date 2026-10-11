package com.wb.signalpro;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 增强模块 — 通过 Shizuku 获得 shell 身份(uid 2000),
 * 以 IShizukuService.newProcess 执行 dumpsys,解锁公共 API 拿不到的字段。
 * 两级模式:未授权时仅公共 API;授权后采集全量。
 */
public class ShizukuHelper {

    private static final String TAG = "ShizukuHelper";
    private static final int REQ_CODE = 1000;

    /** Shizuku 服务是否可用(binder 已接收) */
    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已授权 */
    public static boolean isGranted() {
        try {
            return Shizuku.checkSelfPermission() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 发起授权请求(需用户确认) */
    public static void request() {
        try {
            Shizuku.requestPermission(REQ_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed: " + t);
        }
    }

    /** 当前 uid(2000 = shell 身份已生效) */
    public static int getUid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 以 shell 身份执行命令,返回 stdout 全文。
     * 核心:IShizukuService.newProcess(cmd, env, dir) 是公共 AIDL 方法,
     * 直接读 ParcelFileDescriptor 输出流。
     */
    public static String exec(String[] cmd) {
        try {
            IShizukuService svc = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (svc == null) return "ERR: no service binder";
            IRemoteProcess proc = svc.newProcess(cmd, null, null);
            ParcelFileDescriptor pfd = proc.getInputStream();
            InputStream is = new FileInputStream(pfd.getFileDescriptor());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            try { is.close(); } catch (Throwable ignored) {}
            try { proc.waitFor(); } catch (Throwable ignored) {}
            return bos.toString("UTF-8");
        } catch (RemoteException e) {
            Log.w(TAG, "exec remote error: " + e);
            return "ERR: " + e;
        } catch (Throwable t) {
            Log.w(TAG, "exec error: " + t);
            return "ERR: " + t;
        }
    }

    /** 便捷:执行 dumpsys telephony.registry */
    public static String dumpTelephonyRegistry() {
        return exec(new String[]{"dumpsys", "telephony.registry"});
    }

    /** 便捷:执行 dumpsys connectivity(网络状态/IP/DNS/带宽) */
    public static String dumpConnectivity() {
        return exec(new String[]{"dumpsys", "connectivity"});
    }
}
