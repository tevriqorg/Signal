package com.wb.signalpro;

import android.content.Context;
import android.os.Build;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.NetworkRegistrationInfo;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Signal - 信号参数采集(全部使用公共 API,无需 root/Shizuku)
 * 数据源:TelephonyManager.getServiceState / getNetworkOperator / getSignalStrength
 *       + getAllCellInfo + SubscriptionManager
 * v0.1.1: 大幅扩展字段(CSI 系列 / ASU / 时间提前量 / 数据活动 / 通话状态 / SIM 卡详情)
 */
public class SignalInfo {

    private static final String TAG = "Signal";
    public static final int MINV = Integer.MIN_VALUE;

    /** 邻区(含 LTE/NR) */
    public static class Cell {
        public String rat = "--";          // NR / LTE
        public String band = "--";         // n41 / b39
        public int arfcn = 0;              // 频点
        public int pci = 0;                // 基站编号
        public int rsrp = MINV;
        public int rsrq = MINV;
        public int sinr = MINV;
        public int cqi = MINV;
        public int ta = MINV;              // 时间提前量
        public int asu = MINV;
        public int bandwidthKhz = 0;
        public boolean serving = false;
    }

    /** SIM 卡信息 */
    public static class SimCard {
        public int slot = -1;
        public int subId = -1;
        public String carrierName = "--";
        public String displayName = "--";
        public String mccMnc = "--";
        public String roaming = "--";
    }

    public static class Snapshot {
        // —— 运营商 / SIM ——
        public String operator = "--";        // 网络运营商名(中国移动)
        public String operatorCode = "--";    // 网络运营商代号 (46000)
        public String simOperator = "--";     // SIM 运营商名
        public String simOperatorCode = "--"; // SIM 运营商代号
        public String netCountry = "--";      // 网络国家
        public String simCountry = "--";      // SIM 国家
        public String simState = "--";        // SIM 卡状态
        public String phoneType = "--";       // 手机类型
        // —— 网络 ——
        public String rat = "--";             // 数据 RAT
        public String ratVoice = "--";
        public String dataState = "--";
        public String voiceState = "--";
        public String roaming = "--";
        public String ca = "--";
        public String dataActivity = "--";    // 数据活动
        public String callState = "--";       // 通话状态
        public String dataEnabled = "--";     // 数据开关
        public int rejectCause = -1;          // 注册拒绝原因
        public boolean emergencyOnly = false;
        // —— 小区身份 ——
        public int pci = 0;
        public int tac = 0;
        public int nrarfcn = 0;
        public long nci = 0;
        public String bands = "--";
        public String mccMnc = "--";
        public String freqRange = "--";
        public int bandwidthKhz = 0;
        // —— 信号 SS 主 ——
        public int rsrp = MINV;
        public int rsrq = MINV;
        public int sinr = MINV;
        public int level = -1;
        public int asu = MINV;                // ASU 等级
        // —— 信号 CSI 辅助 ——
        public int csiRsrp = MINV;
        public int csiRsrq = MINV;
        public int csiSinr = MINV;
        public int csiCqiTable = -1;
        public int taMicros = MINV;           // NR 时间提前量(微秒)
        // —— 数据承载 ——
        public int maxDataCalls = 0;
        public boolean dcNrRestricted = false;
        public boolean nrAvailable = false;
        public boolean endcAvailable = false;
        public int nrVops = -1;               // -1 未知
        public int lteVops = -1;
        // —— 列表 ——
        public List<SimCard> sims = new ArrayList<>();
        public List<Cell> neighbors = new ArrayList<>();

        public String describeRsrp() {
            if (rsrp == MINV || rsrp == Integer.MAX_VALUE) return "--";
            return rsrp + " dBm";
        }
        public String describeRsrq() {
            if (rsrq == MINV || rsrq == Integer.MAX_VALUE) return "--";
            return (rsrq / 10.0) + " dB";
        }
        public String describeSinr() {
            if (sinr == MINV || sinr == Integer.MAX_VALUE) return "--";
            return (sinr / 10.0) + " dB";
        }
        public String describeLevel() {
            if (level < 0) return "--";
            String[] names = {"很差", "较差", "一般", "良好", "优秀"};
            return level + " (" + names[Math.min(level, 4)] + ")";
        }
        public String describeAsu() {
            if (asu == MINV || asu == Integer.MAX_VALUE) return "--";
            return String.valueOf(asu);
        }
        public String describeCsiRsrp() {
            if (csiRsrp == MINV || csiRsrp == Integer.MAX_VALUE) return "--";
            return csiRsrp + " dBm";
        }
        public String describeCsiRsrq() {
            if (csiRsrq == MINV || csiRsrq == Integer.MAX_VALUE) return "--";
            return (csiRsrq / 10.0) + " dB";
        }
        public String describeCsiSinr() {
            if (csiSinr == MINV || csiSinr == Integer.MAX_VALUE) return "--";
            return (csiSinr / 10.0) + " dB";
        }
        public String describeTa() {
            if (taMicros == MINV || taMicros == Integer.MAX_VALUE) return "--";
            return (taMicros / 1000.0) + " ms";
        }
    }

