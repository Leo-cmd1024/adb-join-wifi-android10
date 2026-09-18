package com.steinwurf.adbjoinwifi;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.ProxyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements CheckSSIDBroadcastReceiver.SSIDFoundListener
{
    private static final String TAG = "adbjoinwifi";

    private static final String WEP_PASSWORD = "WEP";
    private static final String WPA_PASSWORD = "WPA";

    private static final String SSID = "ssid";
    private static final String PASSWORD_TYPE = "password_type";
    private static final String PASSWORD = "password";

    private static final String PROXY_HOST = "proxy_host";
    private static final String PROXY_PORT = "proxy_port";
    private static final String PROXY_BYPASS = "proxy_bypass";
    private static final String PROXY_PAC_URI = "proxy_pac_uri";

    private static final String CLEAR_DEVICE_ADMIN = "clear_device_admin";

    String mSSID;
    String mPassword;
    String mPasswordType;
    ProxyInfo mProxyInfo;

    CheckSSIDBroadcastReceiver broadcastReceiver;
    WifiManager mWifiManager;

    Thread mThread;

    private void printUsage()
    {
        Log.d(TAG, "No datastring provided. use the following adb command:");
        Log.d(TAG,
                "adb shell am start" +
                " -n com.steinwurf.adbjoinwifi/.MainActivity " +
                "-e ssid SSID " +
                "-e password_type [WEP|WPA] " +
                "-e password PASSWORD " +
                "\nOptional proxy args:\n" +
                "    -e proxy_host HOSTNAME " +
                "-e proxy_port PORT " +
                "[-e proxy_bypass COMMA,SEPARATED,LIST]\n" +
                "    OR\n" +
                "    -e proxy_pac_uri http://my.proxy.config/url\n" +
                "If app was granted device owner using dpm, you can unset it with:\n" +
                "    -e clear_device_admin true");
        Toast.makeText(this, "This application is meant to be used with ADB",
                Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        boolean clearDeviceAdmin = getIntent().getExtras() != null && getIntent().getExtras().containsKey(CLEAR_DEVICE_ADMIN);

        if (clearDeviceAdmin) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clearDeviceOwner();
            }
            else
            {
                throw new UnsupportedOperationException("API level 21 or higher required for this");
            }
            finish();
            return;
        }

        // Get Content
        mSSID = getIntent().getStringExtra(SSID);
        mPasswordType = getIntent().getStringExtra(PASSWORD_TYPE);
        mPassword = getIntent().getStringExtra(PASSWORD);

        String proxyHost = getIntent().getStringExtra(PROXY_HOST);
        String proxyPort = getIntent().getStringExtra(PROXY_PORT);
        String proxyBypass = getIntent().getStringExtra(PROXY_BYPASS);
        String proxyPacUri = getIntent().getStringExtra(PROXY_PAC_URI);

        // Validate
        if ((mSSID == null) || // SSID REQUIRED
            (mPasswordType != null && mPassword == null) || // PASSWORD REQUIRED IF PASSWORD TYPE GIVEN
            (mPassword != null && mPasswordType == null) || // PASSWORD TYPE REQUIRED IF PASSWORD GIVEN
            (mPasswordType != null && !mPasswordType.equals(WPA_PASSWORD) && !mPasswordType.equals(WEP_PASSWORD))) // PASSWORD TYPE MUST BE NULL OR WPA OR WEP
        {
            printUsage();
            return;
        }

        // Android 10+ (API 29+): use WifiNetworkSuggestion, which does NOT require
        // the ACCESS_FINE_LOCATION permission that the legacy WifiConfiguration path needs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectViaSuggestion(mSSID, mPasswordType, mPassword);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                mProxyInfo = Proxy.parseProxyInfo(proxyHost, proxyPort, proxyBypass, proxyPacUri);
            } catch (ParseException e) {
                Log.d(TAG, "Error parsing proxy settings");
                printUsage();
                return;
            }
        }

        Log.d(TAG, "Trying to join:");
        Log.d(TAG, "SSID: " + mSSID);
        if(mPasswordType != null && mPassword != null)
        {
            Log.d(TAG, "Password Type: " + mPasswordType);
            Log.d(TAG, "Password: " + mPassword);
        }

        // Setup layout

        TextView textview = new TextView(this);
        textview.setText(getString(R.string.trying_to_connect_to));
        textview.setTextSize(20);
        setContentView(textview);

        // Setup broadcast receiver

        broadcastReceiver = new CheckSSIDBroadcastReceiver(mSSID);
        broadcastReceiver.setSSIDFoundListener(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(broadcastReceiver, filter);

        // Check if wifi is enabled, and act accordingly
        mWifiManager = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);

        if (!mWifiManager.isWifiEnabled())
            mWifiManager.setWifiEnabled(true);
        else
            WifiEnabled();
    }

    /**
     * Android 10+ (API 29+) connect path.
     * Uses WifiNetworkSuggestion which the system honors when the network is in range.
     * Unlike the legacy WifiConfiguration path, this does NOT require ACCESS_FINE_LOCATION.
     */
    private void connectViaSuggestion(String ssid, String passwordType, String password)
    {
        mWifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (mWifiManager != null && !mWifiManager.isWifiEnabled()) {
            mWifiManager.setWifiEnabled(true);
        }

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(ssid);

        if (password != null && passwordType != null) {
            if (WPA_PASSWORD.equals(passwordType)) {
                builder.setWpa2Passphrase(password);
            } else if (WEP_PASSWORD.equals(passwordType)) {
                builder.setWepKeys(new String[]{ password });
            }
        }

        List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
        suggestions.add(builder.build());

        int status = mWifiManager.addNetworkSuggestions(suggestions);
        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.d(TAG, "Network suggestion added; system will connect when in range: " + ssid);
            Toast.makeText(this, "Suggested network: " + ssid, Toast.LENGTH_SHORT).show();
        } else {
            Log.e(TAG, "Failed to add network suggestion, status=" + status);
            Toast.makeText(this, "Suggestion failed status=" + status, Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (broadcastReceiver != null)
        unregisterReceiver(broadcastReceiver);
        broadcastReceiver = null;
    }

    @Override
    public void SSIDFound()
    {
        Log.d(TAG, "Device Connected to " + mSSID);
        if (mThread != null)
        {
            mThread.interrupt();
            try
            {
                mThread.join();
            }
            catch (InterruptedException e)
            {
                Log.e(TAG, "Hit exception", e);
            }
        }
        finish();
    }

    @Override
    public void WifiEnabled()
    {
        Log.d(TAG, "WifiEnabled");
        if (mThread != null)
            return;

        WifiConfiguration wfc = getExistingWifiConfiguration();
        int networkId;

        if (wfc == null)
        {
            // Wifi configuration didn't exist for this SSID, create it.
            wfc = new WifiConfiguration();
            updateWifiConfiguration(wfc);
            networkId = mWifiManager.addNetwork(wfc);
        }
        else if (permittedToUpdate(wfc))
        {
            // Wifi configuration already exists, update if we can
            updateWifiConfiguration(wfc);
            networkId = mWifiManager.updateNetwork(wfc);
        }
        else {
            // Wifi configuration already exists, we cannot update it so just join it
            networkId = wfc.networkId;
        }

        if (networkId == -1)
        {
            Log.d(TAG, "Invalid wifi network (ensure this SSID exists, auth method and password are correct, etc.)");
            finish();
            return;
        }

        final int finalNetworkId = networkId;

        mThread = new Thread() {
            @Override
            public void run() {
                mWifiManager.disconnect();
                try
                {
                    while(!isInterrupted())
                    {
                        Log.d(TAG, "Joining, network id=" + Integer.toString(finalNetworkId));
                        mWifiManager.enableNetwork(finalNetworkId, true);
                        mWifiManager.reconnect();
                        // Wait and see if it worked. Otherwise try again.
                        sleep(10000);
                    }
                } catch (InterruptedException ignored) {
                }
            }
        };
        mThread.start();
    }

    private boolean permittedToUpdate(WifiConfiguration wfc)
    {
        Field field;
        int creatorUid;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            try
            {
                field = wfc.getClass().getDeclaredField("creatorUid");
                creatorUid = field.getInt(wfc);
            }
            catch (ReflectiveOperationException e)
            {
                Log.e(TAG, "Hit exception", e);
                return false;
            }
            if (creatorUid == getApplicationInfo().uid || canEditWifi())
            {
                Log.d(TAG, "App is permitted to modify this wifi configuration");
                return true;
            }
        }
        // Since app doesn't have proper permissions, we will join the existing Wifi network as configured
        Log.w(TAG, "App does not have admin access, unable to modify a wifi network created by another app");
        return false;
    }

    private void updateWifiConfiguration(WifiConfiguration wfc)
    {
        wfc.SSID = "\"".concat(mSSID).concat("\"");
        wfc.status = WifiConfiguration.Status.ENABLED;
        wfc.priority = 100;
        if (mPasswordType == null) // no password
        {
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            wfc.allowedAuthAlgorithms.clear();
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        }
        else if (mPasswordType.equals(WEP_PASSWORD)) // WEP
        {
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            wfc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            wfc.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);

            // if hex string
            // wfc.wepKeys[0] = password;

            wfc.wepKeys[0] = "\"".concat(mPassword).concat("\"");
            wfc.wepTxKeyIndex = 0;
        }
        else if (mPasswordType.equals(WPA_PASSWORD)) // WPA(2)
        {
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
            wfc.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
            wfc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
            wfc.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
            wfc.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

            wfc.preSharedKey = "\"".concat(mPassword).concat("\"");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                Proxy.setProxy(wfc, mProxyInfo);
            } catch (IllegalArgumentException | ReflectiveOperationException e) {
                Log.e(TAG, "Failed to set proxy on wifi configuration", e);
            }
        }
    }

    private WifiConfiguration getExistingWifiConfiguration()
    {
        for( WifiConfiguration i : mWifiManager.getConfiguredNetworks())
        {
            if(i.SSID != null && i.SSID.equals("\"".concat(mSSID).concat("\"")))
            {
                Log.d(TAG, "wifi network already exists.");
                return i;
            }
        }
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private boolean canEditWifi()
    {
        DevicePolicyManager devicePolicyManager =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

        return devicePolicyManager.isAdminActive(new ComponentName(this, AdminReceiver.class)) &&
               devicePolicyManager.isDeviceOwnerApp(getPackageName());
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void clearDeviceOwner()
    {
        if (canEditWifi())
        {
            DevicePolicyManager devicePolicyManager =
                    (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);

            devicePolicyManager.clearDeviceOwnerApp(getPackageName());
        }
    }
}
