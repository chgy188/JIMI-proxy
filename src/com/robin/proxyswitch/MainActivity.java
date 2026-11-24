package com.robin.proxyswitch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 代理开关。
 * 优先走“当前 WiFi 网络的静态代理”（WifiConfiguration，普通应用即可，无需特权，重启后保留）；
 * 若写不进系统全局 http_proxy（需要 WRITE_SECURE_SETTINGS），则自动退回 WiFi 配置这条路。
 */
public class MainActivity extends Activity {

    private static final String KEY_HTTP_PROXY = "http_proxy";
    private static final String DISABLED_VALUE = ":0";
    private static final String PREFS = "proxyswitch";
    private static final String P_HOST = "host";
    private static final String P_PORT = "port";
    private static final String PERM = "android.permission.WRITE_SECURE_SETTINGS";

    private EditText etHost;
    private EditText etPort;
    private TextView tvStatus;
    private Button btnTest;
    private ToggleSwitchView toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etHost = (EditText) findViewById(R.id.et_host);
        etPort = (EditText) findViewById(R.id.et_port);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        toggle = (ToggleSwitchView) findViewById(R.id.toggle);
        btnTest = (Button) findViewById(R.id.btn_test);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedHost = sp.getString(P_HOST, "");
        String savedPort = sp.getString(P_PORT, "");
        if (savedHost.length() == 0) {
            String cur = currentGlobalProxy();
            if (cur != null && cur.contains(":")) {
                String[] parts = cur.split(":");
                savedHost = parts[0];
                savedPort = parts[1];
            }
        }
        if (savedHost.length() == 0) savedHost = "192.168.0.100";
        if (savedPort == null || savedPort.length() == 0) savedPort = "7890";
        etHost.setText(savedHost);
        etPort.setText(savedPort);

        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { applyProxy(!toggle.isChecked()); }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { testProxy(); }
        });

        // 遥控器进入后焦点直接落在开关上，方向键可移到输入框/测试按钮
        toggle.post(new Runnable() {
            public void run() { toggle.requestFocus(); }
        });

        refreshStatus(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(null);
    }

    /* ================= 应用 / 取消代理 ================= */

    private void applyProxy(boolean enable) {
        String host = etHost.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();
        int port = 0;

        if (enable) {
            if (host.length() == 0 || !host.matches("[A-Za-z0-9\\.\\-]+")) {
                toast("代理地址格式不对（示例 192.168.10.173）");
                return;
            }
            try {
                port = Integer.parseInt(portStr);
            } catch (Exception e) {
                toast("端口必须是数字");
                return;
            }
            if (port < 1 || port > 65535) {
                toast("端口范围 1-65535");
                return;
            }
        }

        StringBuilder report = new StringBuilder();

        // 主路：系统全局 http_proxy（本机 test-keys，APK 用平台密钥签名，安装即授权）
        boolean globalOk = writeGlobalProxy(enable ? host + ":" + port : DISABLED_VALUE);
        if (globalOk) {
            report.append(enable ? ("系统全局代理：已开启 " + host + ":" + port) : "系统全局代理：已关闭");
        } else {
            // 兜底：写当前 WiFi 网络的静态代理
            report.append("系统全局代理写入失败，改用 WiFi 网络代理：\n");
            report.append(applyWifiProxy(enable, host, port));
        }

        if (enable) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(P_HOST, host).putString(P_PORT, portStr).commit();
            toast("已开启：" + host + ":" + port);
        } else {
            toast("已关闭代理");
        }

        refreshStatus(report.toString());
    }

    /* -------- WiFi 静态代理 -------- */

    private String applyWifiProxy(boolean enable, String host, int port) {
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return "WiFi 代理：不可用（无 WifiManager）";
        try {
            if (!wm.isWifiEnabled()) {
                return "WiFi 代理：WiFi 未开启，未设置";
            }
            WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getNetworkId() < 0) {
                return "WiFi 代理：当前未连接任何 WiFi，未设置";
            }
            List<WifiConfiguration> configs = wm.getConfiguredNetworks();
            if (configs == null || configs.isEmpty()) {
                return "WiFi 代理：读不到已保存的网络（系统限制），未设置";
            }
            WifiConfiguration target = null;
            for (WifiConfiguration c : configs) {
                if (c.networkId == info.getNetworkId()) { target = c; break; }
            }
            if (target == null) {
                String ssid = info.getSSID();
                if (ssid != null) {
                    String bare = ssid.startsWith("\"") && ssid.endsWith("\"")
                            ? ssid.substring(1, ssid.length() - 1) : ssid;
                    for (WifiConfiguration c : configs) {
                        if (c.SSID != null) {
                            String cs = c.SSID.startsWith("\"") && c.SSID.endsWith("\"")
                                    ? c.SSID.substring(1, c.SSID.length() - 1) : c.SSID;
                            if (cs.equals(bare)) { target = c; break; }
                        }
                    }
                }
            }
            if (target == null) return "WiFi 代理：找不到当前网络配置，未设置";

            if (!setProxyFields(target, enable, host, port)) {
                return "WiFi 代理：写入字段失败（" + (lastFieldError == null ? "未知" : lastFieldError) + "）";
            }

            int id = wm.updateNetwork(target);
            if (id < 0) return "WiFi 代理：写入被系统拒绝（updateNetwork=-1）";
            wm.saveConfiguration();

            // 让改动立刻生效：重新关联一次当前网络
            try {
                wm.reassociate();
            } catch (Throwable ignored) {
            }

            String ssid = target.SSID == null ? "" : target.SSID;
            return enable
                    ? "WiFi 代理（" + ssid + "）：已设为 " + host + ":" + port
                    : "WiFi 代理（" + ssid + "）：已取消";
        } catch (Throwable t) {
            return "WiFi 代理：失败 " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
    }

    /* -------- WifiConfiguration 隐藏字段的读写（反射） -------- */

    private static volatile String lastFieldError = null;

    private static boolean setProxyFields(WifiConfiguration cfg, boolean enable, String host, int port) {
        try {
            java.lang.reflect.Field fSet = field("proxySettings");
            java.lang.reflect.Field fLp = field("linkProperties");
            Class<?> psClass = fSet.getType();

            if (enable) {
                Object pi = newProxyInfo(host, port);
                android.net.LinkProperties lp = new android.net.LinkProperties();
                java.lang.reflect.Method setHttpProxy =
                        android.net.LinkProperties.class.getDeclaredMethod("setHttpProxy", pi.getClass());
                setHttpProxy.setAccessible(true);
                setHttpProxy.invoke(lp, pi);
                fLp.set(cfg, lp);
                fSet.set(cfg, psClass.getField("STATIC").get(null));
            } else {
                fLp.set(cfg, new android.net.LinkProperties());
                fSet.set(cfg, psClass.getField("NONE").get(null));
            }
            lastFieldError = null;
            return true;
        } catch (Throwable t) {
            lastFieldError = t.getClass().getSimpleName() + ": " + t.getMessage();
            android.util.Log.e("ProxySwitch", "setProxyFields failed", t);
            return false;
        }
    }

    private static Object newProxyInfo(String host, int port) throws Exception {
        Class<?> pc = Class.forName("android.net.ProxyInfo");
        java.lang.reflect.Constructor<?> c = pc.getDeclaredConstructor(String.class, int.class, String.class);
        c.setAccessible(true);
        return c.newInstance(host, port, null);
    }

    private static java.lang.reflect.Field field(String name) throws Exception {
        java.lang.reflect.Field f = WifiConfiguration.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** 返回 "host:port"；未启用返回 null */
    private static String getProxyFields(WifiConfiguration cfg) {
        try {
            Object lp = field("linkProperties").get(cfg);
            if (lp != null) {
                java.lang.reflect.Method getHttpProxy =
                        android.net.LinkProperties.class.getDeclaredMethod("getHttpProxy");
                getHttpProxy.setAccessible(true);
                Object pi = getHttpProxy.invoke(lp);
                if (pi != null) {
                    java.lang.reflect.Method gh = pi.getClass().getMethod("getHost");
                    java.lang.reflect.Method gp = pi.getClass().getMethod("getPort");
                    return gh.invoke(pi) + ":" + gp.invoke(pi);
                }
            }
        } catch (Throwable t) {
            lastFieldError = t.getClass().getSimpleName() + ": " + t.getMessage();
            android.util.Log.e("ProxySwitch", "getProxyFields failed", t);
        }
        return null;
    }

    /* -------- 系统全局代理 -------- */

    private boolean writeGlobalProxy(String value) {
        if (!hasPerm()) return false;
        try {
            Settings.Global.putString(getContentResolver(), KEY_HTTP_PROXY, value);
            try {
                sendBroadcast(new Intent("android.intent.action.PROXY_CHANGE"));
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.e("ProxySwitch", "putString failed [" + value + "]", t);
            return false;
        }
    }

    private String currentGlobalProxy() {
        try {
            return Settings.Global.getString(getContentResolver(), KEY_HTTP_PROXY);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean hasPerm() {
        return getPackageManager().checkPermission(PERM, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermDialog(Throwable t) {
        String msg = "两条路都没写成功。可以把本机设为系统应用，或用电脑 ADB 设置（整行执行）：\n\n"
                + "adb shell settings put global http_proxy " + etHost.getText().toString().trim()
                + ":" + etPort.getText().toString().trim() + "\n\n"
                + "原始错误：" + (t == null ? "unknown" : t.getClass().getSimpleName());
        try {
            new AlertDialog.Builder(this).setTitle("写入失败").setMessage(msg)
                    .setPositiveButton("知道了", null).show();
        } catch (Throwable ignored) {
        }
    }

    /* ================= 状态 ================= */

    private void refreshStatus(String extra) {
        String global = currentGlobalProxy();
        boolean enabled = !(global == null || global.length() == 0 || DISABLED_VALUE.equals(global));
        if (toggle != null) toggle.setChecked(enabled);

        String gLine = enabled ? ("当前系统代理：" + global) : "当前系统代理：未启用（直连）";

        String permLine = hasPerm() ? "写入权限：已授权" : "写入权限：未授权（本机需平台签名，或在 /system/priv-app 中安装）";

        StringBuilder sb = new StringBuilder();
        sb.append(gLine).append("\n").append(permLine).append("\n");
        sb.append("本机 IP：").append(localIp());
        if (extra != null && extra.length() > 0) sb.append("\n— ").append(extra);
        tvStatus.setText(sb.toString());
    }

    private String readWifiProxyLine() {
        WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        try {
            if (wm == null || !wm.isWifiEnabled()) return "WiFi 代理：WiFi 未开启";
            WifiInfo info = wm.getConnectionInfo();
            List<WifiConfiguration> configs = wm.getConfiguredNetworks();
            if (info == null || configs == null) return "WiFi 代理：不可读";
            for (WifiConfiguration c : configs) {
                if (c.networkId == info.getNetworkId()) {
                    String hp = getProxyFields(c);
                    if (hp != null) return "WiFi 代理：" + hp + "（" + c.SSID + "）";
                    return "WiFi 代理：未启用（" + c.SSID + "）";
                }
            }
        } catch (Throwable t) {
            return "WiFi 代理：读取失败 " + t.getClass().getSimpleName();
        }
        return "WiFi 代理：未连接";
    }

    private String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                String name = ni.getName();
                if (name != null && name.startsWith("rmnet")) continue;
                for (java.net.InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (ia instanceof java.net.Inet4Address) {
                        String ip = ia.getHostAddress();
                        if (ip != null && ip.startsWith("192.168.")) return ip;
                    }
                }
            }
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (java.net.InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (ia instanceof java.net.Inet4Address) {
                        String ip = ia.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) return ip;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "未知";
    }

    /* ================= 连通性测试 ================= */

    private void testProxy() {
        String host = etHost.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(etPort.getText().toString().trim());
        } catch (Exception e) {
            toast("端口必须是数字");
            return;
        }
        refreshStatus("正在测试 " + host + ":" + port + " …");
        btnTest.setEnabled(false);

        new Thread(new Runnable() {
            public void run() {
                String result;
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(host, port), 6000);
                    s.setSoTimeout(8000);
                    String req = "CONNECT www.gstatic.com:443 HTTP/1.1\r\n"
                            + "Host: www.gstatic.com:443\r\n\r\n";
                    s.getOutputStream().write(req.getBytes("ISO-8859-1"));
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
                    String line = br.readLine();
                    s.close();
                    if (line == null) result = "测试失败：代理无响应";
                    else if (line.indexOf(" 200") > 0) result = "测试通过，代理可用：" + line.trim();
                    else result = "测试失败：" + line.trim();
                } catch (Exception e) {
                    result = "测试失败：" + e.getClass().getSimpleName()
                            + (e.getMessage() == null ? "" : " " + e.getMessage());
                }
                final String r = result;
                runOnUiThread(new Runnable() {
                    public void run() {
                        btnTest.setEnabled(true);
                        refreshStatus(r);
                    }
                });
            }
        }).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
