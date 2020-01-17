/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OPSTR_CHANGE_WIFI_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.MacAddress;
import android.net.wifi.ISuggestionConnectionStatusListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.server.wifi.util.ExternalCallbackTracker;
import com.android.server.wifi.util.TelephonyUtil;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Network Suggestions Manager.
 * NOTE: This class should always be invoked from the main wifi service thread.
 */
@NotThreadSafe
public class WifiNetworkSuggestionsManager {
    private static final String TAG = "WifiNetworkSuggestionsManager";

    /** Intent when user tapped action button to allow the app. */
    @VisibleForTesting
    public static final String NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION =
            "com.android.server.wifi.action.NetworkSuggestion.USER_ALLOWED_APP";
    /** Intent when user tapped action button to disallow the app. */
    @VisibleForTesting
    public static final String NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION =
            "com.android.server.wifi.action.NetworkSuggestion.USER_DISALLOWED_APP";
    /** Intent when user dismissed the notification. */
    @VisibleForTesting
    public static final String NOTIFICATION_USER_DISMISSED_INTENT_ACTION =
            "com.android.server.wifi.action.NetworkSuggestion.USER_DISMISSED";
    @VisibleForTesting
    public static final String EXTRA_PACKAGE_NAME =
            "com.android.server.wifi.extra.NetworkSuggestion.PACKAGE_NAME";
    @VisibleForTesting
    public static final String EXTRA_UID =
            "com.android.server.wifi.extra.NetworkSuggestion.UID";
    /**
     * Limit number of hidden networks attach to scan
     */
    private static final int NUMBER_OF_HIDDEN_NETWORK_FOR_ONE_SCAN = 100;

    private final Context mContext;
    private final Resources mResources;
    private final Handler mHandler;
    private final AppOpsManager mAppOps;
    private final NotificationManager mNotificationManager;
    private final PackageManager mPackageManager;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiMetrics mWifiMetrics;
    private final WifiInjector mWifiInjector;
    private final FrameworkFacade mFrameworkFacade;
    private final TelephonyUtil mTelephonyUtil;

    /**
     * Per app meta data to store network suggestions, status, etc for each app providing network
     * suggestions on the device.
     */
    public static class PerAppInfo {
        /**
         * UID of the app.
         */
        public int uid;
        /**
         * Package Name of the app.
         */
        public final String packageName;
        /**
         * First Feature in the package that registered the suggestion
         */
        public final String featureId;
        /**
         * Set of active network suggestions provided by the app.
         */
        public final Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = new HashSet<>();
        /**
         * Whether we have shown the user a notification for this app.
         */
        public boolean hasUserApproved = false;

        /** Stores the max size of the {@link #extNetworkSuggestions} list ever for this app */
        public int maxSize = 0;

        public PerAppInfo(int uid, @NonNull String packageName, @Nullable String featureId) {
            this.uid = uid;
            this.packageName = packageName;
            this.featureId = featureId;
        }

        /**
         * Needed for migration of config store data.
         */
        public void setUid(int uid) {
            if (this.uid == Process.INVALID_UID) {
                this.uid = uid;
            }
            // else ignored.
        }

        // This is only needed for comparison in unit tests.
        @Override
        public boolean equals(Object other) {
            if (other == null) return false;
            if (!(other instanceof PerAppInfo)) return false;
            PerAppInfo otherPerAppInfo = (PerAppInfo) other;
            return uid == otherPerAppInfo.uid
                    && TextUtils.equals(packageName, otherPerAppInfo.packageName)
                    && Objects.equals(extNetworkSuggestions, otherPerAppInfo.extNetworkSuggestions)
                    && hasUserApproved == otherPerAppInfo.hasUserApproved;
        }

        // This is only needed for comparison in unit tests.
        @Override
        public int hashCode() {
            return Objects.hash(uid, packageName, extNetworkSuggestions, hasUserApproved);
        }

        @Override
        public String toString() {
            return new StringBuilder("PerAppInfo[ ")
                    .append("uid=").append(uid)
                    .append(", packageName=").append(packageName)
                    .append(", hasUserApproved=").append(hasUserApproved)
                    .append(", suggestions=").append(extNetworkSuggestions)
                    .append(" ]")
                    .toString();
        }
    }

    /**
     * Internal container class which holds a network suggestion and a pointer to the
     * {@link PerAppInfo} entry from {@link #mActiveNetworkSuggestionsPerApp} corresponding to the
     * app that made the suggestion.
     */
    public static class ExtendedWifiNetworkSuggestion {
        public final WifiNetworkSuggestion wns;
        // Store the pointer to the corresponding app's meta data.
        public final PerAppInfo perAppInfo;

        public ExtendedWifiNetworkSuggestion(@NonNull WifiNetworkSuggestion wns,
                                             @NonNull PerAppInfo perAppInfo) {
            this.wns = wns;
            this.perAppInfo = perAppInfo;
        }

        @Override
        public int hashCode() {
            return Objects.hash(wns, perAppInfo.uid, perAppInfo.packageName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ExtendedWifiNetworkSuggestion)) {
                return false;
            }
            ExtendedWifiNetworkSuggestion other = (ExtendedWifiNetworkSuggestion) obj;
            return wns.equals(other.wns)
                    && perAppInfo.uid == other.perAppInfo.uid
                    && TextUtils.equals(perAppInfo.packageName, other.perAppInfo.packageName);
        }

        @Override
        public String toString() {
            return wns.toString();
        }

