package com.wb.signalpro;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Public-API network snapshot. No root/Shizuku required.
 * The important distinction is "networks that exist" vs "the active network used by this app".
 */
public final class NetworkSnapshot {

    public static final class Snapshot {
        public String activeTransport = "--";
        public String interfaceName = "--";
        public String ipv4 = "--";
        public String ipv6 = "--";
        public String dns = "--";
        public int mtu = 0;
        public boolean metered = false;
        public boolean validated = false;
        public boolean vpn = false;
        public final List<String> networks = new ArrayList<>();

        public String summary() {
            StringBuilder sb = new StringBuilder(activeTransport);
            if (!"--".equals(interfaceName)) sb.append(" · ").append(interfaceName);
            if (vpn) sb.append(" · VPN");
            return sb.toString();
        }
    }

    private NetworkSnapshot() {}

    public static Snapshot capture(Context context) {
        Snapshot out = new Snapshot();
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return out;

        try {
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                LinkProperties lp = cm.getLinkProperties(active);
                if (caps != null) {
                    out.activeTransport = transportName(caps);
                    out.vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                    out.validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                }
                out.metered = cm.isActiveNetworkMetered();
                fillLinkProperties(out, lp);
            }
        } catch (Throwable ignored) {
        }

        try {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                LinkProperties lp = cm.getLinkProperties(network);
                String transport = caps == null ? "UNKNOWN" : transportName(caps);
                String iface = lp == null || lp.getInterfaceName() == null ? "--" : lp.getInterfaceName();
                String state = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        ? "已联网" : "未验证";
                out.networks.add(transport + " · " + iface + " · " + state);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void fillLinkProperties(Snapshot out, LinkProperties lp) {
        if (lp == null) return;
        if (lp.getInterfaceName() != null) out.interfaceName = lp.getInterfaceName();
        out.mtu = lp.getMtu();

        List<String> dns = new ArrayList<>();
        for (InetAddress address : lp.getDnsServers()) dns.add(address.getHostAddress());
        if (!dns.isEmpty()) out.dns = String.join(", ", dns);

        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress address = la.getAddress();
            if (address instanceof Inet4Address && "--".equals(out.ipv4)) {
                out.ipv4 = address.getHostAddress();
            } else if (address instanceof Inet6Address && "--".equals(out.ipv6)) {
                String raw = address.getHostAddress();
                int zone = raw == null ? -1 : raw.indexOf('%');
                out.ipv6 = zone > 0 ? raw.substring(0, zone) : raw;
            }
        }
    }

    private static String transportName(NetworkCapabilities caps) {
        List<String> names = new ArrayList<>();
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names.add("蜂窝");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names.add("Wi-Fi");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names.add("以太网");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names.add("VPN");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) names.add("蓝牙");
        if (names.isEmpty()) names.add("其他网络");
        return String.join(" + ", names);
    }
}
