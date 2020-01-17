/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import static android.net.wifi.WifiManager.WIFI_GENERATION_DEFAULT;

import android.annotation.NonNull;
import android.content.Context;
import android.content.IntentFilter;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.os.Environment;
import android.os.Handler;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Random;

import javax.annotation.Nullable;
import javax.crypto.Mac;

/**
 * Provides API for reading/writing soft access point configuration.
 */
public class WifiApConfigStore {

    // Intent when user has interacted with the softap settings change notification
    public static final String ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT =
            "com.android.server.wifi.WifiApConfigStoreUtil.HOTSPOT_CONFIG_USER_TAPPED_CONTENT";

    private static final String TAG = "WifiApConfigStore";

    // Note: This is the legacy Softap config file. This is only used for migrating data out
    // of this file on first reboot.
    private static final String LEGACY_AP_CONFIG_FILE =
            Environment.getDataDirectory() + "/misc/wifi/softap.conf";

    @VisibleForTesting
    public static final int AP_CONFIG_FILE_VERSION = 3;

    private static final int RAND_SSID_INT_MIN = 1000;
    private static final int RAND_SSID_INT_MAX = 9999;

    @VisibleForTesting
    static final int SSID_MIN_LEN = 1;
    @VisibleForTesting
    static final int SSID_MAX_LEN = 32;
    @VisibleForTesting
    static final int PSK_MIN_LEN = 8;
    @VisibleForTesting
    static final int PSK_MAX_LEN = 63;

    private SoftApConfiguration mPersistentWifiApConfig = null;

    private final Context mContext;
    private final Handler mHandler;
    private final BackupManagerProxy mBackupManagerProxy;
    private final MacAddressUtil mMacAddressUtil;
    private final Mac mMac;
    private final WifiConfigManager mWifiConfigManager;
    private boolean mHasNewDataToSerialize = false;

    /**
     * Module to interact with the wifi config store.
     */
    private class SoftApStoreDataSource implements SoftApStoreData.DataSource {

        public SoftApConfiguration toSerialize() {
            mHasNewDataToSerialize = false;
            return mPersistentWifiApConfig;
        }

        public void fromDeserialized(SoftApConfiguration config) {
            mPersistentWifiApConfig = new SoftApConfiguration.Builder(config).build();
        }

        public void reset() {
            if (mPersistentWifiApConfig != null) {
                // Note: Reset is invoked when WifiConfigStore.read() is invoked on boot completed.
                // If we had migrated data from the legacy store before that (which is most likely
                // true because we read the legacy file in the constructor here, whereas
                // WifiConfigStore.read() is only triggered on boot completed), trigger a write to
                // persist the migrated data.
                mHandler.post(() -> mWifiConfigManager.saveToStore(true));
            }
        }

        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    // Dual SAP config
    private String mBridgeInterfaceName = null;
    private boolean mDualSapStatus = false;

    private int mWifiGeneration = WIFI_GENERATION_DEFAULT;

    WifiApConfigStore(Context context, WifiInjector wifiInjector, Handler handler,
            BackupManagerProxy backupManagerProxy, WifiConfigStore wifiConfigStore,
            WifiConfigManager wifiConfigManager) {
        this(context, wifiInjector, handler, backupManagerProxy, wifiConfigStore,
                wifiConfigManager, LEGACY_AP_CONFIG_FILE);
    }

    WifiApConfigStore(Context context,
            WifiInjector wifiInjector,
            Handler handler,
            BackupManagerProxy backupManagerProxy,
            WifiConfigStore wifiConfigStore,
            WifiConfigManager wifiConfigManager,
            String apConfigFile) {
        mContext = context;
        mHandler = handler;
        mBackupManagerProxy = backupManagerProxy;
        mWifiConfigManager = wifiConfigManager;

        // One time migration from legacy config store.
        try {
            File file = new File(apConfigFile);
            FileInputStream fis = new FileInputStream(apConfigFile);
            /* Load AP configuration from persistent storage. */
            SoftApConfiguration config = loadApConfigurationFromLegacyFile(fis);
            if (config != null) {
                // Persist in the new store.
                persistConfigAndTriggerBackupManagerProxy(config);
                Log.i(TAG, "Migrated data out of legacy store file " + apConfigFile);
                // delete the legacy file.
                file.delete();
            }
        } catch (FileNotFoundException e) {
            // Expected on further reboots after the first reboot.
        }

        // Register store data listener
        wifiConfigStore.registerStoreData(
                wifiInjector.makeSoftApStoreData(new SoftApStoreDataSource()));

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_HOTSPOT_CONFIG_USER_TAPPED_CONTENT);
        mMacAddressUtil = wifiInjector.getMacAddressUtil();
        mMac = mMacAddressUtil.obtainMacRandHashFunctionForSap(Process.WIFI_UID);
        if (mMac == null) {
            Log.wtf(TAG, "Failed to obtain secret for SAP MAC randomization."
                    + " All randomized MAC addresses are lost!");
        }