        /**
         * Convert from {@link WifiNetworkSuggestion} to a new instance of
         * {@link ExtendedWifiNetworkSuggestion}.
         */
        public static ExtendedWifiNetworkSuggestion fromWns(
                @NonNull WifiNetworkSuggestion wns, @NonNull PerAppInfo perAppInfo) {
            return new ExtendedWifiNetworkSuggestion(wns, perAppInfo);
        }
    }

    /**
     * Map of package name of an app to the set of active network suggestions provided by the app.
     */
    private final Map<String, PerAppInfo> mActiveNetworkSuggestionsPerApp = new HashMap<>();
    /**
     * Map of package name of an app to the app ops changed listener for the app.
     */
    private final Map<String, AppOpsChangedListener> mAppOpsChangedListenerPerApp = new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions (with no bssid) that match a
     * provided scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<ScanResultMatchInfo, Set<ExtendedWifiNetworkSuggestion>>
            mActiveScanResultMatchInfoWithNoBssid = new HashMap<>();
    /**
     * Map maintained to help lookup all the network suggestions (with bssid) that match a provided
     * scan result.
     * Note:
     * <li>There could be multiple suggestions (provided by different apps) that match a single
     * scan result.</li>
     * <li>Adding/Removing to this set for scan result lookup is expensive. But, we expect scan
     * result lookup to happen much more often than apps modifying network suggestions.</li>
     */
    private final Map<Pair<ScanResultMatchInfo, MacAddress>, Set<ExtendedWifiNetworkSuggestion>>
            mActiveScanResultMatchInfoWithBssid = new HashMap<>();
    /**
     * List of {@link WifiNetworkSuggestion} matching the current connected network.
     */
    private Set<ExtendedWifiNetworkSuggestion> mActiveNetworkSuggestionsMatchingConnection;

    private final Map<String, Set<ExtendedWifiNetworkSuggestion>>
            mPasspointInfo = new HashMap<>();

    private final HashMap<String, ExternalCallbackTracker<ISuggestionConnectionStatusListener>>
            mSuggestionStatusListenerPerApp = new HashMap<>();

    /**
     * Intent filter for processing notification actions.
     */
    private final IntentFilter mIntentFilter;

    /**
     * Verbose logging flag.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Indicates that we have new data to serialize.
     */
    private boolean mHasNewDataToSerialize = false;
    /**
     * Indicates if the user approval notification is active.
     */
    private boolean mUserApprovalNotificationActive = false;
    /**
     * Stores the name of the user approval notification that is active.
     */
    private String mUserApprovalNotificationPackageName;

    /**
     * Listener for app-ops changes for active suggestor apps.
     */
    private final class AppOpsChangedListener implements AppOpsManager.OnOpChangedListener {
        private final String mPackageName;
        private final int mUid;

        AppOpsChangedListener(@NonNull String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        @Override
        public void onOpChanged(String op, String packageName) {
            mHandler.post(() -> {
                if (!mPackageName.equals(packageName)) return;
                if (!OPSTR_CHANGE_WIFI_STATE.equals(op)) return;

                // Ensure the uid to package mapping is still correct.
                try {
                    mAppOps.checkPackage(mUid, mPackageName);
                } catch (SecurityException e) {
                    Log.wtf(TAG, "Invalid uid/package" + packageName);
                    return;
                }

                if (mAppOps.unsafeCheckOpNoThrow(OPSTR_CHANGE_WIFI_STATE, mUid, mPackageName)
                        == AppOpsManager.MODE_IGNORED) {
                    Log.i(TAG, "User disallowed change wifi state for " + packageName);
                    // User disabled the app, remove app from database. We want the notification
                    // again if the user enabled the app-op back.
                    removeApp(mPackageName);
                }
            });
        }
    };

    /**
     * Module to interact with the wifi config store.
     */
    private class NetworkSuggestionDataSource implements NetworkSuggestionStoreData.DataSource {
        @Override
        public Map<String, PerAppInfo> toSerialize() {
            // Clear the flag after writing to disk.
            // TODO(b/115504887): Don't reset the flag on write failure.
            mHasNewDataToSerialize = false;
            return mActiveNetworkSuggestionsPerApp;
        }

        @Override
        public void fromDeserialized(Map<String, PerAppInfo> networkSuggestionsMap) {
            mActiveNetworkSuggestionsPerApp.putAll(networkSuggestionsMap);
            // Build the scan cache.
            for (Map.Entry<String, PerAppInfo> entry : networkSuggestionsMap.entrySet()) {
                String packageName = entry.getKey();
                Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                        entry.getValue().extNetworkSuggestions;
                if (!extNetworkSuggestions.isEmpty()) {
                    // Start tracking app-op changes from the app if they have active suggestions.
                    startTrackingAppOpsChange(packageName,
                            extNetworkSuggestions.iterator().next().perAppInfo.uid);
                }
                for (ExtendedWifiNetworkSuggestion ewns : extNetworkSuggestions) {
                    if (ewns.wns.wifiConfiguration.FQDN != null) {
                        addToPasspointInfoMap(ewns);
                    } else {
                        addToScanResultMatchInfoMap(ewns);
                    }
                }
            }
        }

        @Override
        public void reset() {
            mActiveNetworkSuggestionsPerApp.clear();
            mActiveScanResultMatchInfoWithBssid.clear();
            mActiveScanResultMatchInfoWithNoBssid.clear();
            mPasspointInfo.clear();
        }

        @Override
        public boolean hasNewDataToSerialize() {
            return mHasNewDataToSerialize;
        }
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
                    if (packageName == null) {
                        Log.e(TAG, "No package name found in intent");
                        return;
                    }
                    int uid = intent.getIntExtra(EXTRA_UID, -1);
                    if (uid == -1) {
                        Log.e(TAG, "No uid found in intent");
                        return;
                    }
                    switch (intent.getAction()) {
                        case NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION:
                            Log.i(TAG, "User clicked to allow app");
                            // Set the user approved flag.
                            setHasUserApprovedForApp(true, packageName);
                            break;
                        case NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION:
                            Log.i(TAG, "User clicked to disallow app");
                            // Set the user approved flag.
                            setHasUserApprovedForApp(false, packageName);
                            // Take away CHANGE_WIFI_STATE app-ops from the app.
                            mAppOps.setMode(AppOpsManager.OPSTR_CHANGE_WIFI_STATE, uid, packageName,
                                    MODE_IGNORED);
                            break;
                        case NOTIFICATION_USER_DISMISSED_INTENT_ACTION:
                            Log.i(TAG, "User dismissed the notification");
                            mUserApprovalNotificationActive = false;
                            return; // no need to cancel a dismissed notification, return.
                        default:
                            Log.e(TAG, "Unknown action " + intent.getAction());
                            return;
                    }
                    // Clear notification once the user interacts with it.
                    mUserApprovalNotificationActive = false;
                    mNotificationManager.cancel(SystemMessage.NOTE_NETWORK_SUGGESTION_AVAILABLE);
                }
            };

    public WifiNetworkSuggestionsManager(Context context, Handler handler,
                                         WifiInjector wifiInjector,
                                         WifiPermissionsUtil wifiPermissionsUtil,
                                         WifiConfigManager wifiConfigManager,
                                         WifiConfigStore wifiConfigStore,
                                         WifiMetrics wifiMetrics,
                                         TelephonyUtil telephonyUtil) {
        mContext = context;
        mResources = context.getResources();
        mHandler = handler;
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mPackageManager = context.getPackageManager();
        mWifiInjector = wifiInjector;
        mFrameworkFacade = mWifiInjector.getFrameworkFacade();
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mWifiConfigManager = wifiConfigManager;
        mWifiMetrics = wifiMetrics;
        mTelephonyUtil = telephonyUtil;

        // register the data store for serializing/deserializing data.
        wifiConfigStore.registerStoreData(
                wifiInjector.makeNetworkSuggestionStoreData(new NetworkSuggestionDataSource()));

        // Register broadcast receiver for UI interactions.
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION);
        mIntentFilter.addAction(NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION);
        mIntentFilter.addAction(NOTIFICATION_USER_DISMISSED_INTENT_ACTION);
        mContext.registerReceiver(mBroadcastReceiver, mIntentFilter);
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
    }

    private void saveToStore() {
        // Set the flag to let WifiConfigStore that we have new data to write.
        mHasNewDataToSerialize = true;
        if (!mWifiConfigManager.saveToStore(true)) {
            Log.w(TAG, "Failed to save to store");
        }
    }

    private void addToScanResultMatchInfoMap(
            @NonNull ExtendedWifiNetworkSuggestion extNetworkSuggestion) {
        ScanResultMatchInfo scanResultMatchInfo =
                ScanResultMatchInfo.fromWifiConfiguration(
                        extNetworkSuggestion.wns.wifiConfiguration);
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestionsForScanResultMatchInfo;
        if (!TextUtils.isEmpty(extNetworkSuggestion.wns.wifiConfiguration.BSSID)) {
            Pair<ScanResultMatchInfo, MacAddress> lookupPair =
                    Pair.create(scanResultMatchInfo,
                            MacAddress.fromString(
                                    extNetworkSuggestion.wns.wifiConfiguration.BSSID));
            extNetworkSuggestionsForScanResultMatchInfo =
                    mActiveScanResultMatchInfoWithBssid.get(lookupPair);
            if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                extNetworkSuggestionsForScanResultMatchInfo = new HashSet<>();
                mActiveScanResultMatchInfoWithBssid.put(
                        lookupPair, extNetworkSuggestionsForScanResultMatchInfo);
            }
        } else {
            extNetworkSuggestionsForScanResultMatchInfo =
                    mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
            if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                extNetworkSuggestionsForScanResultMatchInfo = new HashSet<>();
                mActiveScanResultMatchInfoWithNoBssid.put(
                        scanResultMatchInfo, extNetworkSuggestionsForScanResultMatchInfo);
            }
        }
        extNetworkSuggestionsForScanResultMatchInfo.remove(extNetworkSuggestion);
        extNetworkSuggestionsForScanResultMatchInfo.add(extNetworkSuggestion);
    }

    private void removeFromScanResultMatchInfoMap(
            @NonNull ExtendedWifiNetworkSuggestion extNetworkSuggestion) {
        ScanResultMatchInfo scanResultMatchInfo =
                ScanResultMatchInfo.fromWifiConfiguration(
                        extNetworkSuggestion.wns.wifiConfiguration);
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestionsForScanResultMatchInfo;
        if (!TextUtils.isEmpty(extNetworkSuggestion.wns.wifiConfiguration.BSSID)) {
            Pair<ScanResultMatchInfo, MacAddress> lookupPair =
                    Pair.create(scanResultMatchInfo,
                            MacAddress.fromString(
                                    extNetworkSuggestion.wns.wifiConfiguration.BSSID));
            extNetworkSuggestionsForScanResultMatchInfo =
                    mActiveScanResultMatchInfoWithBssid.get(lookupPair);
            // This should never happen because we should have done necessary error checks in
            // the parent method.
            if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                Log.wtf(TAG, "No scan result match info found.");
                return;
            }
            extNetworkSuggestionsForScanResultMatchInfo.remove(extNetworkSuggestion);
            // Remove the set from map if empty.
            if (extNetworkSuggestionsForScanResultMatchInfo.isEmpty()) {
                mActiveScanResultMatchInfoWithBssid.remove(lookupPair);
            }
        } else {
            extNetworkSuggestionsForScanResultMatchInfo =
                    mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
            // This should never happen because we should have done necessary error checks in
            // the parent method.
            if (extNetworkSuggestionsForScanResultMatchInfo == null) {
                Log.wtf(TAG, "No scan result match info found.");
                return;
            }
            extNetworkSuggestionsForScanResultMatchInfo.remove(extNetworkSuggestion);
            // Remove the set from map if empty.
            if (extNetworkSuggestionsForScanResultMatchInfo.isEmpty()) {
                mActiveScanResultMatchInfoWithNoBssid.remove(scanResultMatchInfo);
            }
        }
    }

    private void addToPasspointInfoMap(ExtendedWifiNetworkSuggestion ewns) {
        Set<ExtendedWifiNetworkSuggestion> extendedWifiNetworkSuggestions =
                mPasspointInfo.get(ewns.wns.wifiConfiguration.FQDN);
        if (extendedWifiNetworkSuggestions == null) {
            extendedWifiNetworkSuggestions = new HashSet<>();
        }
        extendedWifiNetworkSuggestions.add(ewns);
        mPasspointInfo.put(ewns.wns.wifiConfiguration.FQDN, extendedWifiNetworkSuggestions);
    }

    private void removeFromPassPointInfoMap(ExtendedWifiNetworkSuggestion ewns) {
        Set<ExtendedWifiNetworkSuggestion> extendedWifiNetworkSuggestions =
                mPasspointInfo.get(ewns.wns.wifiConfiguration.FQDN);
        if (extendedWifiNetworkSuggestions == null
                || !extendedWifiNetworkSuggestions.contains(ewns)) {
            Log.wtf(TAG, "No Passpoint info found.");
            return;
        }
        extendedWifiNetworkSuggestions.remove(ewns);
        if (extendedWifiNetworkSuggestions.isEmpty()) {
            mPasspointInfo.remove(ewns.wns.wifiConfiguration.FQDN);
        }
    }


    // Issues a disconnect if the only serving network suggestion is removed.
    private void removeFromConfigManagerIfServingNetworkSuggestionRemoved(
            Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestionsRemoved) {
        if (mActiveNetworkSuggestionsMatchingConnection == null
                || mActiveNetworkSuggestionsMatchingConnection.isEmpty()) {
            return;
        }
        WifiConfiguration activeWifiConfiguration =
                mActiveNetworkSuggestionsMatchingConnection.iterator().next().wns.wifiConfiguration;
        if (mActiveNetworkSuggestionsMatchingConnection.removeAll(extNetworkSuggestionsRemoved)) {
            if (mActiveNetworkSuggestionsMatchingConnection.isEmpty()) {
                Log.i(TAG, "Only network suggestion matching the connected network removed. "
                        + "Removing from config manager...");
                // will trigger a disconnect.
                mWifiConfigManager.removeSuggestionConfiguredNetwork(
                        activeWifiConfiguration.getKey());
            }
        }
    }

    private void startTrackingAppOpsChange(@NonNull String packageName, int uid) {
        AppOpsChangedListener appOpsChangedListener =
                new AppOpsChangedListener(packageName, uid);
        mAppOps.startWatchingMode(OPSTR_CHANGE_WIFI_STATE, packageName, appOpsChangedListener);
        mAppOpsChangedListenerPerApp.put(packageName, appOpsChangedListener);
    }

    /**
     * Helper method to convert the incoming collection of public {@link WifiNetworkSuggestion}
     * objects to a set of corresponding internal wrapper
     * {@link ExtendedWifiNetworkSuggestion} objects.
     */
    private Set<ExtendedWifiNetworkSuggestion> convertToExtendedWnsSet(
            final Collection<WifiNetworkSuggestion> networkSuggestions,
            final PerAppInfo perAppInfo) {
        return networkSuggestions
                .stream()
                .collect(Collectors.mapping(
                        n -> ExtendedWifiNetworkSuggestion.fromWns(n, perAppInfo),
                        Collectors.toSet()));
    }

    /**
     * Helper method to convert the incoming collection of internal wrapper
     * {@link ExtendedWifiNetworkSuggestion} objects to a set of corresponding public
     * {@link WifiNetworkSuggestion} objects.
     */
    private Set<WifiNetworkSuggestion> convertToWnsSet(
            final Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestions) {
        return extNetworkSuggestions
                .stream()
                .collect(Collectors.mapping(
                        n -> n.wns,
                        Collectors.toSet()));
    }

    /**
     * Add the provided list of network suggestions from the corresponding app's active list.
     */
    public @WifiManager.NetworkSuggestionsStatusCode int add(
            List<WifiNetworkSuggestion> networkSuggestions, int uid, String packageName,
            @Nullable String featureId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding " + networkSuggestions.size() + " networks from " + packageName);
        }
        if (networkSuggestions.isEmpty()) {
            Log.w(TAG, "Empty list of network suggestions for " + packageName + ". Ignoring");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
        }
        if (!validateNetworkSuggestions(networkSuggestions, uid, packageName)) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED;
        }
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) {
            perAppInfo = new PerAppInfo(uid, packageName, featureId);
            mActiveNetworkSuggestionsPerApp.put(packageName, perAppInfo);
            if (mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(uid)) {
                Log.i(TAG, "Setting the carrier provisioning app approved");
                perAppInfo.hasUserApproved = true;
            } else {
                sendUserApprovalNotification(packageName, uid);
            }
        }
        // If PerAppInfo is upgrade from pre-R, uid may not be set.
        perAppInfo.setUid(uid);
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                convertToExtendedWnsSet(networkSuggestions, perAppInfo);
        boolean isLowRamDevice = mContext.getSystemService(ActivityManager.class).isLowRamDevice();
        int networkSuggestionsMaxPerApp =
                WifiManager.getMaxNumberOfNetworkSuggestionsPerApp(isLowRamDevice);
        if (perAppInfo.extNetworkSuggestions.size() + extNetworkSuggestions.size()
                > networkSuggestionsMaxPerApp) {
            Set<ExtendedWifiNetworkSuggestion> savedNetworkSuggestions =
                    new HashSet<>(perAppInfo.extNetworkSuggestions);
            savedNetworkSuggestions.addAll(extNetworkSuggestions);
            if (savedNetworkSuggestions.size() > networkSuggestionsMaxPerApp) {
                Log.e(TAG, "Failed to add network suggestions for " + packageName
                        + ". Exceeds max per app, current list size: "
                        + perAppInfo.extNetworkSuggestions.size()
                        + ", new list size: "
                        + extNetworkSuggestions.size());
                return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP;
            }
        }
        if (perAppInfo.extNetworkSuggestions.isEmpty()) {
            // Start tracking app-op changes from the app if they have active suggestions.
            startTrackingAppOpsChange(packageName, uid);
        }
        for (ExtendedWifiNetworkSuggestion ewns: extNetworkSuggestions) {
            if (ewns.wns.passpointConfiguration == null) {
                addToScanResultMatchInfoMap(ewns);
            } else {
                // Install Passpoint config, if failure, ignore that suggestion
                if (!mWifiInjector.getPasspointManager().addOrUpdateProvider(
                        ewns.wns.passpointConfiguration, uid,
                        packageName, true)) {
                    Log.e(TAG, "Passpoint profile install failure.");
                    continue;
                }
                addToPasspointInfoMap(ewns);
            }
            perAppInfo.extNetworkSuggestions.remove(ewns);
            perAppInfo.extNetworkSuggestions.add(ewns);
        }
        // Update the max size for this app.
        perAppInfo.maxSize = Math.max(perAppInfo.extNetworkSuggestions.size(), perAppInfo.maxSize);
        saveToStore();
        mWifiMetrics.incrementNetworkSuggestionApiNumModification();
        mWifiMetrics.noteNetworkSuggestionApiListSizeHistogram(getAllMaxSizes());
        return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
    }

    private boolean validateNetworkSuggestions(
            List<WifiNetworkSuggestion> networkSuggestions, int uid, String packageName) {
        if (mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(uid)) {
            return true;
        }
        for (WifiNetworkSuggestion suggestion : networkSuggestions) {
            WifiConfiguration config = suggestion.wifiConfiguration;
            if (config != null
                    && config.carrierId != TelephonyManager.UNKNOWN_CARRIER_ID) {
                Log.e(TAG, "bad wifi suggestion from app: " + packageName);
                return false;
            }
        }

        return true;
    }

    private void stopTrackingAppOpsChange(@NonNull String packageName) {
        AppOpsChangedListener appOpsChangedListener =
                mAppOpsChangedListenerPerApp.remove(packageName);
        if (appOpsChangedListener == null) {
            Log.wtf(TAG, "No app ops listener found for " + packageName);
            return;
        }
        mAppOps.stopWatchingMode(appOpsChangedListener);
    }

    /**
     * Remove provided list from that App active list. If provided list is empty, will remove all.
     * Will disconnect network if current connected network is in the remove list.
     */
    private void removeInternal(
            @NonNull Collection<ExtendedWifiNetworkSuggestion> extNetworkSuggestions,
            @NonNull String packageName,
            @NonNull PerAppInfo perAppInfo) {
        if (!extNetworkSuggestions.isEmpty()) {
            perAppInfo.extNetworkSuggestions.removeAll(extNetworkSuggestions);
        } else {
            // empty list is used to clear everything for the app. Store a copy for use below.
            extNetworkSuggestions = new HashSet<>(perAppInfo.extNetworkSuggestions);
            perAppInfo.extNetworkSuggestions.clear();
        }
        if (perAppInfo.extNetworkSuggestions.isEmpty()) {
            // Note: We don't remove the app entry even if there is no active suggestions because
            // we want to keep the notification state for all apps that have ever provided
            // suggestions.
            if (mVerboseLoggingEnabled) Log.v(TAG, "No active suggestions for " + packageName);
            // Stop tracking app-op changes from the app if they don't have active suggestions.
            stopTrackingAppOpsChange(packageName);
        }
        // Clear the cache.
        for (ExtendedWifiNetworkSuggestion ewns : extNetworkSuggestions) {
            if (ewns.wns.wifiConfiguration.FQDN != null) {
                // Clear the Passpoint config.
                mWifiInjector.getPasspointManager().removeProvider(
                        ewns.perAppInfo.uid,
                        false,
                        ewns.wns.wifiConfiguration.FQDN);
                removeFromPassPointInfoMap(ewns);
            } else {
                removeFromScanResultMatchInfoMap(ewns);
            }
        }
        // Disconnect suggested network if connected
        removeFromConfigManagerIfServingNetworkSuggestionRemoved(extNetworkSuggestions);
    }

    /**
     * Remove the provided list of network suggestions from the corresponding app's active list.
     */
    public @WifiManager.NetworkSuggestionsStatusCode int remove(
            List<WifiNetworkSuggestion> networkSuggestions, int uid, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing " + networkSuggestions.size() + " networks from " + packageName);
        }
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". No network suggestions found");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                convertToExtendedWnsSet(networkSuggestions, perAppInfo);
        // check if all the request network suggestions are present in the active list.
        if (!extNetworkSuggestions.isEmpty()
                && !perAppInfo.extNetworkSuggestions.containsAll(extNetworkSuggestions)) {
            Log.e(TAG, "Failed to remove network suggestions for " + packageName
                    + ". Network suggestions not found in active network suggestions");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID;
        }
        removeInternal(extNetworkSuggestions, packageName, perAppInfo);
        saveToStore();
        mWifiMetrics.incrementNetworkSuggestionApiNumModification();
        mWifiMetrics.noteNetworkSuggestionApiListSizeHistogram(getAllMaxSizes());
        return WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;
    }

    /**
     * Remove all tracking of the app that has been uninstalled.
     */
    public void removeApp(@NonNull String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return;
        removeInternal(Collections.EMPTY_LIST, packageName, perAppInfo);
        // Remove the package fully from the internal database.
        mActiveNetworkSuggestionsPerApp.remove(packageName);
        ExternalCallbackTracker<ISuggestionConnectionStatusListener> listenerTracker =
                mSuggestionStatusListenerPerApp.remove(packageName);
        if (listenerTracker != null) listenerTracker.clear();
        saveToStore();
        Log.i(TAG, "Removed " + packageName);
    }

    /**
     * Get all network suggestion for target App
     * @return List of WifiNetworkSuggestions
     */
    public @NonNull List<WifiNetworkSuggestion> get(@NonNull String packageName) {
        List<WifiNetworkSuggestion> networkSuggestionList = new ArrayList<>();
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        // if App never suggested return empty list.
        if (perAppInfo == null) return networkSuggestionList;
        for (ExtendedWifiNetworkSuggestion extendedSuggestion : perAppInfo.extNetworkSuggestions) {
            networkSuggestionList.add(extendedSuggestion.wns);
        }
        return networkSuggestionList;
    }


    /**
     * Clear all internal state (for network settings reset).
     */
    public void clear() {
        Iterator<Map.Entry<String, PerAppInfo>> iter =
                mActiveNetworkSuggestionsPerApp.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, PerAppInfo> entry = iter.next();
            removeInternal(Collections.EMPTY_LIST, entry.getKey(), entry.getValue());
            iter.remove();
        }
        mSuggestionStatusListenerPerApp.clear();
        saveToStore();
        Log.i(TAG, "Cleared all internal state");
    }

    /**
     * Check if network suggestions are enabled or disabled for the app.
     */
    public boolean hasUserApprovedForApp(String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return false;

        return perAppInfo.hasUserApproved;
    }

    /**
     * Enable or Disable network suggestions for the app.
     */
    public void setHasUserApprovedForApp(boolean approved, String packageName) {
        PerAppInfo perAppInfo = mActiveNetworkSuggestionsPerApp.get(packageName);
        if (perAppInfo == null) return;

        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Setting the app " + (approved ? "approved" : "not approved"));
        }
        perAppInfo.hasUserApproved = approved;
        saveToStore();
    }

    /**
     * Returns a set of all network suggestions across all apps.
     */
    @VisibleForTesting
    public Set<WifiNetworkSuggestion> getAllNetworkSuggestions() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .flatMap(e -> convertToWnsSet(e.extNetworkSuggestions)
                        .stream())
                .collect(Collectors.toSet());
    }

    private List<Integer> getAllMaxSizes() {
        return mActiveNetworkSuggestionsPerApp.values()
                .stream()
                .map(e -> e.maxSize)
                .collect(Collectors.toList());
    }

    private PendingIntent getPrivateBroadcast(@NonNull String action, @NonNull String packageName,
                                              int uid) {
        Intent intent = new Intent(action)
                .setPackage(mWifiInjector.getWifiStackPackageName())
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_UID, uid);
        return mFrameworkFacade.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private @NonNull CharSequence getAppName(@NonNull String packageName, int uid) {
        ApplicationInfo applicationInfo = null;
        try {
            applicationInfo = mContext.getPackageManager().getApplicationInfoAsUser(
                packageName, 0, UserHandle.getUserHandleForUid(uid));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to find app name for " + packageName);
            return "";
        }
        CharSequence appName = mPackageManager.getApplicationLabel(applicationInfo);
        return (appName != null) ? appName : "";
    }

    private void sendUserApprovalNotification(@NonNull String packageName, int uid) {
        Notification.Action userAllowAppNotificationAction =
                new Notification.Action.Builder(null,
                        mResources.getText(R.string.wifi_suggestion_action_allow_app),
                        getPrivateBroadcast(NOTIFICATION_USER_ALLOWED_APP_INTENT_ACTION,
                                packageName, uid))
                        .build();
        Notification.Action userDisallowAppNotificationAction =
                new Notification.Action.Builder(null,
                        mResources.getText(R.string.wifi_suggestion_action_disallow_app),
                        getPrivateBroadcast(NOTIFICATION_USER_DISALLOWED_APP_INTENT_ACTION,
                                packageName, uid))
                        .build();

        CharSequence appName = getAppName(packageName, uid);
        Notification notification = new Notification.Builder(
                mContext, WifiService.NOTIFICATION_NETWORK_STATUS)
                .setSmallIcon(com.android.wifi.resources.R.drawable.stat_notify_wifi_in_range)
                .setTicker(mResources.getString(R.string.wifi_suggestion_title))
                .setContentTitle(mResources.getString(R.string.wifi_suggestion_title))
                .setStyle(new Notification.BigTextStyle()
                        .bigText(mResources.getString(R.string.wifi_suggestion_content, appName)))
                .setDeleteIntent(getPrivateBroadcast(NOTIFICATION_USER_DISMISSED_INTENT_ACTION,
                        packageName, uid))
                .setShowWhen(false)
                .setLocalOnly(true)
                .setColor(mResources.getColor(android.R.color.system_notification_accent_color,
                        mContext.getTheme()))
                .addAction(userAllowAppNotificationAction)
                .addAction(userDisallowAppNotificationAction)
                .build();

        // Post the notification.
        mNotificationManager.notify(
                SystemMessage.NOTE_NETWORK_SUGGESTION_AVAILABLE, notification);
        mUserApprovalNotificationActive = true;
        mUserApprovalNotificationPackageName = packageName;
    }

    /**
     * Send user approval notification if the app is not approved
     * @param packageName app package name
     * @param uid app UID
     * @return true if app is not approved and send notification.
     */
    public boolean sendUserApprovalNotificationIfNotApproved(
            @NonNull String packageName, @NonNull int uid) {
        if (!mActiveNetworkSuggestionsPerApp.containsKey(packageName)) {
            Log.wtf(TAG, "AppInfo is missing for " + packageName);
            return false;
        }
        if (mActiveNetworkSuggestionsPerApp.get(packageName).hasUserApproved) {
            return false; // already approved.
        }

        Log.i(TAG, "Sending user approval notification for " + packageName);
        if (!mUserApprovalNotificationActive) {
            sendUserApprovalNotification(packageName, uid);
        }
        return true;
    }

    private @Nullable Set<ExtendedWifiNetworkSuggestion>
            getNetworkSuggestionsForScanResultMatchInfo(
            @NonNull ScanResultMatchInfo scanResultMatchInfo, @Nullable MacAddress bssid) {
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = new HashSet<>();
        if (bssid != null) {
            Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestionsWithBssid =
                    mActiveScanResultMatchInfoWithBssid.get(
                            Pair.create(scanResultMatchInfo, bssid));
            if (matchingExtNetworkSuggestionsWithBssid != null) {
                extNetworkSuggestions.addAll(matchingExtNetworkSuggestionsWithBssid);
            }
        }
        Set<ExtendedWifiNetworkSuggestion> matchingNetworkSuggestionsWithNoBssid =
                mActiveScanResultMatchInfoWithNoBssid.get(scanResultMatchInfo);
        if (matchingNetworkSuggestionsWithNoBssid != null) {
            extNetworkSuggestions.addAll(matchingNetworkSuggestionsWithNoBssid);
        }
        if (extNetworkSuggestions.isEmpty()) {
            return null;
        }
        return extNetworkSuggestions;
    }

    private @Nullable Set<ExtendedWifiNetworkSuggestion> getNetworkSuggestionsForFqdnMatch(
            @Nullable String fqdn) {
        if (TextUtils.isEmpty(fqdn)) {
            return null;
        }
        return mPasspointInfo.get(fqdn);
    }

    /**
     * Returns a set of all network suggestions matching the provided FQDN.
     */
    public @Nullable Set<ExtendedWifiNetworkSuggestion> getNetworkSuggestionsForFqfn(String fqdn) {
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions =
                getNetworkSuggestionsForFqdnMatch(fqdn);
        if (extNetworkSuggestions == null) {
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> approvedExtNetworkSuggestions =
                extNetworkSuggestions
                        .stream()
                        .filter(n -> n.perAppInfo.hasUserApproved)
                        .collect(Collectors.toSet());
        // If there is no active notification, check if we need to get approval for any of the apps
        // & send a notification for one of them. If there are multiple packages awaiting approval,
        // we end up picking the first one. The others will be reconsidered in the next iteration.
        if (!mUserApprovalNotificationActive
                && approvedExtNetworkSuggestions.size() != extNetworkSuggestions.size()) {
            for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
                if (sendUserApprovalNotificationIfNotApproved(
                        extNetworkSuggestion.perAppInfo.packageName,
                        extNetworkSuggestion.perAppInfo.uid)) {
                    break;
                }
            }
        }
        if (approvedExtNetworkSuggestions.isEmpty()) {
            return null;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "getNetworkSuggestionsForFqdn Found "
                    + approvedExtNetworkSuggestions + " for " + fqdn);
        }
        return approvedExtNetworkSuggestions;
    }

    /**
     * Returns a set of all network suggestions matching the provided scan detail.
     */
    public @Nullable Set<ExtendedWifiNetworkSuggestion> getNetworkSuggestionsForScanDetail(
            @NonNull ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) {
            Log.e(TAG, "No scan result found in scan detail");
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = null;
        try {
            ScanResultMatchInfo scanResultMatchInfo =
                    ScanResultMatchInfo.fromScanResult(scanResult);
            extNetworkSuggestions = getNetworkSuggestionsForScanResultMatchInfo(
                    scanResultMatchInfo,  MacAddress.fromString(scanResult.BSSID));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to lookup network from scan result match info map", e);
        }
        if (extNetworkSuggestions == null) {
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> approvedExtNetworkSuggestions = extNetworkSuggestions
                .stream()
                .filter(n -> {
                    if (!n.perAppInfo.hasUserApproved) {
                        return false;
                    }
                    WifiConfiguration config = n.wns.wifiConfiguration;
                    if (config != null && config.enterpriseConfig != null
                            && config.enterpriseConfig.requireSimCredential()) {
                        int subId = mTelephonyUtil.getBestMatchSubscriptionId(config);
                        if (!mTelephonyUtil.isSimPresent(subId)
                                || (mTelephonyUtil.requiresImsiEncryption(subId)
                                        && !mTelephonyUtil.isImsiEncryptionInfoAvailable(subId))) {
                            if (mVerboseLoggingEnabled) {
                                Log.v(TAG, "No SIM is matched or IMSI encryption "
                                        + "info is required, ignore the config.");
                            }
                            return false;
                        }
                    }
                    return true;
                }).collect(Collectors.toSet());
        // If there is no active notification, check if we need to get approval for any of the apps
        // & send a notification for one of them. If there are multiple packages awaiting approval,
        // we end up picking the first one. The others will be reconsidered in the next iteration.
        if (!mUserApprovalNotificationActive
                && approvedExtNetworkSuggestions.size() != extNetworkSuggestions.size()) {
            for (ExtendedWifiNetworkSuggestion extNetworkSuggestion : extNetworkSuggestions) {
                if (sendUserApprovalNotificationIfNotApproved(
                        extNetworkSuggestion.perAppInfo.packageName,
                        extNetworkSuggestion.perAppInfo.uid)) {
                    break;
                }
            }
        }
        if (approvedExtNetworkSuggestions.isEmpty()) {
            return null;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "getNetworkSuggestionsForScanDetail Found "
                    + approvedExtNetworkSuggestions + " for " + scanResult.SSID
                    + "[" + scanResult.capabilities + "]");
        }
        return approvedExtNetworkSuggestions;
    }

    /**
     * Returns a set of all network suggestions matching the provided the WifiConfiguration.
     */
    public @Nullable Set<ExtendedWifiNetworkSuggestion> getNetworkSuggestionsForWifiConfiguration(
            @NonNull WifiConfiguration wifiConfiguration, @Nullable String bssid) {
        Set<ExtendedWifiNetworkSuggestion> extNetworkSuggestions = null;
        if (wifiConfiguration.isPasspoint()) {
            extNetworkSuggestions = getNetworkSuggestionsForFqdnMatch(wifiConfiguration.FQDN);
        } else {
            try {
                ScanResultMatchInfo scanResultMatchInfo =
                        ScanResultMatchInfo.fromWifiConfiguration(wifiConfiguration);
                extNetworkSuggestions = getNetworkSuggestionsForScanResultMatchInfo(
                        scanResultMatchInfo, bssid == null ? null : MacAddress.fromString(bssid));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to lookup network from scan result match info map", e);
            }
        }
        if (extNetworkSuggestions == null || extNetworkSuggestions.isEmpty()) {
            return null;
        }
        Set<ExtendedWifiNetworkSuggestion> approvedExtNetworkSuggestions =
                extNetworkSuggestions
                        .stream()
                        .filter(n -> n.perAppInfo.hasUserApproved)
                        .collect(Collectors.toSet());
        if (approvedExtNetworkSuggestions.isEmpty()) {
            return null;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "getNetworkSuggestionsForWifiConfiguration Found "
                    + approvedExtNetworkSuggestions + " for " + wifiConfiguration.SSID
                    + wifiConfiguration.FQDN + "[" + wifiConfiguration.allowedKeyManagement + "]");
        }
        return approvedExtNetworkSuggestions;
    }

    /**
     * Get hidden network from active network suggestions.
     * Todo(): Now limit by a fixed number, maybe we can try rotation?
     * @return set of WifiConfigurations
     */
    public List<WifiScanner.ScanSettings.HiddenNetwork> retrieveHiddenNetworkList() {
        List<WifiScanner.ScanSettings.HiddenNetwork> hiddenNetworks = new ArrayList<>();
        for (PerAppInfo appInfo : mActiveNetworkSuggestionsPerApp.values()) {
            if (!appInfo.hasUserApproved) continue;
            for (ExtendedWifiNetworkSuggestion ewns : appInfo.extNetworkSuggestions) {
                if (!ewns.wns.wifiConfiguration.hiddenSSID) continue;
                hiddenNetworks.add(
                        new WifiScanner.ScanSettings.HiddenNetwork(
                                ewns.wns.wifiConfiguration.SSID));
                if (hiddenNetworks.size() >= NUMBER_OF_HIDDEN_NETWORK_FOR_ONE_SCAN) {
                    return hiddenNetworks;
                }
            }
        }
        return hiddenNetworks;
    }

    /**
     * Helper method to send the post connection broadcast to specified package.
     */
    private void sendPostConnectionBroadcast(
            ExtendedWifiNetworkSuggestion extSuggestion) {
        Intent intent = new Intent(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
        intent.putExtra(WifiManager.EXTRA_NETWORK_SUGGESTION, extSuggestion.wns);
        // Intended to wakeup the receiving app so set the specific package name.
        intent.setPackage(extSuggestion.perAppInfo.packageName);
        mContext.sendBroadcastAsUser(
                intent, UserHandle.getUserHandleForUid(extSuggestion.perAppInfo.uid));
    }

    /**
     * Helper method to send the post connection broadcast to specified package.
     */
    private void sendPostConnectionBroadcastIfAllowed(
            ExtendedWifiNetworkSuggestion matchingExtSuggestion, @NonNull String message) {
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(
                    matchingExtSuggestion.perAppInfo.packageName,
                    matchingExtSuggestion.perAppInfo.featureId,
                    matchingExtSuggestion.perAppInfo.uid, message);
        } catch (SecurityException se) {
            Log.w(TAG, "Permission denied for sending post connection broadcast to "
                    + matchingExtSuggestion.perAppInfo.packageName);
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Sending post connection broadcast to "
                    + matchingExtSuggestion.perAppInfo.packageName);
        }
        sendPostConnectionBroadcast(matchingExtSuggestion);
    }

    /**
     * Send out the {@link WifiManager#ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION} to the
     * network suggestion that provided credential for the current connection network.
     * If current connection network is open user saved network, broadcast will be only sent out to
     * one of the carrier apps that suggested matched network suggestions.
     *
     * @param connectedNetwork {@link WifiConfiguration} representing the network connected to.
     * @param connectedBssid BSSID of the network connected to.
     */
    private void handleConnectionSuccess(
            @NonNull WifiConfiguration connectedNetwork, @NonNull String connectedBssid) {
        if (!(connectedNetwork.fromWifiNetworkSuggestion || connectedNetwork.isOpenNetwork())) {
            return;
        }

        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestions =
                    getNetworkSuggestionsForWifiConfiguration(connectedNetwork, connectedBssid);

        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Network suggestions matching the connection "
                    + matchingExtNetworkSuggestions);
        }
        if (matchingExtNetworkSuggestions == null
                || matchingExtNetworkSuggestions.isEmpty()) return;

        mWifiMetrics.incrementNetworkSuggestionApiNumConnectSuccess();
        if (connectedNetwork.fromWifiNetworkSuggestion) {
            // Find subset of network suggestions from app suggested the connected network.
            matchingExtNetworkSuggestions =
                    matchingExtNetworkSuggestions.stream()
                            .filter(x -> x.perAppInfo.uid == connectedNetwork.creatorUid)
                            .collect(Collectors.toSet());
            if (matchingExtNetworkSuggestions.isEmpty()) {
                Log.wtf(TAG, "Current connected network suggestion is missing!");
                return;
            }
        } else {
            //TODO(143173638) open user saved network should only post notification to one of
            // carrier app.
        }
        // Store the set of matching network suggestions.
        mActiveNetworkSuggestionsMatchingConnection =
                new HashSet<>(matchingExtNetworkSuggestions);

        // Find subset of network suggestions have set |isAppInteractionRequired|.
        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestionsWithReqAppInteraction =
                matchingExtNetworkSuggestions.stream()
                        .filter(x -> x.wns.isAppInteractionRequired)
                        .collect(Collectors.toSet());
        if (matchingExtNetworkSuggestionsWithReqAppInteraction.isEmpty()) return;

        // Iterate over the matching network suggestions list:
        // a) Ensure that these apps have the necessary location permissions.
        // b) Send directed broadcast to the app with their corresponding network suggestion.
        for (ExtendedWifiNetworkSuggestion matchingExtNetworkSuggestion
                : matchingExtNetworkSuggestionsWithReqAppInteraction) {
            sendPostConnectionBroadcastIfAllowed(
                    matchingExtNetworkSuggestion,
                    "Connected to " + matchingExtNetworkSuggestion.wns.wifiConfiguration.SSID
                            + ". featureId is first feature of the app using network suggestions");
        }
    }

    /**
     * Handle connection failure.
     *
     * @param network {@link WifiConfiguration} representing the network that connection failed to.
     * @param bssid BSSID of the network connection failed to if known, else null.
     * @param failureCode failure reason code.
     */
    private void handleConnectionFailure(@NonNull WifiConfiguration network,
                                         @Nullable String bssid, int failureCode) {
        if (!network.fromWifiNetworkSuggestion) {
            return;
        }
        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestions =
                getNetworkSuggestionsForWifiConfiguration(network, bssid);
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Network suggestions matching the connection failure "
                    + matchingExtNetworkSuggestions);
        }
        if (matchingExtNetworkSuggestions == null
                || matchingExtNetworkSuggestions.isEmpty()) return;

        mWifiMetrics.incrementNetworkSuggestionApiNumConnectFailure();
        // TODO (b/115504887, b/112196799): Blacklist the corresponding network suggestion if
        // the connection failed.

        // Find subset of network suggestions which suggested the connection failure network.
        Set<ExtendedWifiNetworkSuggestion> matchingExtNetworkSuggestionsFromTargetApp =
                matchingExtNetworkSuggestions.stream()
                        .filter(x -> x.perAppInfo.uid == network.creatorUid)
                        .collect(Collectors.toSet());
        if (matchingExtNetworkSuggestionsFromTargetApp.isEmpty()) {
            Log.wtf(TAG, "Current connection failure network suggestion is missing!");
            return;
        }

        for (ExtendedWifiNetworkSuggestion matchingExtNetworkSuggestion
                : matchingExtNetworkSuggestionsFromTargetApp) {
            sendConnectionFailureIfAllowed(matchingExtNetworkSuggestion.perAppInfo.packageName,
                    matchingExtNetworkSuggestion.perAppInfo.featureId,
                    matchingExtNetworkSuggestion.perAppInfo.uid,
                    matchingExtNetworkSuggestion.wns, failureCode);
        }
    }

    private void resetConnectionState() {
        mActiveNetworkSuggestionsMatchingConnection = null;
    }

    /**
     * Invoked by {@link ClientModeImpl} on end of connection attempt to a network.
     *
     * @param failureCode Failure codes representing {@link WifiMetrics.ConnectionEvent} codes.
     * @param network WifiConfiguration corresponding to the current network.
     * @param bssid BSSID of the current network.
     */
    public void handleConnectionAttemptEnded(
            int failureCode, @NonNull WifiConfiguration network, @Nullable String bssid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleConnectionAttemptEnded " + failureCode + ", " + network);
        }
        resetConnectionState();
        if (failureCode == WifiMetrics.ConnectionEvent.FAILURE_NONE) {
            handleConnectionSuccess(network, bssid);
        } else {
            handleConnectionFailure(network, bssid, failureCode);
        }
    }

    /**
     * Invoked by {@link ClientModeImpl} on disconnect from network.
     */
    public void handleDisconnect(@NonNull WifiConfiguration network, @NonNull String bssid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "handleDisconnect " + network);
        }
        resetConnectionState();
    }

    /**
     * Send network connection failure event to app when an connection attempt failure.
     * @param packageName package name to send event
     * @param featureId The feature in the package
     * @param uid uid of the app.
     * @param matchingSuggestion suggestion on this connection failure
     * @param connectionEvent connection failure code
     */
    private void sendConnectionFailureIfAllowed(String packageName, @Nullable String featureId,
            int uid, @NonNull WifiNetworkSuggestion matchingSuggestion, int connectionEvent) {
        ExternalCallbackTracker<ISuggestionConnectionStatusListener> listenersTracker =
                mSuggestionStatusListenerPerApp.get(packageName);
        if (listenersTracker == null || listenersTracker.getNumCallbacks() == 0) {
            return;
        }
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(
                    packageName, featureId, uid, "Connection failure");
        } catch (SecurityException se) {
            Log.w(TAG, "Permission denied for sending connection failure event to " + packageName);
            return;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Sending connection failure event to " + packageName);
        }
        for (ISuggestionConnectionStatusListener listener : listenersTracker.getCallbacks()) {
            try {
                listener.onConnectionStatus(matchingSuggestion,
                        internalConnectionEventToSuggestionFailureCode(connectionEvent));
            } catch (RemoteException e) {
                Log.e(TAG, "sendNetworkCallback: remote exception -- " + e);
            }
        }
    }

    private @WifiManager.SuggestionConnectionStatusCode
            int internalConnectionEventToSuggestionFailureCode(int connectionEvent) {
        switch (connectionEvent) {
            case WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_REJECTION:
            case WifiMetrics.ConnectionEvent.FAILURE_ASSOCIATION_TIMED_OUT:
                return WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_ASSOCIATION;
            case WifiMetrics.ConnectionEvent.FAILURE_SSID_TEMP_DISABLED:
            case WifiMetrics.ConnectionEvent.FAILURE_AUTHENTICATION_FAILURE:
                return WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_AUTHENTICATION;
            case WifiMetrics.ConnectionEvent.FAILURE_DHCP:
                return WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_IP_PROVISIONING;
            default:
                return WifiManager.STATUS_SUGGESTION_CONNECTION_FAILURE_UNKNOWN;
        }

    }

    /**
     * Register a SuggestionConnectionStatusListener on network connection failure.
     * @param binder IBinder instance to allow cleanup if the app dies.
     * @param listener ISuggestionNetworkCallback instance to add.
     * @param listenerIdentifier identifier of the listener, should be hash code of listener.
     * @return true if succeed otherwise false.
     */
    public boolean registerSuggestionConnectionStatusListener(@NonNull IBinder binder,
            @NonNull ISuggestionConnectionStatusListener listener,
            int listenerIdentifier, String packageName) {
        ExternalCallbackTracker<ISuggestionConnectionStatusListener> listenersTracker =
                mSuggestionStatusListenerPerApp.get(packageName);
        if (listenersTracker == null) {
            listenersTracker =
                    new ExternalCallbackTracker<>(mHandler);
        }
        listenersTracker.add(binder, listener, listenerIdentifier);
        mSuggestionStatusListenerPerApp.put(packageName, listenersTracker);
        return true;
    }

    /**
     * Unregister a listener on network connection failure.
     * @param listenerIdentifier identifier of the listener, should be hash code of listener.
     */
    public void unregisterSuggestionConnectionStatusListener(int listenerIdentifier,
            String packageName) {
        ExternalCallbackTracker<ISuggestionConnectionStatusListener> listenersTracker =
                mSuggestionStatusListenerPerApp.get(packageName);
        if (listenersTracker == null || listenersTracker.remove(listenerIdentifier) == null) {
            Log.w(TAG, "unregisterSuggestionConnectionStatusListener: Listener["
                    + listenerIdentifier + "] from " + packageName + " already unregister.");
        }
        if (listenersTracker.getNumCallbacks() == 0) {
            mSuggestionStatusListenerPerApp.remove(packageName);
        }
    }

    /**
     * Dump of {@link WifiNetworkSuggestionsManager}.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiNetworkSuggestionsManager");
        pw.println("WifiNetworkSuggestionsManager - Networks Begin ----");
        for (Map.Entry<String, PerAppInfo> networkSuggestionsEntry
                : mActiveNetworkSuggestionsPerApp.entrySet()) {
            pw.println("Package Name: " + networkSuggestionsEntry.getKey());
            PerAppInfo appInfo = networkSuggestionsEntry.getValue();
            pw.println("Has user approved: " + appInfo.hasUserApproved);
            for (ExtendedWifiNetworkSuggestion extNetworkSuggestion
                    : appInfo.extNetworkSuggestions) {
                pw.println("Network: " + extNetworkSuggestion);
            }
        }
        pw.println("WifiNetworkSuggestionsManager - Networks End ----");
        pw.println("WifiNetworkSuggestionsManager - Network Suggestions matching connection: "
                + mActiveNetworkSuggestionsMatchingConnection);
    }
}

