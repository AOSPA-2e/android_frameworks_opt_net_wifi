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

package com.android.wifitrackerlib;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.StandardWifiEntry.wifiConfigToStandardWifiEntryKey;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTED;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_CONNECTING;
import static com.android.wifitrackerlib.WifiEntry.CONNECTED_STATE_DISCONNECTED;
import static com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_UNREACHABLE;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.GuardedBy;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Wi-Fi tracker that provides all Wi-Fi related data to the Wi-Fi picker page.
 *
 * These include
 * - The connected WifiEntry
 * - List of all visible WifiEntries
 * - Number of saved networks
 * - Number of saved subscriptions
 */
public class WifiPickerTracker extends BaseWifiTracker {

    private static final String TAG = "WifiPickerTracker";

    private final WifiPickerTrackerCallback mListener;

    // Lock object for data returned by the public API
    private final Object mLock = new Object();
    // List representing return value of the getWifiEntries() API
    @GuardedBy("mLock") private final List<WifiEntry> mWifiEntries = new ArrayList<>();
    // Reference to the WifiEntry representing the network that is currently connected to
    private WifiEntry mConnectedWifiEntry;
    // Cache containing visible StandardWifiEntries. Must be accessed only by the worker thread.
    private final Map<String, StandardWifiEntry> mStandardWifiEntryCache = new HashMap<>();

    private int mNumSavedNetworks = 0;

    /**
     * Constructor for WifiPickerTracker.
     *
     * @param lifecycle Lifecycle this is tied to for lifecycle callbacks.
     * @param context Context for registering broadcast receiver and for resource strings.
     * @param wifiManager Provides all Wi-Fi info.
     * @param connectivityManager Provides network info.
     * @param networkScoreManager Provides network scores for network badging.
     * @param mainHandler Handler for processing listener callbacks.
     * @param workerHandler Handler for processing all broadcasts and running the Scanner.
     * @param clock Clock used for evaluating the age of scans
     * @param maxScanAgeMillis Max age for tracked WifiEntries.
     * @param scanIntervalMillis Interval between initiating scans.
     * @param listener WifiTrackerCallback listening on changes to WifiPickerTracker data.
     */
    public WifiPickerTracker(@NonNull Lifecycle lifecycle, @NonNull Context context,
            @NonNull WifiManager wifiManager,
            @NonNull ConnectivityManager connectivityManager,
            @NonNull NetworkScoreManager networkScoreManager,
            @NonNull Handler mainHandler,
            @NonNull Handler workerHandler,
            @NonNull Clock clock,
            long maxScanAgeMillis,
            long scanIntervalMillis,
            @Nullable WifiPickerTrackerCallback listener) {
        super(lifecycle, context, wifiManager, connectivityManager, networkScoreManager,
                mainHandler, workerHandler, clock, maxScanAgeMillis, scanIntervalMillis, listener,
                TAG);
        mListener = listener;
    }

    /**
     * Returns the WifiEntry representing the current connection.
     */
    @AnyThread
    public @Nullable WifiEntry getConnectedWifiEntry() {
        return mConnectedWifiEntry;
    }

    /**
     * Returns a list of in-range WifiEntries.
     *
     * The currently connected entry is omitted and may be accessed through
     * {@link #getConnectedWifiEntry()}
     */
    @AnyThread
    public @NonNull List<WifiEntry> getWifiEntries() {
        synchronized (mLock) {
            return new ArrayList<>(mWifiEntries);
        }
    }

    /**
     * Returns the number of saved networks.
     */
    @AnyThread
    public int getNumSavedNetworks() {
        return mNumSavedNetworks;
    }

    /**
     * Returns the number of saved subscriptions.
     */
    @AnyThread
    public int getNumSavedSubscriptions() {
        //TODO(b/70983952): Use a cached value for this and update dynamically instead of calling
        //                  this expensive method every time.
        return mWifiManager.getPasspointConfigurations().size();
    }

