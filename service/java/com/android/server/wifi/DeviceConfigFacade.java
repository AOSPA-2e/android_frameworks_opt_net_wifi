/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This class allows getting all configurable flags from DeviceConfig.
 */
public class DeviceConfigFacade {
    private Context mContext;
    private final WifiMetrics mWifiMetrics;

    private static final String NAMESPACE = "wifi";

    // Default values of fields
    @VisibleForTesting
    protected static final int DEFAULT_ABNORMAL_CONNECTION_DURATION_MS =
            (int) TimeUnit.SECONDS.toMillis(30);
    // Default duration for evaluating Wifi condition to trigger a data stall
    // measured in milliseconds
    public static final int DEFAULT_DATA_STALL_DURATION_MS = 1500;
    // Default threshold of Tx throughput below which to trigger a data stall measured in Kbps
    public static final int DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS = 2000;
    // Default threshold of Rx throughput below which to trigger a data stall measured in Kbps
    public static final int DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS = 2000;
    // Default threshold of Tx packet error rate above which to trigger a data stall in percentage
    public static final int DEFAULT_DATA_STALL_TX_PER_THR = 90;
    // Default threshold of CCA level above which to trigger a data stall in percentage
    public static final int DEFAULT_DATA_STALL_CCA_LEVEL_THR = 100;
    // Default high and low threshold values for various connection failure rates.
    // All of them are in percent with respect to connection attempts
    static final int DEFAULT_CONNECTION_FAILURE_HIGH_THR_PERCENT = 30;
    static final int DEFAULT_CONNECTION_FAILURE_LOW_THR_PERCENT = 5;
    static final int DEFAULT_ASSOC_REJECTION_HIGH_THR_PERCENT = 10;
    static final int DEFAULT_ASSOC_REJECTION_LOW_THR_PERCENT = 1;
    static final int DEFAULT_ASSOC_TIMEOUT_HIGH_THR_PERCENT = 10;
    static final int DEFAULT_ASSOC_TIMEOUT_LOW_THR_PERCENT = 2;
    static final int DEFAULT_AUTH_FAILURE_HIGH_THR_PERCENT = 10;
    static final int DEFAULT_AUTH_FAILURE_LOW_THR_PERCENT = 2;
    // Default high and low threshold values for non-local disconnection rate
    // with respect to disconnection count (with a recent RSSI poll)
    static final int DEFAULT_SHORT_CONNECTION_NONLOCAL_HIGH_THR_PERCENT = 10;
    static final int DEFAULT_SHORT_CONNECTION_NONLOCAL_LOW_THR_PERCENT = 1;
    static final int DEFAULT_DISCONNECTION_NONLOCAL_HIGH_THR_PERCENT = 15;
    static final int DEFAULT_DISCONNECTION_NONLOCAL_LOW_THR_PERCENT = 1;
    // Minimum RSSI in dBm for connection stats collection
    // Connection or disconnection events with RSSI below this threshold are not
    // included in connection stats collection.
    static final int DEFAULT_HEALTH_MONITOR_MIN_RSSI_THR_DBM = -68;

    // Cached values of fields updated via updateDeviceConfigFlags()
    private boolean mIsAbnormalConnectionBugreportEnabled;
    private int mAbnormalConnectionDurationMs;
    private int mDataStallDurationMs;
    private int mDataStallTxTputThrKbps;
    private int mDataStallRxTputThrKbps;
    private int mDataStallTxPerThr;
    private int mDataStallCcaLevelThr;
    private int mConnectionFailureHighThrPercent;
    private int mConnectionFailureLowThrPercent;
    private int mAssocRejectionHighThrPercent;
    private int mAssocRejectionLowThrPercent;
    private int mAssocTimeoutHighThrPercent;
    private int mAssocTimeoutLowThrPercent;
    private int mAuthFailureHighThrPercent;
    private int mAuthFailureLowThrPercent;
    private int mShortConnectionNonlocalHighThrPercent;
    private int mShortConnectionNonlocalLowThrPercent;
    private int mDisconnectionNonlocalHighThrPercent;
    private int mDisconnectionNonlocalLowThrPercent;
    private int mHealthMonitorMinRssiThrDbm;
    private Set<String> mRandomizationFlakySsidHotlist;
    private Set<String> mAggressiveMacRandomizationSsidAllowlist;
    private Set<String> mAggressiveMacRandomizationSsidBlocklist;

    public DeviceConfigFacade(Context context, Handler handler, WifiMetrics wifiMetrics) {
        mContext = context;
        mWifiMetrics = wifiMetrics;

        updateDeviceConfigFlags();
        DeviceConfig.addOnPropertiesChangedListener(
                NAMESPACE,
                command -> handler.post(command),
                properties -> {
                    updateDeviceConfigFlags();
                });
    }

