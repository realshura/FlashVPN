package nova.fast.free.vpn.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.TrafficStats;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.support.v4.app.NotificationCompat;

import nova.fast.free.vpn.NovaUser;
import nova.fast.free.vpn.R;
import nova.fast.free.vpn.core.ProxyConfig.IPAddress;
import nova.fast.free.vpn.dns.DnsPacket;
import nova.fast.free.vpn.network.ServerInfo;
import nova.fast.free.vpn.network.VPNServerManager;
import nova.fast.free.vpn.tcpip.CommonMethods;
import nova.fast.free.vpn.tcpip.IPHeader;
import nova.fast.free.vpn.tcpip.TCPHeader;
import nova.fast.free.vpn.tcpip.UDPHeader;
import nova.fast.free.vpn.ui.HomeActivity;
import nova.fast.free.vpn.utils.CommonUtils;
import nova.fast.free.vpn.utils.MLogs;
import nova.fast.free.vpn.utils.PreferenceUtils;
import nova.fast.free.vpn.utils.RemoteConfig;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static android.os.Build.VERSION_CODES.O;

public class LocalVpnService extends VpnService implements Runnable {

    public static LocalVpnService Instance;
    public static String ProxyUrl;
    public static boolean IsRunning = false;

    private static int ID;
    private static int LOCAL_IP;
    private static ConcurrentHashMap<onStatusChangedListener, Object> m_OnStatusChangedListeners = new ConcurrentHashMap<onStatusChangedListener, Object>();

    private Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private DnsProxy m_DnsProxy;
    private FileOutputStream m_VPNOutputStream;

    private byte[] m_Packet;
    private IPHeader m_IPHeader;
    private TCPHeader m_TCPHeader;
    private UDPHeader m_UDPHeader;
    private ByteBuffer m_DNSBuffer;
    private Handler m_Handler;
    private long m_SentBytes;
    private long m_ReceivedBytes;
    private int NOTIFY_ID = 10001;
    private long lastSpeedCalTime;
    private long lastDownBytes;
    private long lastUpBytes;
    private final int MSG_UPDATE_NOTIFICATION = 100;

    private float mAvgDownloadSpeed;
    private float mAvgUploadSpeed;
    private float mMaxDownloadSpeed;
    private float mMaxUploadSpeed;

    private final String CONF_VIP_SPEED_BOOST = "conf_vip_speed_boost";
    private final String CONF_NORMAL_SPEED_BOOST = "conf_normal_speed_boost";