        mBridgeInterfaceName = SystemProperties
                .get("persist.vendor.wifi.softap.bridge.interface", "wifi_br0");
    }

   /* Additional APIs(get/set) to support SAP + SAP Feature */

    public synchronized String getBridgeInterface() {
        return mBridgeInterfaceName;
    }

    public synchronized boolean getDualSapStatus() {
        return mDualSapStatus;
    }

    public synchronized void setDualSapStatus(boolean enable) {
        mDualSapStatus = enable;
    }

    /**
     * Return the current soft access point configuration.
     */
    public synchronized SoftApConfiguration getApConfiguration() {
        if (mPersistentWifiApConfig == null) {
            /* Use default configuration. */
            Log.d(TAG, "Fallback to use default AP configuration");
            persistConfigAndTriggerBackupManagerProxy(getDefaultApConfiguration());
        }
        SoftApConfiguration sanitizedPersistentconfig =
                sanitizePersistentApConfig(mPersistentWifiApConfig);
        if (mPersistentWifiApConfig != sanitizedPersistentconfig) {
            Log.d(TAG, "persisted config was converted, need to resave it");
            persistConfigAndTriggerBackupManagerProxy(sanitizedPersistentconfig);
        }
        return mPersistentWifiApConfig;
    }

    /**
     * Update the current soft access point configuration.
     * Restore to default AP configuration if null is provided.
     * This can be invoked under context of binder threads (WifiManager.setWifiApConfiguration)
     * and the main Wifi thread (CMD_START_AP).
     */
    public synchronized void setApConfiguration(SoftApConfiguration config) {
        if (config == null) {
            config = getDefaultApConfiguration();
        } else {
            config = sanitizePersistentApConfig(config);
        }
        persistConfigAndTriggerBackupManagerProxy(config);
    }

    public ArrayList<Integer> getAllowed2GChannel() {
        String ap2GChannelListStr = mContext.getResources().getString(
                R.string.config_wifi_framework_sap_2G_channel_list);
        Log.d(TAG, "2G band allowed channels are:" + ap2GChannelListStr);

        ArrayList<Integer> allowed2GChannels = new ArrayList<>();
        if (ap2GChannelListStr != null) {
            String[] channelList = ap2GChannelListStr.split(",");
            for (String tmp : channelList) {
                allowed2GChannels.add(Integer.parseInt(tmp));
            }
        }
        return allowed2GChannels;
    }

    private SoftApConfiguration sanitizePersistentApConfig(SoftApConfiguration config) {
        SoftApConfiguration.Builder convertedConfigBuilder = null;

        // Persistent config may not set BSSID.
        if (config.getBssid() != null) {
            convertedConfigBuilder = new SoftApConfiguration.Builder(config);
            convertedConfigBuilder.setBssid(null);
        }

        if (mContext.getResources().getBoolean(R.bool.config_wifi_convert_apband_5ghz_to_any)) {
            // some devices are unable to support 5GHz only operation, check for 5GHz and
            // allow for 2GHz if apBand conversion is required.
            if (config.getBand() == SoftApConfiguration.BAND_5GHZ) {
                Log.w(TAG, "Supplied ap config band was 5GHz only, Allowing for 2.4GHz");
                if (convertedConfigBuilder == null) {
                    convertedConfigBuilder = new SoftApConfiguration.Builder(config);
                }
                convertedConfigBuilder.setBand(SoftApConfiguration.BAND_5GHZ
                        | SoftApConfiguration.BAND_2GHZ);
            }
        } else {
            // this is a single mode device, convert band to 5GHz if allowed
            int targetBand = 0;
            int apBand = config.getBand();
            if (ApConfigUtil.isMultiband(apBand)) {
                if (ApConfigUtil.containsBand(apBand, SoftApConfiguration.BAND_5GHZ)) {
                    Log.w(TAG, "Supplied ap config band is multiband , converting to 5GHz");
                    targetBand = SoftApConfiguration.BAND_5GHZ;
                } else if (ApConfigUtil.containsBand(apBand,
                        SoftApConfiguration.BAND_2GHZ)) {
                    Log.w(TAG, "Supplied ap config band is multiband , converting to 2GHz");
                    targetBand = SoftApConfiguration.BAND_2GHZ;
                } else if (ApConfigUtil.containsBand(apBand,
                        SoftApConfiguration.BAND_6GHZ)) {
                    Log.w(TAG, "Supplied ap config band is multiband , converting to 6GHz");
                    targetBand = SoftApConfiguration.BAND_6GHZ;
                }
            }

            if (targetBand != 0) {
                if (convertedConfigBuilder == null) {
                    convertedConfigBuilder = new SoftApConfiguration.Builder(config);
                }
                convertedConfigBuilder.setBand(targetBand);
            }
        }
        return convertedConfigBuilder == null ? config : convertedConfigBuilder.build();
    }