    private void updateDeviceConfigFlags() {
        mIsAbnormalConnectionBugreportEnabled = DeviceConfig.getBoolean(NAMESPACE,
                "abnormal_connection_bugreport_enabled", false);
        mAbnormalConnectionDurationMs = DeviceConfig.getInt(NAMESPACE,
                "abnormal_connection_duration_ms",
                DEFAULT_ABNORMAL_CONNECTION_DURATION_MS);

        mDataStallDurationMs = DeviceConfig.getInt(NAMESPACE,
                "data_stall_duration_ms", DEFAULT_DATA_STALL_DURATION_MS);
        mDataStallTxTputThrKbps = DeviceConfig.getInt(NAMESPACE,
                "data_stall_tx_tput_thr_kbps", DEFAULT_DATA_STALL_TX_TPUT_THR_KBPS);
        mDataStallRxTputThrKbps = DeviceConfig.getInt(NAMESPACE,
                "data_stall_rx_tput_thr_kbps", DEFAULT_DATA_STALL_RX_TPUT_THR_KBPS);
        mDataStallTxPerThr = DeviceConfig.getInt(NAMESPACE,
                "data_stall_tx_per_thr", DEFAULT_DATA_STALL_TX_PER_THR);
        mDataStallCcaLevelThr = DeviceConfig.getInt(NAMESPACE,
                "data_stall_cca_level_thr", DEFAULT_DATA_STALL_CCA_LEVEL_THR);
        mWifiMetrics.setDataStallDurationMs(mDataStallDurationMs);
        mWifiMetrics.setDataStallTxTputThrKbps(mDataStallTxTputThrKbps);
        mWifiMetrics.setDataStallRxTputThrKbps(mDataStallRxTputThrKbps);
        mWifiMetrics.setDataStallTxPerThr(mDataStallTxPerThr);
        mWifiMetrics.setDataStallCcaLevelThr(mDataStallCcaLevelThr);

        mConnectionFailureHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "connection_failure_high_thr_percent",
                DEFAULT_CONNECTION_FAILURE_HIGH_THR_PERCENT);
        mConnectionFailureLowThrPercent = DeviceConfig.getInt(NAMESPACE,
                "connection_failure_low_thr_percent",
                DEFAULT_CONNECTION_FAILURE_LOW_THR_PERCENT);
        mAssocRejectionHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "assoc_rejection_high_thr_percent",
                DEFAULT_ASSOC_REJECTION_HIGH_THR_PERCENT);
        mAssocRejectionLowThrPercent = DeviceConfig.getInt(NAMESPACE,
                "assoc_rejection_low_thr_percent",
                DEFAULT_ASSOC_REJECTION_LOW_THR_PERCENT);
        mAssocTimeoutHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "assoc_timeout_high_thr_percent",
                DEFAULT_ASSOC_TIMEOUT_HIGH_THR_PERCENT);
        mAssocTimeoutLowThrPercent = DeviceConfig.getInt(NAMESPACE,
                "assoc_timeout_low_thr_percent",
                DEFAULT_ASSOC_TIMEOUT_LOW_THR_PERCENT);
        mAuthFailureHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "auth_failure_high_thr_percent",
                DEFAULT_AUTH_FAILURE_HIGH_THR_PERCENT);
        mAuthFailureLowThrPercent = DeviceConfig.getInt(NAMESPACE,
                "auth_failure_low_thr_percent",
                DEFAULT_AUTH_FAILURE_LOW_THR_PERCENT);
        mShortConnectionNonlocalHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "short_connection_nonlocal_high_thr_percent",
                DEFAULT_SHORT_CONNECTION_NONLOCAL_HIGH_THR_PERCENT);
        mShortConnectionNonlocalLowThrPercent = DeviceConfig.getInt(NAMESPACE,
                "short_connection_nonlocal_low_thr_percent",
                DEFAULT_SHORT_CONNECTION_NONLOCAL_LOW_THR_PERCENT);
        mDisconnectionNonlocalHighThrPercent = DeviceConfig.getInt(NAMESPACE,
                "disconnection_nonlocal_high_thr_percent",
                DEFAULT_DISCONNECTION_NONLOCAL_HIGH_THR_PERCENT);
        mDisconnectionNonlocalLowThrPercent = DeviceConfig.getInt(NAMESPACE,
                "disconnection_nonlocal_low_thr_percent",
                DEFAULT_DISCONNECTION_NONLOCAL_LOW_THR_PERCENT);

        mHealthMonitorMinRssiThrDbm = DeviceConfig.getInt(NAMESPACE,
                "health_monitor_min_rssi_thr_dbm",
                DEFAULT_HEALTH_MONITOR_MIN_RSSI_THR_DBM);

        mRandomizationFlakySsidHotlist =
                getUnmodifiableSetQuoted("randomization_flaky_ssid_hotlist");
        mAggressiveMacRandomizationSsidAllowlist =
                getUnmodifiableSetQuoted("aggressive_randomization_ssid_allowlist");
        mAggressiveMacRandomizationSsidBlocklist =
                getUnmodifiableSetQuoted("aggressive_randomization_ssid_blocklist");
    }

    private Set<String> getUnmodifiableSetQuoted(String key) {
        String rawList = DeviceConfig.getString(NAMESPACE, key, "");
        Set<String> result = new ArraySet<>();
        String[] list = rawList.split(",");
        for (String cur : list) {
            if (cur.length() == 0) {
                continue;
            }
            result.add("\"" + cur + "\"");
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Gets the feature flag for reporting abnormally long connections.
     */
    public boolean isAbnormalConnectionBugreportEnabled() {
        return mIsAbnormalConnectionBugreportEnabled;
    }

    /**
     * Gets the threshold for classifying abnormally long connections.
     */
    public int getAbnormalConnectionDurationMs() {
        return mAbnormalConnectionDurationMs;
    }

    /**
     * Gets the duration of evaluating Wifi condition to trigger a data stall.
     */
    public int getDataStallDurationMs() {
        return mDataStallDurationMs;
    }

    /**
     * Gets the threshold of Tx throughput below which to trigger a data stall.
     */
    public int getDataStallTxTputThrKbps() {
        return mDataStallTxTputThrKbps;
    }

    /**
     * Gets the threshold of Rx throughput below which to trigger a data stall.
     */
    public int getDataStallRxTputThrKbps() {
        return mDataStallRxTputThrKbps;
    }

    /**
     * Gets the threshold of Tx packet error rate above which to trigger a data stall.
     */
    public int getDataStallTxPerThr() {
        return mDataStallTxPerThr;
    }

    /**
     * Gets the threshold of CCA level above which to trigger a data stall.
     */
    public int getDataStallCcaLevelThr() {
        return mDataStallCcaLevelThr;
    }

    /**
     * Gets the high threshold of connection failure rate in percent
     */
    public int getConnectionFailureHighThrPercent() {
        return mConnectionFailureHighThrPercent;
    }

    /**
     * Gets the low threshold of connection failure rate in percent
     */
    public int getConnectionFailureLowThrPercent() {
        return mConnectionFailureLowThrPercent;
    }

    /**
     * Gets the high threshold of association rejection rate in percent
     */
    public int getAssocRejectionHighThrPercent() {
        return mAssocRejectionHighThrPercent;
    }

    /**
     * Gets the low threshold of association rejection rate in percent
     */
    public int getAssocRejectionLowThrPercent() {
        return mAssocRejectionLowThrPercent;
    }

    /**
     * Gets the high threshold of association timeout rate in percent
     */
    public int getAssocTimeoutHighThrPercent() {
        return mAssocTimeoutHighThrPercent;
    }

    /**
     * Gets the low threshold of association timeout rate in percent
     */
    public int getAssocTimeoutLowThrPercent() {
        return mAssocTimeoutLowThrPercent;
    }

    /**
     * Gets the high threshold of authentication failure rate in percent
     */
    public int getAuthFailureHighThrPercent() {
        return mAuthFailureHighThrPercent;
    }

    /**
     * Gets the low threshold of authentication failure rate in percent
     */
    public int getAuthFailureLowThrPercent() {
        return mAuthFailureLowThrPercent;
    }

    /**
     * Gets the high threshold of nonlocal short connection rate in percent
     */
    public int getShortConnectionNonlocalHighThrPercent() {
        return mShortConnectionNonlocalHighThrPercent;
    }

    /**
     * Gets the low threshold of nonlocal short connection rate in percent
     */
    public int getShortConnectionNonlocalLowThrPercent() {
        return mShortConnectionNonlocalLowThrPercent;
    }

    /**
     * Gets the high threshold of nonlocal disconnection rate in percent
     */
    public int getDisconnectionNonlocalHighThrPercent() {
        return mDisconnectionNonlocalHighThrPercent;
    }

    /**
     * Gets the low threshold of nonlocal disconnection rate in percent
     */
    public int getDisconnectionNonlocalLowThrPercent() {
        return mDisconnectionNonlocalLowThrPercent;
    }

    /**
     * Gets health monitor min RSSI threshold in dBm
     */
    public int getHealthMonitorMinRssiThrDbm() {
        return mHealthMonitorMinRssiThrDbm;
    }

    /**
     * Gets the Set of SSIDs in the flaky SSID hotlist.
     */
    public Set<String> getRandomizationFlakySsidHotlist() {
        return mRandomizationFlakySsidHotlist;
    }

    /**
     * Gets the list of SSIDs for aggressive MAC randomization.
     */
    public Set<String> getAggressiveMacRandomizationSsidAllowlist() {
        return mAggressiveMacRandomizationSsidAllowlist;
    }

    /**
     * Gets the list of SSIDs that aggressive MAC randomization should not be used for.
     */
    public Set<String> getAggressiveMacRandomizationSsidBlocklist() {
        return mAggressiveMacRandomizationSsidBlocklist;
    }
}
