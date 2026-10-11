package com.wb.signalpro;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 dumpsys telephony.registry 输出,提取公共 API 拿不到的增强字段。
 * 真实格式(实测):key = value 或 key=value;多张卡数据重复;
 * sentinel 值 2147483647 表示未上报。grab 取第一个有效值。
 */
public class DumpsysParser {

    /** 无效值(未上报) */
    private static boolean invalid(String v) {
        if (v == null || v.isEmpty()) return true;
        if (v.equals("2147483647") || v.equals("-1") || v.equals("-2147483648")) return true;
        return false;
    }

    public static Map<String, String> parse(String dump) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dump == null || dump.length() < 100) {
            out.put("错误", "dumpsys 输出为空或过短");
            return out;
        }

        // —— 允许的网络制式(取有效值,906119 等;多卡时取非 -1 值)——
        grab(out, dump, "允许的网络制式 (TYPE)", "mAllowedNetworkTypeValue\\s*=\\s*(-?\\d+)", true);

        // —— 服务小区:CellIdentityNr 块 ——
        String cellBlock = firstBlock(dump, "CellIdentityNr");
        if (cellBlock != null) {
            grab(out, cellBlock, "基站编号 (PCI)", "mPci\\s*=\\s*(\\d+)", false);
            grab(out, cellBlock, "位置区 (TAC)", "mTac\\s*=\\s*(\\d+)", false);
            grab(out, cellBlock, "NR-ARFCN", "mNrArfcn\\s*=\\s*(\\d+)", false);
            grab(out, cellBlock, "Band 列表", "mBands\\s*=\\s*\\[([^\\]]*)\\]", false);
            grab(out, cellBlock, "NCI", "mNci\\s*=\\s*(\\d+)", false);
            grab(out, cellBlock, "MCC", "mMcc\\s*=\\s*(\\d+)", false);
            grab(out, cellBlock, "MNC", "mMnc\\s*=\\s*(\\d+)", false);
        }

        // —— NR 信号强度物理层(mNr=CellSignalStrengthNr 块)—— 
        String nrSig = firstBlock(dump, "CellSignalStrengthNr");
        if (nrSig != null) {
            grab(out, nrSig, "SS-RSRP(物理)", "ssRsrp\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "SS-RSRQ(物理)", "ssRsrq\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "SS-SINR(物理)", "ssSinr\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "CSI-RSRP(物理)", "csiRsrp\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "CSI-RSRQ(物理)", "csiRsrq\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "CSI-CQI 表", "csiCqiTableIndex\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "NR 时间提前量", "timingAdvance\\s*=\\s*(-?\\d+)", false);
            grab(out, nrSig, "NR 信号等级", "level\\s*=\\s*(\\d+)", false);
        }

        // —— 注册状态(NetworkRegistrationInfo 里找 WWAN PS 域)——
        grab(out, dump, "数据域注册 (PS)", "domain=PS transportType=WWAN[^}]*?registrationState=(\\w+)", false);
        grab(out, dump, "语音域注册 (CS)", "domain=CS transportType=WWAN[^}]*?registrationState=(\\w+)", false);
        grab(out, dump, "RAT 技术码", "getRilDataRadioTechnology\\s*=\\s*(\\d+)\\(([^)]*)\\)", true);

        // —— 数据承载能力(DataSpecificRegistrationInfo)——
        grab(out, dump, "最大数据通道 (MAX)", "maxDataCalls\\s*=\\s*(\\d+)", false);
        grab(out, dump, "NR 数据受限 (DCNR)", "isDcNrRestricted\\s*=\\s*(\\w+)", false);
        grab(out, dump, "5G 可用 (NRAVAIL)", "isNrAvailable\\s*=\\s*(\\w+)", false);
        grab(out, dump, "双连接 (ENDC)", "isEnDcAvailable\\s*=\\s*(\\w+)", false);

        // —— VoNR/VoLTE 支持 ——
        grab(out, dump, "VoNR 支持 (mVops)", "NrVopsSupportInfo\\s*:\\s*mVopsSupport\\s*=\\s*(\\d+)", false);
        grab(out, dump, "VoLTE 支持 (mVops)", "LteVopsSupportInfo\\s*:\\s*mVopsSupport\\s*=\\s*(\\d+)", false);

        // —— 第一档:注册/网络细节(已在 registry 输出中确认存在)——
        grab(out, dump, "数据活动", "mDataActivity\\s*=\\s*(-?\\d+)", true);
        grab(out, dump, "注册漫游", "mIsDataRoamingFromRegistration\\s*=\\s*(\\w+)", false);
        grab(out, dump, "IWLAN 偏好", "mIsIwlanPreferred\\s*=\\s*(\\w+)", false);
        grab(out, dump, "CS 支持 (CSS)", "mCssIndicator\\s*=\\s*(\\w+)", false);
        grab(out, dump, "LTE 附着结果", "mLteAttachResultType\\s*=\\s*(-?\\d+)", false);
        // 等效运营商列表(取第一个有效值)
        grab(out, dump, "等效运营商 (PLMN)", "mAdditionalPlmns\\s*=\\s*\\{([^}]*)\\}", false);
        // 紧急号码表
        String emg = firstBlock(dump, "mEmergencyNumberList");
        if (emg != null) out.put("紧急号码表", emg.length() > 60 ? emg.substring(0, 60) + "..." : emg);

        // —— ServiceState 外层 ——
        String ssBlock = firstBlock(dump, "mServiceState=");
        if (ssBlock != null) {
            grab(out, ssBlock, "带宽 kHz", "mCellBandwidths\\s*=\\s*\\[([^\\]]*)\\]", false);
            grab(out, ssBlock, "载波聚合 (CA)", "isUsingCarrierAggregation\\s*=\\s*(\\w+)", false);
            grab(out, ssBlock, "NR 频率范围", "mNrFrequencyRange\\s*=\\s*(\\d+)", false);
        }

        // —— 邻区统计(最近一次 mCellInfo 数组,格式 =[CellInfoNr:{...}])——
        String cellInfo = lastArray(dump, "mCellInfo=");
        if (cellInfo != null) {
            int nr = count(cellInfo, "CellInfoNr");
            int lte = count(cellInfo, "CellInfoLte");
            int wcdma = count(cellInfo, "CellInfoWcdma");
            int gsm = count(cellInfo, "CellInfoGsm");
            out.put("邻区统计", "NR=" + nr + " LTE=" + lte + " WCDMA=" + wcdma + " GSM=" + gsm);
        }

        if (out.isEmpty()) {
            out.put("提示", "未解析到增强字段(可能 dumpsys 格式不同)");
        }
        return out;
    }

    /** 提取第一个指定前缀的块(花括号内,含嵌套时截到第一个 }) */
    private static String firstBlock(String dump, String prefix) {
        Pattern p = Pattern.compile(Pattern.quote(prefix) + "\\s*\\{([^}]*)\\}");
        Matcher m = p.matcher(dump);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 提取最后一个指定前缀的数组(格式 prefix=[...],方括号内) */
    private static String lastArray(String dump, String prefix) {
        Pattern p = Pattern.compile(Pattern.quote(prefix) + "\\s*\\[([^\\]]*)\\]");
        Matcher m = p.matcher(dump);
        String found = null;
        while (m.find()) found = m.group(1);
        return found;
    }

    // ================= 第二档:dumpsys connectivity 解析 =================

    /**
     * 解析 dumpsys connectivity 输出,提取蜂窝网络状态 / IP / DNS / 带宽等。
     * 实测格式:
     *   NetworkAgentInfo{network{128} ni{MOBILE[NR] CONNECTED extra: cmnet}
     *     lp{{InterfaceName: rmnet_data3 LinkAddresses: [ 10.17.42.115/29,... ]
     *     DnsAddresses: [ /223.87.253.100,/223.87.253.253 ] MTU: 1500
     *     Routes: [ 0.0.0.0/0 -> 10.17.42.116 rmnet_data3 mtu 0 ]}}
     *     nc{[ Transports: CELLULAR ... LinkUpBandwidth>=74561Kbps
     *     LinkDnBandwidth>=74561Kbps ... VALIDATED ... ]}}
     */
    public static Map<String, String> parseConnectivity(String dump) {
        Map<String, String> out = new LinkedHashMap<>();
        if (dump == null || dump.length() < 100) {
            out.put("网络错误", "connectivity 输出为空");
            return out;
        }

        // —— 蜂窝网络状态 + APN ——
        Matcher mo = Pattern.compile("ni\\{MOBILE\\[([^\\]]*)\\]\\s*(CONNECTED|DISCONNECTED|CONNECTING)[^}]*?extra:\\s*(\\w+)").matcher(dump);
        if (mo.find()) {
            out.put("蜂窝状态", mo.group(1) + " " + mo.group(2));
            out.put("APN 接入点", mo.group(3));
        } else {
            Matcher mo2 = Pattern.compile("ni\\{MOBILE\\[([^\\]]*)\\]\\s*(CONNECTED|DISCONNECTED|CONNECTING)").matcher(dump);
            if (mo2.find()) out.put("蜂窝状态", mo2.group(1) + " " + mo2.group(2));
        }

        // —— 蜂窝网络 lp 块(接口名 rmnet 定位,含 IP/DNS/MTU/网关)——
        String mob = firstLpWith(dump, "rmnet");
        if (mob != null) {
            grab(out, mob, "运营商 IP", "LinkAddresses:\\s*\\[\\s*([0-9.]+)", false);
            grab(out, mob, "运营商 IPv6", "LinkAddresses:\\s*\\[[^\\]]*?,([0-9a-f:]+)", false);
            grab(out, mob, "DNS 服务器", "DnsAddresses:\\s*\\[\\s*/?([0-9.]+)", false);
            // IPv6 DNS:整个 DnsAddresses 列表里取含 ':' 的地址
            Matcher dm = Pattern.compile("DnsAddresses:\\s*\\[([^\\]]*)\\]").matcher(mob);
            if (dm.find()) {
                for (String tok : dm.group(1).split(",")) {
                    String t = tok.trim().replace("/", "");
                    if (t.contains(":") && !t.isEmpty()) { out.put("IPv6 DNS", t); break; }
                }
            }
            grab(out, mob, "MTU", "MTU:\\s*(\\d+)", false);
            grab(out, mob, "网关", "0\\.0\\.0\\.0/0\\s*->\\s*([0-9.]+)", false);
        }

        // —— 蜂窝带宽与验证(定位 nc{[ Transports: CELLULAR 的 NetworkAgentInfo)——
        int cellIdx = dump.indexOf("nc{[ Transports: CELLULAR");
        if (cellIdx < 0) cellIdx = dump.indexOf("nc\\{[ Transports: CELLULAR");
        if (cellIdx < 0) cellIdx = dump.indexOf("nc{ [ Transports: CELLULAR");
        if (cellIdx > 0) {
            String tail = dump.substring(cellIdx, Math.min(cellIdx + 1500, dump.length()));
            grab(out, tail, "系统带宽↑ (Kbps)", "LinkUpBandwidth>=?(\\d+)", false);
            grab(out, tail, "系统带宽↓ (Kbps)", "LinkDnBandwidth>=?(\\d+)", false);
            out.put("网络验证", tail.contains("VALIDATED") ? "已通过联网验证" : "未验证");
        }

        // —— WiFi 状态 ——
        Matcher wi = Pattern.compile("ni\\{WIFI\\s*(CONNECTED|DISCONNECTED|CONNECTING)").matcher(dump);
        if (wi.find()) {
            out.put("WiFi 状态", wi.group(1));
            Matcher ssid = Pattern.compile("SSID:\\s*\"([^\"]+)\"").matcher(dump);
            if (ssid.find()) out.put("WiFi SSID", ssid.group(1));
            Matcher rssi = Pattern.compile("RSSI:\\s*(-?\\d+)").matcher(dump);
            if (rssi.find()) out.put("WiFi 信号", rssi.group(1) + " dBm");
            Matcher freq = Pattern.compile("Frequency:\\s*(\\d+)MHz").matcher(dump);
            if (freq.find()) out.put("WiFi 频率", (Integer.parseInt(freq.group(1)) / 1000.0) + " GHz");
            Matcher speed = Pattern.compile("Link speed:\\s*(\\d+)Mbps").matcher(dump);
            if (speed.find()) out.put("WiFi 速率", speed.group(1) + " Mbps");
        }

        // —— 默认网络 ——
        Matcher def = Pattern.compile("Active default network:\\s*(\\d+)").matcher(dump);
        if (def.find()) {
            String defId = def.group(1);
            Matcher nw = Pattern.compile("network\\{" + defId + "\\}[\\s\\S]{0,400}?ni\\{(\\w+)").matcher(dump);
            out.put("默认网络", nw.find() ? nw.group(1) : "id " + defId);
        }

        if (out.isEmpty()) out.put("网络提示", "未解析到网络状态");
        return out;
    }

    /** 提取第一个含指定接口名的 lp 块(格式 lp{{ ... }},双花括号) */
    private static String firstLpWith(String dump, String iface) {
        Matcher m = Pattern.compile("lp\\{\\{([^}]*?" + iface + "[^}]*)\\}\\}").matcher(dump);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 抓取第一个有效值。若 lastOnly=true(如制式),取最后一个有效值
     * (dumpsys 多卡时 -1 的卡在前,真实值在后)。
     */
    private static void grab(Map<String, String> out, String hay, String label, String regex, boolean lastOnly) {
        Matcher m = Pattern.compile(regex).matcher(hay);
        String lastValid = null;
        while (m.find()) {
            String v = m.group(m.groupCount() == 2 ? 2 : 1).trim();
            if (!invalid(v)) {
                lastValid = v;
                if (!lastOnly) break;
            }
        }
        if (lastValid != null) out.put(label, lastValid);
    }

    private static int count(String hay, String needle) {
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) != -1) { c++; i += needle.length(); }
        return c;
    }
}