    @WorkerThread
    @Override
    protected void handleOnStart() {
        mScanResultUpdater.update(mWifiManager.getScanResults());
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        final NetworkInfo networkInfo = mConnectivityManager.getActiveNetworkInfo();
        updateStandardWifiEntryConnectionInfo(wifiInfo, networkInfo);
        // Create a StandardWifiEntry for the current connection if there are no scan results yet.
        conditionallyCreateConnectedStandardWifiEntry(wifiInfo, networkInfo);
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleWifiStateChangedAction() {
        conditionallyUpdateScanResults(true /* lastScanSucceeded */);
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleScanResultsAvailableAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        conditionallyUpdateScanResults(
                intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleConfiguredNetworksChangedAction(@NonNull Intent intent) {
        // TODO(b/70983952): Handle Passpoint configurations here
        checkNotNull(intent, "Intent cannot be null!");

        final WifiConfiguration config =
                (WifiConfiguration) intent.getExtra(WifiManager.EXTRA_WIFI_CONFIGURATION);
        if (config != null) {
            updateStandardWifiEntryConfig(
                    config, (Integer) intent.getExtra(WifiManager.EXTRA_CHANGE_REASON));
        } else {
            updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
        }
        notifyOnNumSavedNetworksChanged();
        updateWifiEntries();
    }

    @WorkerThread
    @Override
    protected void handleNetworkStateChangedAction(@NonNull Intent intent) {
        checkNotNull(intent, "Intent cannot be null!");
        updateStandardWifiEntryConnectionInfo(mWifiManager.getConnectionInfo(),
                (NetworkInfo) intent.getExtra(WifiManager.EXTRA_NETWORK_INFO));
        updateWifiEntries();
    }

    /**
     * Update the list returned by getWifiEntries() with the current states of the entry caches.
     */
    @WorkerThread
    private void updateWifiEntries() {
        synchronized (mLock) {
            mWifiEntries.clear();
            mWifiEntries.addAll(mStandardWifiEntryCache.values().stream().filter(entry ->
                    entry.getConnectedState() == CONNECTED_STATE_DISCONNECTED).collect(toList()));
            // mWifiEntries.addAll(mPasspointWifiEntryCache);
            // mWifiEntries.addAll(mCarrierWifiEntryCache);
            // mWifiEntries.addAll(mSuggestionWifiEntryCache);
            mConnectedWifiEntry = mStandardWifiEntryCache.values().stream().filter(entry -> {
                final @WifiEntry.ConnectedState int connectedState = entry.getConnectedState();
                return connectedState == CONNECTED_STATE_CONNECTED
                        || connectedState == CONNECTED_STATE_CONNECTING;
            }).findAny().orElse(null /* other */);
            Collections.sort(mWifiEntries);
            if (isVerboseLoggingEnabled()) {
                Log.v(TAG, "Connected WifiEntry: " + mConnectedWifiEntry);
                Log.v(TAG, "Updated WifiEntries: " + Arrays.toString(mWifiEntries.toArray()));
            }
        }
        notifyOnWifiEntriesChanged();
    }

    /**
     * Updates or removes scan results for the corresponding StandardWifiEntries.
     * New entries will be created for scan results without an existing entry.
     * Unreachable entries will be removed.
     *
     * @param scanResults List of valid scan results to convey as StandardWifiEntries
     */
    @WorkerThread
    private void updateStandardWifiEntryScans(@NonNull List<ScanResult> scanResults) {
        checkNotNull(scanResults, "Scan Result list should not be null!");

        // Filter out scan results with unsupported capabilities
        final List<ScanResult> filteredScans = Utils.filterScanResultsByCapabilities(scanResults,
                mWifiManager.isWpa3SaeSupported(),
                mWifiManager.isWpa3SuiteBSupported(),
                mWifiManager.isEnhancedOpenSupported());

        // Group scans by StandardWifiEntry key
        final Map<String, List<ScanResult>> scanResultsByKey = filteredScans.stream()
                .filter(scanResult -> !TextUtils.isEmpty(scanResult.SSID))
                .collect(groupingBy(StandardWifiEntry::scanResultToStandardWifiEntryKey));

        // Iterate through current entries and update each entry's scan results
        mStandardWifiEntryCache.entrySet().removeIf(e -> {
            final String key = e.getKey();
            final StandardWifiEntry entry = e.getValue();
            // Update scan results if available, or set to null.
            entry.updateScanResultInfo(scanResultsByKey.remove(key));
            // Entry is now unreachable, remove it.
            return entry.getLevel() == WIFI_LEVEL_UNREACHABLE;
        });

        // Create new StandardWifiEntry objects for each leftover group of scan results.
        for (Map.Entry<String, List<ScanResult>> e: scanResultsByKey.entrySet()) {
            mStandardWifiEntryCache.put(e.getKey(),
                    new StandardWifiEntry(mMainHandler, e.getValue(), mWifiManager));
        }

        updateStandardWifiEntryConfigs(mWifiManager.getConfiguredNetworks());
    }

    /**
     * Conditionally updates the WifiEntry scan results based on the current wifi state and
     * whether the last scan succeeded or not.
     */
    @WorkerThread
    private void conditionallyUpdateScanResults(boolean lastScanSucceeded) {
        if (mWifiManager.getWifiState() == WifiManager.WIFI_STATE_DISABLED) {
            updateStandardWifiEntryScans(Collections.emptyList());
            return;
        }

        long scanAgeWindow = mMaxScanAgeMillis;
        if (lastScanSucceeded) {
            // Scan succeeded, cache new scans
            mScanResultUpdater.update(mWifiManager.getScanResults());
        } else {
            // Scan failed, increase scan age window to prevent WifiEntry list from
            // clearing prematurely.
            scanAgeWindow += mScanIntervalMillis;
        }
        updateStandardWifiEntryScans(mScanResultUpdater.getScanResults(scanAgeWindow));
    }

    /**
     * Updates a single WifiConfiguration for the corresponding StandardWifiEntry if it exists.
     *
     * @param config WifiConfiguration to update
     * @param changeReason WifiManager.CHANGE_REASON_ADDED, WifiManager.CHANGE_REASON_REMOVED, or
     *                     WifiManager.CHANGE_REASON_CONFIG_CHANGE
     */
    @WorkerThread
    private void updateStandardWifiEntryConfig(@NonNull WifiConfiguration config,
            int changeReason) {
        checkNotNull(config, "Config should not be null!");

        final String key = wifiConfigToStandardWifiEntryKey(config);
        final StandardWifiEntry entry = mStandardWifiEntryCache.get(key);

        if (entry != null) {
            if (changeReason == WifiManager.CHANGE_REASON_REMOVED) {
                mNumSavedNetworks--;
                entry.updateConfig(null);
            } else { // CHANGE_REASON_ADDED || CHANGE_REASON_CONFIG_CHANGE
                if (changeReason == WifiManager.CHANGE_REASON_ADDED) mNumSavedNetworks++;
                entry.updateConfig(config);
            }
        }
    }

    /**
     * Updates all saved WifiConfigurations for the corresponding StandardWifiEntries if they exist.
     *
     * @param configs List of saved WifiConfigurations
     */
    @WorkerThread
    private void updateStandardWifiEntryConfigs(@NonNull List<WifiConfiguration> configs) {
        checkNotNull(configs, "Config list should not be null!");
        mNumSavedNetworks = configs.size();

        // Group configs by StandardWifiEntry key
        final Map<String, WifiConfiguration> wifiConfigsByKey =
                configs.stream().collect(Collectors.toMap(
                        StandardWifiEntry::wifiConfigToStandardWifiEntryKey,
                        Function.identity()));

        // Iterate through current entries and update each entry's config
        mStandardWifiEntryCache.entrySet().forEach((entry) -> {
            final StandardWifiEntry wifiEntry = entry.getValue();
            final String key = wifiEntry.getKey();
            wifiEntry.updateConfig(wifiConfigsByKey.get(key));
        });
    }

    /**
     * Updates all StandardWifiEntries with the current connection info.
     * @param wifiInfo WifiInfo of the current connection
     * @param networkInfo NetworkInfo of the current connection
     */
    @WorkerThread
    private void updateStandardWifiEntryConnectionInfo(@Nullable WifiInfo wifiInfo,
            @Nullable NetworkInfo networkInfo) {
        for (StandardWifiEntry entry : mStandardWifiEntryCache.values()) {
            entry.updateConnectionInfo(wifiInfo, networkInfo);
        }
    }

    /**
     * Creates and caches a StandardWifiEntry representing the current connection using the current
     * WifiInfo and NetworkInfo if there are no scans results available for the network yet.
     * @param wifiInfo WifiInfo of the current connection
     * @param networkInfo NetworkInfo of the current connection
     */
    @WorkerThread
    private void conditionallyCreateConnectedStandardWifiEntry(@Nullable WifiInfo wifiInfo,
            @Nullable NetworkInfo networkInfo) {
        final int connectedNetId = wifiInfo.getNetworkId();
        mWifiManager.getConfiguredNetworks().stream()
                .filter(config ->
                    config.networkId == connectedNetId && !mStandardWifiEntryCache.containsKey(
                        wifiConfigToStandardWifiEntryKey(config)))
                .findAny().ifPresent(config -> {
                    final StandardWifiEntry connectedEntry =
                            new StandardWifiEntry(mMainHandler, config, mWifiManager);
                    connectedEntry.updateConnectionInfo(wifiInfo, networkInfo);
                    mStandardWifiEntryCache.put(connectedEntry.getKey(), connectedEntry);
                });
    }

    /**
     * Posts onWifiEntryChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnWifiEntriesChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onWifiEntriesChanged);
        }
    }

    /**
     * Posts onNumSavedNetworksChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnNumSavedNetworksChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onNumSavedNetworksChanged);
        }
    }

    /**
     * Posts onNumSavedSubscriptionsChanged callback on the main thread.
     */
    @WorkerThread
    private void notifyOnNumSavedSubscriptionsChanged() {
        if (mListener != null) {
            mMainHandler.post(mListener::onNumSavedSubscriptionsChanged);
        }
    }

    /**
     * Listener for changes to the list of visible WifiEntries as well as the number of saved
     * networks and subscriptions.
     *
     * These callbacks must be run on the MainThread.
     */
    public interface WifiPickerTrackerCallback extends BaseWifiTracker.BaseWifiTrackerCallback {
        /**
         * Called when there are changes to
         *      {@link #getConnectedWifiEntry()}
         *      {@link #getWifiEntries()}
         */
        @MainThread
        void onWifiEntriesChanged();

        /**
         * Called when there are changes to
         *      {@link #getNumSavedNetworks()}
         */
        @MainThread
        void onNumSavedNetworksChanged();

        /**
         * Called when there are changes to
         *      {@link #getNumSavedSubscriptions()}
         */
        @MainThread
        void onNumSavedSubscriptionsChanged();
    }
}