    public LocalVpnService() {
        ID++;
        m_Handler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case MSG_UPDATE_NOTIFICATION:
                        updateNotification();
                        sendMessageDelayed(obtainMessage(MSG_UPDATE_NOTIFICATION), 5000);
                        break;
                }
            }
        };
        m_Packet = new byte[20000];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

        MLogs.d("New VPNService(%d)\n"+ ID);
    }

    @Override
    public void onCreate() {
        MLogs.d("VPNService(%s) created.\n" + ID);
        // Start a new session by creating a new thread.
        m_VPNThread = new Thread(this, "VPNServiceThread");
        m_VPNThread.start();
        m_Handler.sendMessageDelayed(m_Handler.obtainMessage(MSG_UPDATE_NOTIFICATION), 5000);
        super.onCreate();
    }

    private void resetSpeeds() {
        mAvgDownloadSpeed = 0;
        mAvgUploadSpeed = 0;
        mMaxDownloadSpeed = 0;
        mMaxUploadSpeed = 0;
    }

    private void recalculateSpeed(float currentDownloadSpeed, float currentUploadSpeed) {
        if (currentDownloadSpeed > mMaxDownloadSpeed) {
            mMaxDownloadSpeed = currentDownloadSpeed;
        }
        if (currentUploadSpeed > mMaxUploadSpeed) {
            mMaxUploadSpeed = currentUploadSpeed;
        }
        mAvgUploadSpeed = (mAvgUploadSpeed + currentUploadSpeed)/2;
        mAvgDownloadSpeed = (mAvgDownloadSpeed + currentDownloadSpeed)/2;
    }

    private void updateNotification() {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
//                if (Build.VERSION.SDK_INT >= 26) {
                Intent intent = new Intent(Instance, HomeActivity.class);
                PendingIntent pendingIntent = PendingIntent.getActivity(Instance, 0, intent, 0);
                Notification notification;
                NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                String channel_id = "_id_service_";
                if (Build.VERSION.SDK_INT >= 26 &&
                        notificationManager.getNotificationChannel(channel_id) == null) {
                    int importance = NotificationManager.IMPORTANCE_HIGH;
                    NotificationChannel notificationChannel = new NotificationChannel(channel_id, "Nova VPN", importance);
//                notificationChannel.enableVibration(false);
                    notificationChannel.enableLights(false);
//                notificationChannel.setVibrationPattern(new long[]{0});
                    notificationChannel.setSound(null, null);
                    notificationChannel.setDescription("Nova VPN information");
                    notificationChannel.setShowBadge(false);
                    //notificationChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
                    notificationManager.createNotificationChannel(notificationChannel);
                }
                NotificationCompat.Builder mBuilder =  new NotificationCompat.Builder(Instance, channel_id);
                String title = IsRunning ? getString(R.string.notification_connected):getString(R.string.notification_to_connect);
                int id = PreferenceUtils.getPreferServer();
                ServerInfo si = id == ServerInfo.SERVER_ID_AUTO ? VPNServerManager.getInstance(LocalVpnService.this).getBestServer():
                        VPNServerManager.getInstance(LocalVpnService.this).getServerInfo(id);
                float[] speed = getNetworkSpeed();
                DecimalFormat format = new DecimalFormat("0.0");
                String downSpeed = format.format((speed[0] > 1000)? speed[0]/1000:speed[0]);
                downSpeed += (speed[0] > 1000) ? "KB/s":"Byte/s";
                String upSpeed = format.format((speed[1] > 1000)? speed[1]/1000:speed[1]);
                upSpeed += (speed[1] > 1000) ? "KB/s":"Byte/s";

                mBuilder.setContentTitle(title)
                        .setContentText("Down " + downSpeed + " Up " + upSpeed)
                        .setSmallIcon(si.getFlagResId())
                        .setContentIntent(pendingIntent);
                notification = mBuilder.build();
                notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
                try {
                    startForeground(NOTIFY_ID, notification);

                }catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
//            }
        });

    }

    private float[] getRealNetworkSpeed() {
        return getNetworkSpeedInternal(false);
    }

    private float[] getNetworkSpeed() {
        return getNetworkSpeedInternal(true);
    }

    private float[] getNetworkSpeedInternal(boolean tryBoost) {
        float boost = 1.0f;
        if (IsRunning) {
            boost = (tryBoost && (NovaUser.getInstance(this).isVIP() || NovaUser.getInstance(this).getFreePremiumSeconds() > 0))
                    ? ((float) RemoteConfig.getLong(CONF_VIP_SPEED_BOOST)) / 100
                    : ((float) RemoteConfig.getLong(CONF_NORMAL_SPEED_BOOST)) / 100;
        }
        long current = System.currentTimeMillis();
        long sent = TrafficStats.getTotalTxBytes();
        long received = TrafficStats.getTotalRxBytes();

        float realDownloadSpeed = ((float)(received - lastDownBytes))*1000/(current - lastSpeedCalTime);
        float realUploadSpeed = ((float)(sent - lastUpBytes))*1000/(current - lastSpeedCalTime);;
        recalculateSpeed(realDownloadSpeed, realUploadSpeed);

        float[] result = new float[2];
        result[0] = ((float)(received - lastDownBytes))*boost*1000/(current - lastSpeedCalTime);
        result[1] = ((float)(sent - lastUpBytes))*boost*1000/(current - lastSpeedCalTime);
        lastSpeedCalTime = current;
        lastUpBytes = sent;
        lastDownBytes = received;
        return result;
    }

    @Override
    public int onStartCommand(Intent cmd, int flags, int startId) {
        updateNotification();
        IsRunning = true;
        return super.onStartCommand(cmd, flags, startId);
    }

    public interface onStatusChangedListener {
        public void onStatusChanged(String status, Boolean isRunning, float avgDownloadSpeed, float avgUploadSpeed,
                                    float maxDownloadSpeed, float maxUploadSpeed);

        public void onLogReceived(String logString);
    }

    public static void addOnStatusChangedListener(onStatusChangedListener listener) {
        if (!m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.put(listener, 1);
        }
    }

    public static void removeOnStatusChangedListener(onStatusChangedListener listener) {
        if (m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.remove(listener);
        }
    }

    private void onStatusChanged(final String status, final boolean isRunning,
                                final float avgDownloadSpeed, final float avgUploadSpeed,
                                 final float maxDownloadSpeed, final float maxUploadSpeed) {
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onStatusChanged(status, isRunning, avgDownloadSpeed, avgUploadSpeed,
                                                                        maxDownloadSpeed, maxUploadSpeed);
                }
            }
        });
    }

    public void writeLog(final String format, Object... args) {
        final String logString = String.format(format, args);
        m_Handler.post(new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                    entry.getKey().onLogReceived(logString);
                }
            }
        });
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getAppInstallID() {
        SharedPreferences preferences = getSharedPreferences("SmartProxy", MODE_PRIVATE);
        String appInstallID = preferences.getString("AppInstallID", null);
        if (appInstallID == null || appInstallID.isEmpty()) {
            appInstallID = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString("AppInstallID", appInstallID);
            editor.apply();
        }
        return appInstallID;
    }

    String getVersionName() {
        try {
            PackageManager packageManager = getPackageManager();
            // getPackageName()是你当前类的包名，0代表是获取版本信息
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            String version = packInfo.versionName;
            return version;
        } catch (Exception e) {
            return "0.0";
        }
    }

    @Override
    public synchronized void run() {
        try {
            MLogs.d("VPNService(%s) work thread is runing...\n"+ ID);

            ProxyConfig.AppInstallID = getAppInstallID();//获取安装ID
            ProxyConfig.AppVersion = getVersionName();//获取版本号
            MLogs.d("AppInstallID: %s\n", ProxyConfig.AppInstallID);
            MLogs.d("Android version: %s", Build.VERSION.RELEASE);
            MLogs.d("App version: %s", ProxyConfig.AppVersion);


            ChinaIpMaskManager.loadFromFile(getResources().openRawResource(R.raw.ipmask));//加载中国的IP段，用于IP分流。
            waitUntilPreapred();//检查是否准备完毕。

            MLogs.d("Load config from file ...");
            try {
                ProxyConfig.Instance.loadFromFile(getResources().openRawResource(R.raw.config));
                MLogs.d("Load done");
            } catch (Exception e) {
                String errString = e.getMessage();
                if (errString == null || errString.isEmpty()) {
                    errString = e.toString();
                }
                MLogs.d("Load failed with error: %s", errString);
            }
            if (!getPackageName().contains("fast.free")){
                System.exit(0);
            }

            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();
            MLogs.d("LocalTcpServer started.");

            m_DnsProxy = new DnsProxy();
            m_DnsProxy.start();
            MLogs.d("LocalDnsProxy started.");

            while (true) {
                if (IsRunning) {
                    //加载配置文件

                    MLogs.d("set app_icon/(http proxy)");
                    try {
                        ProxyConfig.Instance.m_ProxyList.clear();
                        int id = PreferenceUtils.getPreferServer();
                        String url;
                        if (id == ServerInfo.SERVER_ID_AUTO) {
                            url = VPNServerManager.getInstance(LocalVpnService.this).getBestServer().url;
                        } else {
                            url = VPNServerManager.getInstance(LocalVpnService.this).getServerInfo(id).url;
                        }
                        ProxyUrl = url;
                        ProxyConfig.Instance.addProxyToList(url);
                        MLogs.d("Proxy is:  " + ProxyConfig.Instance.getDefaultProxy());
                    } catch (Exception e) {
                        ;
                        String errString = e.getMessage();
                        if (errString == null || errString.isEmpty()) {
                            errString = e.toString();
                        }
                        IsRunning = false;
                        onStatusChanged(errString, false, mAvgDownloadSpeed, mAvgUploadSpeed, mMaxDownloadSpeed, mMaxUploadSpeed);
                        continue;
                    }
                    String welcomeInfoString = ProxyConfig.Instance.getWelcomeInfo();
                    if (welcomeInfoString != null && !welcomeInfoString.isEmpty()) {
                        MLogs.d("%s", ProxyConfig.Instance.getWelcomeInfo());
                    }
                    MLogs.d("Global mode is " + (ProxyConfig.Instance.globalMode ? "on" : "off"));

                    runVPN();
                } else {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            System.out.println(e);
        } catch (Exception e) {
            e.printStackTrace();
            MLogs.d("Fatal error: %s", e.toString());
        } finally {
            MLogs.d("App terminated.");
            dispose();
        }
    }

    private void runVPN() throws Exception {
        this.m_VPNInterface = establishVPN();
        this.m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
        FileInputStream in = new FileInputStream(m_VPNInterface.getFileDescriptor());
        int size = 0;
        long last = System.currentTimeMillis();
        long start = System.currentTimeMillis();
        while (size != -1 && IsRunning) {
            while ((size = in.read(m_Packet)) > 0 && IsRunning) {
                if (m_DnsProxy.Stopped || m_TcpProxyServer.Stopped) {
                    in.close();
                    throw new Exception("LocalServer stopped.");
                }
                onIPPacketReceived(m_IPHeader, size);
                long deduct = 0;
                long current = System.currentTimeMillis();
                if (current - last > 1000) {
                    deduct = (current - last) / 1000;
                    NovaUser.getInstance(LocalVpnService.this).costFreePremiumSec(deduct);
                }
                if (deduct > 0) {
                    last = current - (current - last - deduct*1000);
                }
            }
            Thread.sleep(20);
            long deduct = 0;
            long current = System.currentTimeMillis();
            if (current - last > 1000) {
                deduct = (current - last) / 1000;
                NovaUser.getInstance(LocalVpnService.this).costFreePremiumSec(deduct);
            }
            if (deduct > 0) {
                last = current - (current - last - deduct*1000);
            }
            if (current - start > 10000) {
                PreferenceUtils.addConnectedTimeSec((current - start) / 1000);
                start = current;
            }
        }
        in.close();
        disconnectVPN();
        PreferenceUtils.addReceiveBytes(m_ReceivedBytes);
        PreferenceUtils.addSentBytes(m_SentBytes);
        updateNotification();
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {// 收到本地TCP服务器数据
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        } else {
                            MLogs.d("NoSession:  " + ipHeader.toString() + " " + tcpHeader.toString());
                        }
                    } else {

                        // 添加端口映射
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                        }

                        session.LastNanoTime = System.nanoTime();
                        session.PacketSent++;//注意顺序

                        int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
                        if (session.PacketSent == 2 && tcpDataSize == 0) {
                            return;//丢弃tcp握手的第二个ACK报文。因为客户端发数据的时候也会带上ACK，这样可以在服务器Accept之前分析出HOST信息。
                        }

                        //分析数据，找到host
                        if (session.BytesSent == 0 && tcpDataSize > 10) {
                            int dataOffset = tcpHeader.m_Offset + tcpHeader.getHeaderLength();
                            String host = HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
                            if (host != null) {
                                session.RemoteHost = host;
                            } else {
                                MLogs.d("No host name found: %s", session.RemoteHost);
                            }
                        }

                        // 转发给本地TCP服务器
                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;//注意顺序
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                // 转发DNS数据包：
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                    m_DNSBuffer.clear();
                    m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                    DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                    if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                        m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                    }
                }
                break;
        }
    }

    private void waitUntilPreapred() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private ParcelFileDescriptor establishVPN() throws Exception {
        resetSpeeds();

        Builder builder = new Builder();
        builder.setMtu(ProxyConfig.Instance.getMTU());
        if (ProxyConfig.IS_DEBUG)
            MLogs.d("setMtu: "  + ProxyConfig.Instance.getMTU());

        IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        if (ProxyConfig.IS_DEBUG)
            MLogs.d("addAddress: " + ipAddress.Address + "\\" + ipAddress.PrefixLength);

        for (ProxyConfig.IPAddress dns : ProxyConfig.Instance.getDnsList()) {
            builder.addDnsServer(dns.Address);
            if (ProxyConfig.IS_DEBUG)
                MLogs.d("addDnsServer:  "+dns.Address);
        }

        if (ProxyConfig.Instance.getRouteList().size() > 0) {
            for (ProxyConfig.IPAddress routeAddress : ProxyConfig.Instance.getRouteList()) {
                builder.addRoute(routeAddress.Address, routeAddress.PrefixLength);
                if (ProxyConfig.IS_DEBUG)
                    MLogs.d("addRoute: "+routeAddress.Address + "/" +routeAddress.PrefixLength);
            }
            builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);

            if (ProxyConfig.IS_DEBUG)
                MLogs.d("addRoute for FAKE_NETWORK: " + CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP) + "/" +16);
        } else {
            builder.addRoute("0.0.0.0", 0);
            if (ProxyConfig.IS_DEBUG)
                MLogs.d("addDefaultRoute: 0.0.0.0/0\n");
        }


        Class<?> SystemProperties = Class.forName("android.os.SystemProperties");
        Method method = SystemProperties.getMethod("get", new Class[]{String.class});
        ArrayList<String> servers = new ArrayList<String>();
        for (String name : new String[]{"net.dns1", "net.dns2", "net.dns3", "net.dns4",}) {
            String value = (String) method.invoke(null, name);
            if (value != null && !"".equals(value) && !servers.contains(value)) {
                servers.add(value);
                if (value.replaceAll("\\d", "").length() == 3){//防止IPv6地址导致问题
                    builder.addRoute(value, 32);
                } else {
                    builder.addRoute(value, 128);
                }
                if (ProxyConfig.IS_DEBUG)
                    MLogs.d(name + "= " + value);
            }
        }

