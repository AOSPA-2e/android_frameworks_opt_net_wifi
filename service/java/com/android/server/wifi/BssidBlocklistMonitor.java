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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.net.wifi.WifiSsid;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This classes manages the addition and removal of BSSIDs to the BSSID blocklist, which is used
 * for firmware roaming and network selection.
 */
public class BssidBlocklistMonitor {
    // A special type association rejection
    public static final int REASON_AP_UNABLE_TO_HANDLE_NEW_STA = 0;
    // No internet
    public static final int REASON_NETWORK_VALIDATION_FAILURE = 1;
    // Wrong password error
    public static final int REASON_WRONG_PASSWORD = 2;
    // Incorrect EAP credentials
    public static final int REASON_EAP_FAILURE = 3;
    // Other association rejection failures
    public static final int REASON_ASSOCIATION_REJECTION = 4;
    // Associated timeout failures, when the RSSI is good
    public static final int REASON_ASSOCIATION_TIMEOUT = 5;
    // Other authentication failures
    public static final int REASON_AUTHENTICATION_FAILURE = 6;
    // DHCP failures
    public static final int REASON_DHCP_FAILURE = 7;
    // Local constant being used to keep track of how many failure reasons there are.
    private static final int NUMBER_REASON_CODES = 8;

    @IntDef(prefix = { "REASON_" }, value = {
            REASON_AP_UNABLE_TO_HANDLE_NEW_STA,
            REASON_NETWORK_VALIDATION_FAILURE,
            REASON_WRONG_PASSWORD,
            REASON_EAP_FAILURE,
            REASON_ASSOCIATION_REJECTION,
            REASON_ASSOCIATION_TIMEOUT,
            REASON_AUTHENTICATION_FAILURE,
            REASON_DHCP_FAILURE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BssidBlocklistMonitorFailureReason {}

    public static final int[] FAILURE_COUNT_DISABLE_THRESHOLD = {
            1,  //  threshold for REASON_AP_UNABLE_TO_HANDLE_NEW_STA
            1,  //  threshold for REASON_NETWORK_VALIDATION_FAILURE
            1,  //  threshold for REASON_WRONG_PASSWORD
            1,  //  threshold for REASON_EAP_FAILURE
            3,  //  threshold for REASON_ASSOCIATION_REJECTION
            3,  //  threshold for REASON_ASSOCIATION_TIMEOUT
            3,  //  threshold for REASON_AUTHENTICATION_FAILURE
            3   //  threshold for REASON_DHCP_FAILURE
    };

    private static final int FAILURE_COUNTER_THRESHOLD = 3;
    private static final long BASE_BLOCKLIST_DURATION = 5 * 60 * 1000; // 5 minutes
    private static final String TAG = "BssidBlocklistMonitor";

    private final WifiLastResortWatchdog mWifiLastResortWatchdog;
    private final WifiConnectivityHelper mConnectivityHelper;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final Calendar mCalendar;

    // Map of bssid to BssidStatus
    private Map<String, BssidStatus> mBssidStatusMap = new ArrayMap<>();

    /**
     * Create a new instance of BssidBlocklistMonitor
     */
    BssidBlocklistMonitor(WifiConnectivityHelper connectivityHelper,
            WifiLastResortWatchdog wifiLastResortWatchdog, Clock clock, LocalLog localLog) {
        mConnectivityHelper = connectivityHelper;
        mWifiLastResortWatchdog = wifiLastResortWatchdog;
        mClock = clock;
        mLocalLog = localLog;
        mCalendar = Calendar.getInstance();
    }

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    private long getBlocklistDurationWithExponentialBackoff(String bssid) {
        // TODO: b/139287182 implement exponential backoff to extend the blocklist duration for
        // BSSIDs that continue to fail.
        return BASE_BLOCKLIST_DURATION;
    }

    /**
     * Dump the local log buffer and other internal state of BssidBlocklistMonitor.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of BssidBlocklistMonitor");
        pw.println("BssidBlocklistMonitor - Bssid blocklist Begin ----");
        mBssidStatusMap.values().stream().forEach(entry -> pw.println(entry));
        pw.println("BssidBlocklistMonitor - Bssid blocklist End ----");
    }

    private boolean addToBlocklist(@NonNull BssidStatus entry) {
        // Call mWifiLastResortWatchdog.shouldIgnoreBssidUpdate to give watchdog a chance to
        // trigger before blocklisting the bssid.
        String bssid = entry.bssid;
        if (mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(bssid)) {
            return false;
        }
        long durationMs = getBlocklistDurationWithExponentialBackoff(bssid);
        entry.addToBlocklist(durationMs);
        localLog(TAG + " addToBlocklist: bssid=" + bssid + ", ssid=" + entry.ssid
                + ", durationMs=" + durationMs);
        return true;
    }

    /**
     * increments the number of failures for the given bssid and returns the number of failures so
     * far.
     * @return the BssidStatus for the BSSID
     */
    private @NonNull BssidStatus incrementFailureCountForBssid(
            @NonNull String bssid, @NonNull String ssid, int reasonCode) {
        BssidStatus status = mBssidStatusMap.get(bssid);
        if (status == null || !ssid.equals(status.ssid)) {
            if (status != null) {
                localLog("incrementFailureCountForBssid: BSSID=" + bssid + ", SSID changed from "
                        + status.ssid + " to " + ssid);
            }
            status = new BssidStatus(bssid, ssid);
            mBssidStatusMap.put(bssid, status);
        }
        status.incrementFailureCount(reasonCode);
        return status;
    }

    /**
     * Note a failure event on a bssid and perform appropriate actions.
     * @return True if the blocklist has been modified.
     */
    public boolean handleBssidConnectionFailure(String bssid, String ssid,
            @BssidBlocklistMonitorFailureReason int reasonCode) {
        if (bssid == null || ssid == null || WifiSsid.NONE.equals(ssid)
                || bssid.equals(ClientModeImpl.SUPPLICANT_BSSID_ANY)
                || reasonCode < 0 || reasonCode >= NUMBER_REASON_CODES) {
            Log.e(TAG, "Invalid input: BSSID=" + bssid + ", SSID=" + ssid
                    + ", reasonCode=" + reasonCode);
            return false;
        }
        boolean result = false;
        BssidStatus entry = incrementFailureCountForBssid(bssid, ssid, reasonCode);
        if (entry.failureCount[reasonCode] >= FAILURE_COUNT_DISABLE_THRESHOLD[reasonCode]) {
            result = addToBlocklist(entry);
        }
        return result;
    }

    /**
     * Note a connection success event on a bssid and clear appropriate failure counters.
     */
    public void handleBssidConnectionSuccess(@NonNull String bssid) {
        BssidStatus status = mBssidStatusMap.get(bssid);
        if (status == null) {
            return;
        }
        // Clear the L2 failure counters
        status.failureCount[REASON_AP_UNABLE_TO_HANDLE_NEW_STA] = 0;
        status.failureCount[REASON_WRONG_PASSWORD] = 0;
        status.failureCount[REASON_EAP_FAILURE] = 0;
        status.failureCount[REASON_ASSOCIATION_REJECTION] = 0;
        status.failureCount[REASON_ASSOCIATION_TIMEOUT] = 0;
        status.failureCount[REASON_AUTHENTICATION_FAILURE] = 0;
    }

    /**
     * Note a successful network validation on a BSSID and clear appropriate failure counters.
     */
    public void handleNetworkValidationSuccess(@NonNull String bssid) {
        BssidStatus status = mBssidStatusMap.get(bssid);
        if (status == null) {
            return;
        }
        status.failureCount[REASON_NETWORK_VALIDATION_FAILURE] = 0;
    }

    /**
     * Note a successful DHCP provisioning and clear appropriate faliure counters.
     */
    public void handleDhcpProvisioningSuccess(@NonNull String bssid) {
        BssidStatus status = mBssidStatusMap.get(bssid);
        if (status == null) {
            return;
        }
        status.failureCount[REASON_DHCP_FAILURE] = 0;
    }

    /**
     * Clears the blocklist for BSSIDs associated with the input SSID only.
     * @param ssid
     */
    public void clearBssidBlocklistForSsid(@NonNull String ssid) {
        int prevSize = mBssidStatusMap.size();
        mBssidStatusMap.entrySet().removeIf(e -> e.getValue().ssid.equals(ssid));
        int diff = prevSize - mBssidStatusMap.size();
        if (diff > 0) {
            localLog(TAG + " clearBssidBlocklistForSsid: SSID=" + ssid
                    + ", num BSSIDs cleared=" + diff);
        }
    }

    /**
     * Clears the BSSID blocklist and failure counters.
     */
    public void clearBssidBlocklist() {
        if (mBssidStatusMap.size() > 0) {
            localLog(TAG + " clearBssidBlocklist: num BSSIDs cleared=" + mBssidStatusMap.size());
            mBssidStatusMap.clear();
        }
    }

    /**
     * Gets the BSSIDs that are currently in the blocklist.
     * @return Set of BSSIDs currently in the blocklist
     */
    public Set<String> updateAndGetBssidBlocklist() {
        return updateAndGetBssidBlocklistInternal()
                .map(entry -> entry.bssid)
                .collect(Collectors.toSet());
    }

    /**
     * Removes expired BssidStatus entries and then return remaining entries in the blocklist.
     * @return Stream of BssidStatus for BSSIDs that are in the blocklist.
     */
    private Stream<BssidStatus> updateAndGetBssidBlocklistInternal() {
        Stream.Builder<BssidStatus> builder = Stream.builder();
        long curTime = mClock.getWallClockMillis();
        mBssidStatusMap.entrySet().removeIf(e -> {
            BssidStatus status = e.getValue();
            if (status.isInBlocklist) {
                if (status.blocklistEndTimeMs < curTime) {
                    return true;
                }
                builder.accept(status);
            }
            return false;
        });
        return builder.build();
    }

    /**
     * Sends the BSSIDs belonging to the input SSID down to the firmware to prevent auto-roaming
     * to those BSSIDs.
     * @param ssid
     */
    public void updateFirmwareRoamingConfiguration(@NonNull String ssid) {
        if (!mConnectivityHelper.isFirmwareRoamingSupported()) {
            return;
        }
        ArrayList<String> bssidBlocklist = updateAndGetBssidBlocklistInternal()
                .filter(entry -> ssid.equals(entry.ssid))
                .sorted((o1, o2) -> (int) (o2.blocklistEndTimeMs - o1.blocklistEndTimeMs))
                .map(entry -> entry.bssid)
                .collect(Collectors.toCollection(ArrayList::new));
        int fwMaxBlocklistSize = mConnectivityHelper.getMaxNumBlacklistBssid();
        if (fwMaxBlocklistSize <= 0) {
            Log.e(TAG, "Invalid max BSSID blocklist size:  " + fwMaxBlocklistSize);
            return;
        }
        // Having the blocklist size exceeding firmware max limit is unlikely because we have
        // already flitered based on SSID. But just in case this happens, we are prioritizing
        // sending down BSSIDs blocked for the longest time.
        if (bssidBlocklist.size() > fwMaxBlocklistSize) {
            bssidBlocklist = new ArrayList<String>(bssidBlocklist.subList(0,
                    fwMaxBlocklistSize));
        }
        // plumb down to HAL
        if (!mConnectivityHelper.setFirmwareRoamingConfiguration(bssidBlocklist,
                new ArrayList<String>())) {  // TODO(b/36488259): SSID whitelist management.
        }
    }

    /**
     * Helper class that counts the number of failures per BSSID.
     */
    private class BssidStatus {
        public final String bssid;
        public final String ssid;
        public final int[] failureCount = new int[NUMBER_REASON_CODES];

        // The following are used to flag how long this BSSID stays in the blocklist.
        public boolean isInBlocklist;
        public long blocklistEndTimeMs;


        BssidStatus(String bssid, String ssid) {
            this.bssid = bssid;
            this.ssid = ssid;
        }

        /**
         * increments the failure count for the reasonCode by 1.
         * @return the incremented failure count
         */
        public int incrementFailureCount(int reasonCode) {
            return ++failureCount[reasonCode];
        }

        /**
         * Add this BSSID to blocklist for the specified duration.
         * @param durationMs
         */
        public void addToBlocklist(long durationMs) {
            isInBlocklist = true;
            blocklistEndTimeMs = mClock.getWallClockMillis() + durationMs;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("BSSID=" + bssid);
            sb.append(", SSID=" + ssid);
            sb.append(", isInBlocklist=" + isInBlocklist);
            if (isInBlocklist) {
                mCalendar.setTimeInMillis(blocklistEndTimeMs);
                sb.append(", blocklistEndTimeMs="
                        + String.format("%tm-%td %tH:%tM:%tS.%tL", mCalendar, mCalendar,
                        mCalendar, mCalendar, mCalendar, mCalendar));
            }
            return sb.toString();
        }
    }
}