    private void persistConfigAndTriggerBackupManagerProxy(SoftApConfiguration config) {
        mPersistentWifiApConfig = config;
        mHasNewDataToSerialize = true;
        mWifiConfigManager.saveToStore(true);
        mBackupManagerProxy.notifyDataChanged();
    }

    /**
     * Load AP configuration from legacy persistent storage.
     * Note: This is deprecated and only used for migrating data once on reboot.
     */
    private static SoftApConfiguration loadApConfigurationFromLegacyFile(FileInputStream fis) {
        SoftApConfiguration config = null;
        DataInputStream in = null;
        try {
            SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
            in = new DataInputStream(new BufferedInputStream(fis));

            int version = in.readInt();
            if (version < 1 || version > AP_CONFIG_FILE_VERSION) {
                Log.e(TAG, "Bad version on hotspot configuration file");
                return null;
            }
            configBuilder.setSsid(in.readUTF());

            if (version >= 2) {
                int band = in.readInt();
                int channel = in.readInt();

                if (channel == 0) {
                    configBuilder.setBand(
                            ApConfigUtil.convertWifiConfigBandToSoftApConfigBand(band));
                } else {
                    configBuilder.setChannel(channel,
                            ApConfigUtil.convertWifiConfigBandToSoftApConfigBand(band));
                }
            }

            if (version >= 3) {
                configBuilder.setHiddenSsid(in.readBoolean());
            }

            int authType = in.readInt();
            if (authType == WifiConfiguration.KeyMgmt.WPA2_PSK) {
                configBuilder.setWpa2Passphrase(in.readUTF());
            }
            config = configBuilder.build();
        } catch (IOException e) {
            Log.e(TAG, "Error reading hotspot configuration " + e);
            config = null;
        } catch (IllegalArgumentException ie) {
            Log.e(TAG, "Invalid hotspot configuration " + ie);
            config = null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing hotspot configuration during read" + e);
                }
            }
        }
        return config;
    }

    /**
     * Generate a default WPA2 based configuration with a random password.
     * We are changing the Wifi Ap configuration storage from secure settings to a
     * flat file accessible only by the system. A WPA2 based default configuration
     * will keep the device secure after the update.
     */
    private SoftApConfiguration getDefaultApConfiguration() {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setBand(SoftApConfiguration.BAND_2GHZ);
        configBuilder.setSsid(mContext.getResources().getString(
                R.string.wifi_tether_configure_ssid_default) + "_" + getRandomIntForDefaultSsid());
        configBuilder.setWpa2Passphrase(generatePassword());
        return configBuilder.build();
    }

    private static int getRandomIntForDefaultSsid() {
        Random random = new Random();
        return random.nextInt((RAND_SSID_INT_MAX - RAND_SSID_INT_MIN) + 1) + RAND_SSID_INT_MIN;
    }

    private static String generateLohsSsid(Context context) {
        return context.getResources().getString(
                R.string.wifi_localhotspot_configure_ssid_default) + "_"
                + getRandomIntForDefaultSsid();
    }

    /**
     * Generate a temporary WPA2 based configuration for use by the local only hotspot.
     * This config is not persisted and will not be stored by the WifiApConfigStore.
     */
    public static SoftApConfiguration generateLocalOnlyHotspotConfig(Context context, int apBand,
            @Nullable SoftApConfiguration customConfig) {
        SoftApConfiguration.Builder configBuilder;
        if (customConfig != null) {
            configBuilder = new SoftApConfiguration.Builder(customConfig);
        } else {
            configBuilder = new SoftApConfiguration.Builder();
        }

        configBuilder.setBand(apBand);

        if (customConfig == null || customConfig.getSsid() == null) {
            configBuilder.setSsid(generateLohsSsid(context));
        }
        if (customConfig == null) {
            configBuilder.setWpa2Passphrase(generatePassword());
        }

        return configBuilder.build();
    }

    /**
     * @return a copy of the given SoftApConfig with the BSSID randomized, unless a custom BSSID is
     * already set.
     */
    SoftApConfiguration randomizeBssidIfUnset(Context context, SoftApConfiguration config) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder(config);
        if (config.getBssid() == null && context.getResources().getBoolean(
                R.bool.config_wifi_ap_mac_randomization_supported)) {
            MacAddress macAddress = mMacAddressUtil.calculatePersistentMac(config.getSsid(), mMac);
            if (macAddress == null) {
                Log.e(TAG, "Failed to calculate MAC from SSID. "
                        + "Generating new random MAC instead.");
                macAddress = MacAddress.createRandomUnicastAddress();
            }
            configBuilder.setBssid(macAddress);
        }
        return configBuilder.build();
    }

    /**
     * Verify provided SSID for existence, length and conversion to bytes
     *
     * @param ssid String ssid name
     * @return boolean indicating ssid met requirements
     */
    private static boolean validateApConfigSsid(String ssid) {
        if (TextUtils.isEmpty(ssid)) {
            Log.d(TAG, "SSID for softap configuration must be set.");
            return false;
        }

        try {
            byte[] ssid_bytes = ssid.getBytes(StandardCharsets.UTF_8);

            if (ssid_bytes.length < SSID_MIN_LEN || ssid_bytes.length > SSID_MAX_LEN) {
                Log.d(TAG, "softap SSID is defined as UTF-8 and it must be at least "
                        + SSID_MIN_LEN + " byte and not more than " + SSID_MAX_LEN + " bytes");
                return false;
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap config SSID verification failed: malformed string " + ssid);
            return false;
        }
        return true;
    }

    /**
     * Verify provided preSharedKey in ap config for WPA2_PSK network meets requirements.
     */
    private static boolean validateApConfigPreSharedKey(String preSharedKey) {
        if (preSharedKey.length() < PSK_MIN_LEN || preSharedKey.length() > PSK_MAX_LEN) {
            Log.d(TAG, "softap network password string size must be at least " + PSK_MIN_LEN
                    + " and no more than " + PSK_MAX_LEN);
            return false;
        }

        try {
            preSharedKey.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "softap network password verification failed: malformed string");
            return false;
        }
        return true;
    }

    /**
     * Validate a SoftApConfiguration is properly configured for use by SoftApManager.
     *
     * This method checks the length of the SSID and for sanity between security settings (if it
     * requires a password, was one provided?).
     *
     * @param apConfig {@link SoftApConfiguration} to use for softap mode
     * @return boolean true if the provided config meets the minimum set of details, false
     * otherwise.
     */
    static boolean validateApWifiConfiguration(@NonNull SoftApConfiguration apConfig) {
        // first check the SSID
        if (!validateApConfigSsid(apConfig.getSsid())) {
            // failed SSID verificiation checks
            return false;
        }

        String preSharedKey = apConfig.getWpa2Passphrase();
        boolean hasPreSharedKey = !TextUtils.isEmpty(preSharedKey);
        int authType;

        try {
            authType = apConfig.getSecurityType();
        } catch (IllegalStateException e) {
            Log.d(TAG, "Unable to get AuthType for softap config: " + e.getMessage());
            return false;
        }

        if (authType == SoftApConfiguration.SECURITY_TYPE_OPEN
                || authType == SoftApConfiguration.SECURITY_TYPE_OWE) {
            // open networks should not have a password
            if (hasPreSharedKey) {
                Log.d(TAG, "open or OWE softap network should not have a password");
                return false;
            }
        } else if (authType == SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                || authType == SoftApConfiguration.SECURITY_TYPE_SAE) {
            // this is a config that should have a password - check that first
            if (!hasPreSharedKey) {
                Log.d(TAG, "softap network password must be set");
                return false;
            }

            if (!validateApConfigPreSharedKey(preSharedKey)) {
                // failed preSharedKey checks
                return false;
            }
        } else {
            // this is not a supported security type
            Log.d(TAG, "softap configs must either be open or WPA2 PSK or OWE or SAE networks");
            return false;
        }

        return true;
    }

    private static String generatePassword() {
        // Characters that will be used for password generation. Some characters commonly known to
        // be confusing like 0 and O excluded from this list.
        final String allowed = "23456789abcdefghijkmnpqrstuvwxyz";
        final int passLength = 15;

        StringBuilder sb = new StringBuilder(passLength);
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < passLength; i++) {
            sb.append(allowed.charAt(random.nextInt(allowed.length())));
        }
        return sb.toString();
    }

    public void setWifiGeneration(int generation) {
        mWifiGeneration = generation;
    }

    public int getWifiGeneration() {
        return mWifiGeneration;
    }
}