//                * By default, all applications are allowed access, except for those denied through this
//                * method.  Denied applications will use networking as if the VPN wasn't running.
//                *
//         * A {@link Builder} may have only a set of allowed applications OR a set of disallowed
//                * ones, but not both. Calling this method after {@link #addAllowedApplication} has already
//         * been called, or vice versa, will throw an {@link UnsupportedOperationException}.
//         *
//         * {@code packageName} must be the canonical name of a currently installed application.
//         * {@link PackageManager.NameNotFoundException} is thrown if there's no such application.
//                *
//         * @throws {@link PackageManager.NameNotFoundException} If the application isn't installed.
//                *
        if (AppProxyManager.isLollipopOrAbove){
            if (AppProxyManager.Instance.proxyAppInfo.size() == 0
                    || PreferenceUtils.isGlobalVPN()){
                MLogs.d("Proxy All Apps");
                //add disallow
                final Set<String> blockedApp = CommonUtils.getBlockedApps();
                for(String s: blockedApp) {
                    PackageManager pm =getPackageManager();
                    PackageInfo pi = null;
                    try{
                        pi = pm.getPackageInfo(s, 0);
                    }catch (Exception ex) {

                    }
                    if (pi != null) {
                        builder.addDisallowedApplication(s);
                    }
                }

            } else {
                builder.addAllowedApplication(getPackageName());//需要把自己加入代理，不然会无法进行网络连接
                for (AppInfo app : AppProxyManager.Instance.proxyAppInfo) {
                    try {
                        builder.addAllowedApplication(app.getPkgName());
                        MLogs.d("Proxy App: " + app.getAppLabel());
                    } catch (Exception e) {
                        e.printStackTrace();
                        MLogs.d("Proxy App Fail: " + app.getAppLabel());
                    }
                }
                //do not need add disallow as it already filtered
            }
        } else {
            MLogs.d("No Pre-App proxy, due to low Android version.");
        }
        Intent intent = new Intent(this, HomeActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setConfigureIntent(pendingIntent);

        builder.setSession(ProxyConfig.Instance.getSessionName());
        ParcelFileDescriptor pfdDescriptor = builder.establish();
        onStatusChanged(ProxyConfig.Instance.getSessionName() + getString(R.string.vpn_connected_status), true,
                            mAvgDownloadSpeed, mAvgUploadSpeed, mMaxDownloadSpeed, mMaxUploadSpeed);
        return pfdDescriptor;
    }

    public void disconnectVPN() {
        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }
        onStatusChanged(ProxyConfig.Instance.getSessionName() + getString(R.string.vpn_disconnected_status), false,
                mAvgDownloadSpeed, mAvgUploadSpeed, mMaxDownloadSpeed, mMaxUploadSpeed);
        this.m_VPNOutputStream = null;
    }

    private synchronized void dispose() {
        // 断开VPN
        disconnectVPN();

        // 停止TcpServer
        if (m_TcpProxyServer != null) {
            m_TcpProxyServer.stop();
            m_TcpProxyServer = null;
            MLogs.d("LocalTcpServer stopped.");
        }

        // 停止DNS解析器
        if (m_DnsProxy != null) {
            m_DnsProxy.stop();
            m_DnsProxy = null;
            MLogs.d("LocalDnsProxy stopped.");
        }

        stopSelf();
        IsRunning = false;
        System.exit(0);
    }

    @Override
    public void onDestroy() {
        MLogs.d("VPNService(%s) destoried.\n"+ ID);
        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
        }
    }

}