    public static Snapshot capture(Context ctx) {
        Snapshot s = new Snapshot();
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return s;

            // —— 运营商 / SIM ——
            try {
                s.operator = nz(tm.getNetworkOperatorName());
                s.operatorCode = nz(tm.getNetworkOperator());
                s.simOperator = nz(tm.getSimOperatorName());
                s.simOperatorCode = nz(tm.getSimOperator());
                s.netCountry = nz(tm.getNetworkCountryIso());
                s.simCountry = nz(tm.getSimCountryIso());
                s.simState = simStateName(tm.getSimState());
                s.phoneType = phoneTypeName(tm.getPhoneType());
            } catch (Throwable ignored) {}

            ServiceState ss = tm.getServiceState();
            if (ss != null) {
                s.roaming = ss.getRoaming() ? "漫游" : "未漫游";
                if (s.operator.equals("--")) {
                    s.operator = nz(ss.getOperatorAlphaLong());
                    if (s.operator.equals("--")) s.operator = nz(ss.getOperatorAlphaShort());
                }
                int[] bws = ss.getCellBandwidths();
                if (bws != null && bws.length > 0 && bws[0] > 0) s.bandwidthKhz = bws[0];
            }

            // —— 网络注册(NRI 列表)—— 
            if (ss != null) {
                List<NetworkRegistrationInfo> regs = ss.getNetworkRegistrationInfoList();
                if (regs != null) {
                    for (NetworkRegistrationInfo nri : regs) {
                        if (nri.getTransportType() != 1) continue;  // 1 = WWAN
                        int dom = nri.getDomain();
                        if (dom == NetworkRegistrationInfo.DOMAIN_PS) {
                            s.dataState = nri.isNetworkRegistered() ? "已注册"
                                    : (nri.isNetworkSearching() ? "搜网中" : "无服务");
                            s.rat = ratName(nri.getAccessNetworkTechnology());
                            s.rejectCause = nri.getRejectCause();
                        } else if (dom == NetworkRegistrationInfo.DOMAIN_CS) {
                            s.voiceState = nri.isNetworkRegistered() ? "已注册"
                                    : (nri.isNetworkSearching() ? "搜网中" : "无服务");
                            s.ratVoice = ratName(nri.getAccessNetworkTechnology());
                        }
                    }
                }
            }

            // —— 数据活动 / 通话 / 开关 ——
            try {
                s.dataActivity = dataActivityName(tm.getDataActivity());
                s.callState = callStateName(tm.getCallState());
                s.dataEnabled = tm.isDataEnabled() ? "开" : "关";
            } catch (Throwable ignored) {}

            // —— 小区信息(服务 + 邻区)—— 
            List<CellInfo> cells = tm.getAllCellInfo();
            if (cells != null) {
                for (CellInfo ci : cells) {
                    Cell c = new Cell();
                    if (ci instanceof CellInfoNr) {
                        c.rat = "NR";
                        CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) ci).getCellIdentity();
                        CellSignalStrengthNr sig = (CellSignalStrengthNr) ((CellInfoNr) ci).getCellSignalStrength();
                        if (id != null) {
                            c.band = "n" + firstBand(id.getBands());
                            c.arfcn = id.getNrarfcn();
                            c.pci = id.getPci();
                            s.nrarfcn = c.arfcn;
                            s.pci = c.pci;
                            s.tac = id.getTac();
                            s.nci = id.getNci();
                            s.bands = "n" + joinBands(id.getBands());
                            s.mccMnc = (id.getMccString() == null ? "--" : id.getMccString())
                                    + "/" + (id.getMncString() == null ? "--" : id.getMncString());
                        }
                        if (sig != null) {
                            c.rsrp = sig.getSsRsrp(); c.rsrq = sig.getSsRsrq(); c.sinr = sig.getSsSinr();
                            s.rsrp = c.rsrp; s.rsrq = c.rsrq; s.sinr = c.sinr;
                            s.level = sig.getLevel();
                            s.asu = sig.getAsuLevel();
                            // CSI 系列
                            s.csiRsrp = sig.getCsiRsrp(); s.csiRsrq = sig.getCsiRsrq(); s.csiSinr = sig.getCsiSinr();
                            s.csiCqiTable = callIntNoArg(sig, "getCsiCqiTableIndex", -1);
                            s.taMicros = callIntNoArg(sig, "getTimingAdvanceMicros", MINV);
                            c.asu = s.asu;
                        }
                    } else if (ci instanceof CellInfoLte) {
                        c.rat = "LTE";
                        CellIdentityLte id = ((CellInfoLte) ci).getCellIdentity();
                        CellSignalStrengthLte sig = ((CellInfoLte) ci).getCellSignalStrength();
                        if (id != null) {
                            c.band = "b" + firstBand(id.getBands());
                            c.arfcn = id.getEarfcn();
                            c.pci = id.getPci();
                            c.bandwidthKhz = id.getBandwidth();
                            if (s.pci == 0) {  // NR 缺失时补位
                                s.pci = c.pci;
                                s.tac = id.getTac();
                                s.bands = "b" + joinBands(id.getBands());
                                s.mccMnc = (id.getMcc() > 0 ? String.valueOf(id.getMcc()) : "--")
                                        + "/" + (id.getMnc() > 0 ? String.valueOf(id.getMnc()) : "--");
                            }
                        }
                        if (sig != null) {
                            c.rsrp = sig.getRsrp(); c.rsrq = sig.getRsrq(); c.sinr = sig.getRssnr();
                            c.cqi = sig.getCqi(); c.ta = sig.getTimingAdvance();
                            if (s.rsrp == MINV) { s.rsrp = c.rsrp; s.rsrq = c.rsrq; s.sinr = c.sinr; s.level = sig.getLevel(); s.asu = sig.getAsuLevel(); }
                        }
                    }
                    if (ci.isRegistered()) c.serving = true;
                    s.neighbors.add(c);
                }
            }

            // —— SIM 卡列表 ——
            try {
                SubscriptionManager sm = SubscriptionManager.from(ctx);
                List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
                if (subs != null) {
                    for (SubscriptionInfo si : subs) {
                        SimCard sc = new SimCard();
                        sc.slot = si.getSimSlotIndex();
                        sc.subId = si.getSubscriptionId();
                        sc.carrierName = String.valueOf(si.getCarrierName() == null ? "--" : si.getCarrierName());
                        sc.displayName = String.valueOf(si.getDisplayName() == null ? "--" : si.getDisplayName());
                        sc.mccMnc = nz(si.getMccString()) + "/" + nz(si.getMncString());
                        sc.roaming = si.getDataRoaming() == 1 ? "漫游" : "未漫游";
                        s.sims.add(sc);
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.w(TAG, "capture error: " + t);
        }
        return s;
    }

    private static int callIntNoArg(Object target, String method, int fallback) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Integer ? (Integer) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String nz(String v) {
        return (v == null || v.isEmpty()) ? "--" : v;
    }

    private static String firstBand(int[] bands) {
        if (bands == null || bands.length == 0) return "?";
        return String.valueOf(bands[0]);
    }

    private static String joinBands(int[] bands) {
        if (bands == null || bands.length == 0) return "?";
        StringBuilder sb = new StringBuilder();
        for (int b : bands) { if (sb.length() > 0) sb.append(","); sb.append(b); }
        return sb.toString();
    }

    private static String ratName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G (NR_SA)";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G (LTE)";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "3G (WCDMA)";
            case TelephonyManager.NETWORK_TYPE_GSM:
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE: return "2G (GSM)";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "未知";
            default: return "(" + type + ")";
        }
    }

    private static String simStateName(int st) {
        switch (st) {
            case TelephonyManager.SIM_STATE_READY: return "已就绪";
            case TelephonyManager.SIM_STATE_ABSENT: return "无卡";
            case TelephonyManager.SIM_STATE_PIN_REQUIRED: return "需 PIN";
            case TelephonyManager.SIM_STATE_PUK_REQUIRED: return "需 PUK";
            case TelephonyManager.SIM_STATE_NETWORK_LOCKED: return "网络锁";
            case TelephonyManager.SIM_STATE_NOT_READY: return "未就绪";
            case TelephonyManager.SIM_STATE_PERM_DISABLED: return "永久禁用";
            case TelephonyManager.SIM_STATE_CARD_IO_ERROR: return "卡片错误";
            default: return "(" + st + ")";
        }
    }

    private static String phoneTypeName(int pt) {
        switch (pt) {
            case TelephonyManager.PHONE_TYPE_GSM: return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA: return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP: return "SIP";
            case TelephonyManager.PHONE_TYPE_NONE: return "无";
            default: return "(" + pt + ")";
        }
    }

    private static String dataActivityName(int da) {
        switch (da) {
            case TelephonyManager.DATA_ACTIVITY_NONE: return "空闲";
            case TelephonyManager.DATA_ACTIVITY_IN: return "接收中";
            case TelephonyManager.DATA_ACTIVITY_OUT: return "发送中";
            case TelephonyManager.DATA_ACTIVITY_INOUT: return "收发中";
            case TelephonyManager.DATA_ACTIVITY_DORMANT: return "待机";
            default: return "(" + da + ")";
        }
    }

    private static String callStateName(int cs) {
        switch (cs) {
            case TelephonyManager.CALL_STATE_IDLE: return "空闲";
            case TelephonyManager.CALL_STATE_RINGING: return "来电";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "通话中";
            default: return "(" + cs + ")";
        }
    }
}
