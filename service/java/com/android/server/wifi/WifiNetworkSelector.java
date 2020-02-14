/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.net.wifi.WifiManager.WIFI_FEATURE_OWE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.NetworkKey;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.proto.nano.WifiMetricsProto;
import com.android.server.wifi.util.InformationElementUtil.BssLoad;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.wifi.resources.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class looks at all the connectivity scan results then
 * selects a network for the phone to connect or roam to.
 */
public class WifiNetworkSelector {
    private static final String TAG = "WifiNetworkSelector";

    private static final long INVALID_TIME_STAMP = Long.MIN_VALUE;

    /**
     * Minimum time gap between last successful network selection and a
     * new selection attempt.
     */
    @VisibleForTesting
    public static final int MINIMUM_NETWORK_SELECTION_INTERVAL_MS = 10 * 1000;

    /**
     * For this duration after user selected it, consider the current network as sufficient.
     *
     * This delays network selection during the time that connectivity service may be posting
     * a dialog about a no-internet network.
     */
    @VisibleForTesting
    public static final int LAST_USER_SELECTION_SUFFICIENT_MS = 30_000;

    /**
     * Time that it takes for the boost given to the most recently user-selected
     * network to decay to zero.
     *
     * In milliseconds.
     */
    @VisibleForTesting
    public static final int LAST_USER_SELECTION_DECAY_TO_ZERO_MS = 8 * 60 * 60 * 1000;

    /**
     * Connected score value used to decide whether a still-connected wifi should be treated
     * as unconnected when filtering scan results.
     */
    @VisibleForTesting
    public static final int WIFI_POOR_SCORE = ConnectedScore.WIFI_TRANSITION_SCORE - 10;

    /**
     * The identifier string of the CandidateScorer to use (in the absence of overrides).
     */
    public static final String PRESET_CANDIDATE_SCORER_NAME = "ThroughputScorer";

    /**
     * Experiment ID for the legacy scorer.
     */
    public static final int LEGACY_CANDIDATE_SCORER_EXP_ID = 0;

    private final Context mContext;
    private final WifiConfigManager mWifiConfigManager;
    private final Clock mClock;
    private final LocalLog mLocalLog;
    private final WifiMetrics mWifiMetrics;
    private long mLastNetworkSelectionTimeStamp = INVALID_TIME_STAMP;
    // Buffer of filtered scan results (Scan results considered by network selection) & associated
    // WifiConfiguration (if any).
    private final List<Pair<ScanDetail, WifiConfiguration>> mConnectableNetworks =
            new ArrayList<>();
    private List<ScanDetail> mFilteredNetworks = new ArrayList<>();
    private final WifiScoreCard mWifiScoreCard;
    private final ScoringParams mScoringParams;
    private final WifiNative mWifiNative;

    private final Map<String, WifiCandidates.CandidateScorer> mCandidateScorers = new ArrayMap<>();
    private boolean mIsEnhancedOpenSupportedInitialized = false;
    private boolean mIsEnhancedOpenSupported;
    private ThroughputPredictor mThroughputPredictor;
    private boolean mIsBluetoothConnected = false;
    private WifiChannelUtilization mWifiChannelUtilization;

    /**
     * WiFi Network Selector supports various categories of networks. Each category
     * has an nominator to choose the best WiFi network to connect to.
     * Wifi Network Selector iterates through the registered scorers in registration order
     * before making a final selection from among the candidates.
     */

    /**
     * Interface for WiFi Network Nominator
     *
     * A network nominator examines the scan results reports the
     * connectable candidates in its category for further consideration.
     */
    public interface NetworkNominator {
        /** Type of nominators */
        int NOMINATOR_ID_SAVED = 0;
        int NOMINATOR_ID_SUGGESTION = 1;
        int NOMINATOR_ID_PASSPOINT = 2;
        int NOMINATOR_ID_CARRIER = 3;
        int NOMINATOR_ID_SCORED = 4;

        @IntDef(prefix = { "NOMINATOR_ID_" }, value = {
                NOMINATOR_ID_SAVED,
                NOMINATOR_ID_SUGGESTION,
                NOMINATOR_ID_PASSPOINT,
                NOMINATOR_ID_CARRIER,
                NOMINATOR_ID_SCORED})
        @Retention(RetentionPolicy.SOURCE)
        public @interface NominatorId {}

        /**
         * Get the nominator type.
         */
        @NominatorId int getId();

        /**
         * Get the nominator name.
         */
        String getName();

        /**
         * Update the nominator.
         *
         * Certain nominators have to be updated with the new scan results. For example
         * the ScoredNetworkNominator needs to refresh its Score Cache.
         *
         * @param scanDetails    a list of scan details constructed from the scan results
         */
        void update(List<ScanDetail> scanDetails);

        /**
         * Evaluate all the networks from the scan results.
         *
         * @param scanDetails    a list of scan details constructed from the scan results
         * @param currentNetwork configuration of the current connected network
         *                       or null if disconnected
         * @param currentBssid   BSSID of the current connected network or null if
         *                       disconnected
         * @param connected      a flag to indicate if ClientModeImpl is in connected
         *                       state
         * @param untrustedNetworkAllowed a flag to indicate if untrusted networks like
         *                                ephemeral networks are allowed
         * @param onConnectableListener callback to record all of the connectable networks
         *
         */
        void nominateNetworks(List<ScanDetail> scanDetails,
                WifiConfiguration currentNetwork, String currentBssid,
                boolean connected, boolean untrustedNetworkAllowed,
                OnConnectableListener onConnectableListener);

        /**
         * Callback for recording connectable candidates
         */
        public interface OnConnectableListener {
            /**
             * Notes that an access point is an eligible connection candidate
             *
             * @param scanDetail describes the specific access point
             * @param config is the WifiConfiguration for the network
             */
            void onConnectable(ScanDetail scanDetail, WifiConfiguration config);
        }
    }

    private final List<NetworkNominator> mNominators = new ArrayList<>(3);

    // A helper to log debugging information in the local log buffer, which can
    // be retrieved in bugreport.
    private void localLog(String log) {
        mLocalLog.log(log);
    }

    /**
     * Check if current network has sufficient RSSI
     * @param wifiInfo info of currently connected network
     * @param scoringParams scoring parameter set including RSSI sufficiency check threshold
     * @return true if current link quality is sufficient, false otherwise.
     */
    public static boolean hasSufficientLinkQuality(WifiInfo wifiInfo, ScoringParams scoringParams) {
        int currentRssi = wifiInfo.getRssi();
        return  currentRssi >= scoringParams.getSufficientRssi(wifiInfo.getFrequency());
    }

    /**
     * Check if current network has active Tx or Rx traffic
     * @param wifiInfo info of currently connected network
     * @param scoringParams scoring parameter set including active traffic check threshold
     * @return true if it has active Tx or Rx traffic, false otherwise.
     */
    public static boolean hasActiveStream(WifiInfo wifiInfo, ScoringParams scoringParams) {
        return (wifiInfo.getSuccessfulTxPacketsPerSecond()
                        > scoringParams.getActiveTrafficPacketsPerSecond())
                || (wifiInfo.getSuccessfulRxPacketsPerSecond()
                        > scoringParams.getActiveTrafficPacketsPerSecond());
    }

    /**
     * Check if one of following conditions is met to avoid a new network selection
     * 1) current network is in OSU process
     * 2) current network has internet access, sufficient link quality and active traffic
     */
    private boolean isCurrentNetworkSufficient(WifiInfo wifiInfo) {
        // Currently connected?
        if (wifiInfo.getSupplicantState() != SupplicantState.COMPLETED) {
            localLog("No current connected network.");
            return false;
        } else {
            localLog("Current connected network: " + wifiInfo.getSSID()
                    + " , ID: " + wifiInfo.getNetworkId());
        }

        WifiConfiguration network =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        if (network == null) {
            localLog("Current network was removed.");
            return false;
        }

        // Set OSU (Online Sign Up) network for Passpoint Release 2 to sufficient
        // so that network select selection is skipped and OSU process can complete.
        if (network.osu) {
            return true;
        }

        // Network with no internet access reports is not sufficient
        if (network.numNoInternetAccessReports > 0 && !network.noInternetAccessExpected) {
            localLog("Current network has [" + network.numNoInternetAccessReports
                    + "] no-internet access reports.");
            return false;
        }

        if ((hasSufficientLinkQuality(wifiInfo, mScoringParams))
                && hasActiveStream(wifiInfo, mScoringParams)) {
            localLog("Stay on current network due to sufficient link quality and ongoing traffic");
            return true;
        }

        return false;
    }

    private boolean isNetworkSelectionNeeded(List<ScanDetail> scanDetails, WifiInfo wifiInfo,
                        boolean connected, boolean disconnected) {
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan results. Skip network selection.");
            return false;
        }

        if (connected) {
            // Is roaming allowed?
            if (!mContext.getResources().getBoolean(
                    R.bool.config_wifi_framework_enable_associated_network_selection)) {
                localLog("Switching networks in connected state is not allowed."
                        + " Skip network selection.");
                return false;
            }

            // Has it been at least the minimum interval since last network selection?
            if (mLastNetworkSelectionTimeStamp != INVALID_TIME_STAMP) {
                long gap = mClock.getElapsedSinceBootMillis()
                            - mLastNetworkSelectionTimeStamp;
                if (gap < MINIMUM_NETWORK_SELECTION_INTERVAL_MS) {
                    localLog("Too short since last network selection: " + gap + " ms."
                            + " Skip network selection.");
                    return false;
                }
            }
            // Please note other scans (e.g., location scan or app scan) may also trigger network
            // selection and these scans may or may not run sufficiency check.
            // So it is better to run sufficiency check here before network selection.
            if (isCurrentNetworkSufficient(wifiInfo)) {
                localLog("Current connected network already sufficient. Skip network selection.");
                return false;
            } else {
                localLog("Current connected network is not sufficient.");
                return true;
            }
        } else if (disconnected) {
            return true;
        } else {
            // No network selection if ClientModeImpl is in a state other than
            // CONNECTED or DISCONNECTED.
            localLog("ClientModeImpl is in neither CONNECTED nor DISCONNECTED state."
                    + " Skip network selection.");
            return false;
        }
    }

    /**
     * Format the given ScanResult as a scan ID for logging.
     */
    public static String toScanId(@Nullable ScanResult scanResult) {
        return scanResult == null ? "NULL"
                                  : String.format("%s:%s", scanResult.SSID, scanResult.BSSID);
    }

    /**
     * Format the given WifiConfiguration as a SSID:netId string
     */
    public static String toNetworkString(WifiConfiguration network) {
        if (network == null) {
            return null;
        }

        return (network.SSID + ":" + network.networkId);
    }

    /**
     * Compares ScanResult level against the minimum threshold for its band, returns true if lower
     */
    public boolean isSignalTooWeak(ScanResult scanResult) {
        return (scanResult.level < mScoringParams.getEntryRssi(scanResult.frequency));
    }

    private List<ScanDetail> filterScanResults(List<ScanDetail> scanDetails,
                Set<String> bssidBlacklist, boolean isConnected, String currentBssid) {
        ArrayList<NetworkKey> unscoredNetworks = new ArrayList<>();
        List<ScanDetail> validScanDetails = new ArrayList<>();
        StringBuffer noValidSsid = new StringBuffer();
        StringBuffer blacklistedBssid = new StringBuffer();
        StringBuffer lowRssi = new StringBuffer();
        StringBuffer mboAssociationDisallowedBssid = new StringBuffer();
        boolean scanResultsHaveCurrentBssid = false;

        for (ScanDetail scanDetail : scanDetails) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (TextUtils.isEmpty(scanResult.SSID)) {
                noValidSsid.append(scanResult.BSSID).append(" / ");
                continue;
            }

            // Check if the scan results contain the currently connected BSSID
            if (scanResult.BSSID.equals(currentBssid)) {
                scanResultsHaveCurrentBssid = true;
            }

            final String scanId = toScanId(scanResult);

            if (bssidBlacklist.contains(scanResult.BSSID)) {
                blacklistedBssid.append(scanId).append(" / ");
                continue;
            }

            // Skip network with too weak signals.
            if (isSignalTooWeak(scanResult)) {
                lowRssi.append(scanId).append("(")
                    .append(scanResult.is24GHz() ? "2.4GHz" : "5GHz")
                    .append(")").append(scanResult.level).append(" / ");
                continue;
            }

            // Skip BSS which is not accepting new connections.
            NetworkDetail networkDetail = scanDetail.getNetworkDetail();
            if (networkDetail != null) {
                if (networkDetail.getMboAssociationDisallowedReasonCode()
                        != MboOceConstants.MBO_OCE_ATTRIBUTE_NOT_PRESENT) {
                    mboAssociationDisallowedBssid.append(scanId).append("(")
                        .append(networkDetail.getMboAssociationDisallowedReasonCode())
                        .append(")").append(" / ");
                    continue;
                }
            }

            validScanDetails.add(scanDetail);
        }

        // WNS listens to all single scan results. Some scan requests may not include
        // the channel of the currently connected network, so the currently connected
        // network won't show up in the scan results. We don't act on these scan results
        // to avoid aggressive network switching which might trigger disconnection.
        if (isConnected && !scanResultsHaveCurrentBssid) {
            localLog("Current connected BSSID " + currentBssid + " is not in the scan results."
                    + " Skip network selection.");
            validScanDetails.clear();
            return validScanDetails;
        }

        if (noValidSsid.length() != 0) {
            localLog("Networks filtered out due to invalid SSID: " + noValidSsid);
        }

        if (blacklistedBssid.length() != 0) {
            localLog("Networks filtered out due to blacklist: " + blacklistedBssid);
        }

        if (lowRssi.length() != 0) {
            localLog("Networks filtered out due to low signal strength: " + lowRssi);
        }

        if (mboAssociationDisallowedBssid.length() != 0) {
            localLog("Networks filtered out due to mbo association disallowed indication: "
                    + mboAssociationDisallowedBssid);
        }

        return validScanDetails;
    }

    private boolean isEnhancedOpenSupported() {
        if (mIsEnhancedOpenSupportedInitialized) {
            return mIsEnhancedOpenSupported;
        }

        mIsEnhancedOpenSupportedInitialized = true;
        mIsEnhancedOpenSupported = (mWifiNative.getSupportedFeatureSet(
                mWifiNative.getClientInterfaceName()) & WIFI_FEATURE_OWE) != 0;
        return mIsEnhancedOpenSupported;
    }

    /**
     * This returns a list of ScanDetails that were filtered in the process of network selection.
     * The list is further filtered for only open unsaved networks.
     *
     * @return the list of ScanDetails for open unsaved networks that do not have invalid SSIDS,
     * blacklisted BSSIDS, or low signal strength. This will return an empty list when there are
     * no open unsaved networks, or when network selection has not been run.
     */
    public List<ScanDetail> getFilteredScanDetailsForOpenUnsavedNetworks() {
        List<ScanDetail> openUnsavedNetworks = new ArrayList<>();
        boolean enhancedOpenSupported = isEnhancedOpenSupported();
        for (ScanDetail scanDetail : mFilteredNetworks) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (!ScanResultUtil.isScanResultForOpenNetwork(scanResult)) {
                continue;
            }

            // Filter out Enhanced Open networks on devices that do not support it
            if (ScanResultUtil.isScanResultForOweNetwork(scanResult)
                    && !enhancedOpenSupported) {
                continue;
            }

            // Skip saved networks
            if (mWifiConfigManager.getConfiguredNetworkForScanDetailAndCache(scanDetail) != null) {
                continue;
            }

            openUnsavedNetworks.add(scanDetail);
        }
        return openUnsavedNetworks;
    }

    /**
     * @return the list of ScanDetails scored as potential candidates by the last run of
     * selectNetwork, this will be empty if Network selector determined no selection was
     * needed on last run. This includes scan details of sufficient signal strength, and
     * had an associated WifiConfiguration.
     */
    public List<Pair<ScanDetail, WifiConfiguration>> getConnectableScanDetails() {
        return mConnectableNetworks;
    }

    /**
     * This API is called when user explicitly selects a network. Currently, it is used in following
     * cases:
     * (1) User explicitly chooses to connect to a saved network.
     * (2) User saves a network after adding a new network.
     * (3) User saves a network after modifying a saved network.
     * Following actions will be triggered:
     * 1. If this network is disabled, we need re-enable it again.
     * 2. This network is favored over all the other networks visible in latest network
     *    selection procedure.
     *
     * @param netId  ID for the network chosen by the user
     * @return true -- There is change made to connection choice of any saved network.
     *         false -- There is no change made to connection choice of any saved network.
     */
    public boolean setUserConnectChoice(int netId) {
        localLog("userSelectNetwork: network ID=" + netId);
        WifiConfiguration selected = mWifiConfigManager.getConfiguredNetwork(netId);

        if (selected == null || selected.SSID == null) {
            localLog("userSelectNetwork: Invalid configuration with nid=" + netId);
            return false;
        }

        // Enable the network if it is disabled.
        if (!selected.getNetworkSelectionStatus().isNetworkEnabled()) {
            mWifiConfigManager.updateNetworkSelectionStatus(netId,
                    WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE);
        }
        return setLegacyUserConnectChoice(selected);
    }

    /**
     * This maintains the legacy user connect choice state in the config store
     */
    private boolean setLegacyUserConnectChoice(@NonNull final WifiConfiguration selected) {
        boolean change = false;
        String key = selected.getKey();
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();

        for (WifiConfiguration network : configuredNetworks) {
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (network.networkId == selected.networkId) {
                if (status.getConnectChoice() != null) {
                    localLog("Remove user selection preference of " + status.getConnectChoice()
                            + " from " + network.SSID + " : " + network.networkId);
                    mWifiConfigManager.clearNetworkConnectChoice(network.networkId);
                    change = true;
                }
                continue;
            }

            if (status.getSeenInLastQualifiedNetworkSelection()
                        && !key.equals(status.getConnectChoice())) {
                localLog("Add key: " + key + " to "
                        + toNetworkString(network));
                mWifiConfigManager.setNetworkConnectChoice(network.networkId, key);
                change = true;
            }
        }

        return change;
    }


    /**
     * Iterate thru the list of configured networks (includes all saved network configurations +
     * any ephemeral network configurations created for passpoint networks, suggestions, carrier
     * networks, etc) and do the following:
     * a) Try to re-enable any temporarily enabled networks (if the blacklist duration has expired).
     * b) Clear the {@link WifiConfiguration.NetworkSelectionStatus#getCandidate()} field for all
     * of them to identify networks that are present in the current scan result.
     * c) Log any disabled networks.
     */
    private void updateConfiguredNetworks() {
        List<WifiConfiguration> configuredNetworks = mWifiConfigManager.getConfiguredNetworks();
        if (configuredNetworks.size() == 0) {
            localLog("No configured networks.");
            return;
        }

        StringBuffer sbuf = new StringBuffer();
        for (WifiConfiguration network : configuredNetworks) {
            // If a configuration is temporarily disabled, re-enable it before trying
            // to connect to it.
            mWifiConfigManager.tryEnableNetwork(network.networkId);
            // Clear the cached candidate, score and seen.
            mWifiConfigManager.clearNetworkCandidateScanResult(network.networkId);

            // Log disabled network.
            WifiConfiguration.NetworkSelectionStatus status = network.getNetworkSelectionStatus();
            if (!status.isNetworkEnabled()) {
                sbuf.append("  ").append(toNetworkString(network)).append(" ");
                for (int index = WifiConfiguration.NetworkSelectionStatus
                        .NETWORK_SELECTION_DISABLED_STARTING_INDEX;
                        index < WifiConfiguration.NetworkSelectionStatus
                                .NETWORK_SELECTION_DISABLED_MAX;
                        index++) {
                    int count = status.getDisableReasonCounter(index);
                    // Here we log the reason as long as its count is greater than zero. The
                    // network may not be disabled because of this particular reason. Logging
                    // this information anyway to help understand what happened to the network.
                    if (count > 0) {
                        sbuf.append("reason=")
                                .append(WifiConfiguration.NetworkSelectionStatus
                                        .getNetworkDisableReasonString(index))
                                .append(", count=").append(count).append("; ");
                    }
                }
                sbuf.append("\n");
            }
        }

        if (sbuf.length() > 0) {
            localLog("Disabled configured networks:");
            localLog(sbuf.toString());
        }
    }

    /**
     * Overrides the {@code candidate} chosen by the {@link #mNominators} with the user chosen
     * {@link WifiConfiguration} if one exists.
     *
     * @return the user chosen {@link WifiConfiguration} if one exists, {@code candidate} otherwise
     */
    private WifiConfiguration overrideCandidateWithUserConnectChoice(
            @NonNull WifiConfiguration candidate) {
        WifiConfiguration tempConfig = Preconditions.checkNotNull(candidate);
        WifiConfiguration originalCandidate = candidate;
        ScanResult scanResultCandidate = candidate.getNetworkSelectionStatus().getCandidate();

        while (tempConfig.getNetworkSelectionStatus().getConnectChoice() != null) {
            String key = tempConfig.getNetworkSelectionStatus().getConnectChoice();
            tempConfig = mWifiConfigManager.getConfiguredNetwork(key);

            if (tempConfig != null) {
                WifiConfiguration.NetworkSelectionStatus tempStatus =
                        tempConfig.getNetworkSelectionStatus();
                if (tempStatus.getCandidate() != null && tempStatus.isNetworkEnabled()) {
                    scanResultCandidate = tempStatus.getCandidate();
                    candidate = tempConfig;
                }
            } else {
                localLog("Connect choice: " + key + " has no corresponding saved config.");
                break;
            }
        }

        if (candidate != originalCandidate) {
            localLog("After user selection adjustment, the final candidate is:"
                    + WifiNetworkSelector.toNetworkString(candidate) + " : "
                    + scanResultCandidate.BSSID);
            mWifiMetrics.setNominatorForNetwork(candidate.networkId,
                    WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED_USER_CONNECT_CHOICE);
        }
        return candidate;
    }

    /**
     * Select the best network from the ones in range. Scan detail cache is also updated here.
     *
     * @param scanDetails    List of ScanDetail for all the APs in range
     * @param bssidBlacklist Blacklisted BSSIDs
     * @param wifiInfo       Currently connected network
     * @param connected      True if the device is connected
     * @param disconnected   True if the device is disconnected
     * @param untrustedNetworkAllowed True if untrusted networks are allowed for connection
     * @return Configuration of the selected network, or Null if nothing
     */
    @Nullable
    public WifiConfiguration selectNetwork(List<ScanDetail> scanDetails,
            Set<String> bssidBlacklist, WifiInfo wifiInfo,
            boolean connected, boolean disconnected, boolean untrustedNetworkAllowed) {
        mFilteredNetworks.clear();
        mConnectableNetworks.clear();
        if (scanDetails.size() == 0) {
            localLog("Empty connectivity scan result");
            return null;
        }

        WifiConfiguration currentNetwork =
                mWifiConfigManager.getConfiguredNetwork(wifiInfo.getNetworkId());

        // Always get the current BSSID from WifiInfo in case that firmware initiated
        // roaming happened.
        String currentBssid = wifiInfo.getBSSID();

        // Shall we start network selection at all?
        if (!isNetworkSelectionNeeded(scanDetails, wifiInfo, connected, disconnected)) {
            // If network selection is skipped, update scan detail cache before exit.
            // Otherwise, scan detail cache will be updated in each nominator.
            updateScanDetailCache(scanDetails);
            return null;
        }

        // Update all configured networks before initiating network selection.
        updateConfiguredNetworks();

        // Update the registered network nominators.
        for (NetworkNominator registeredNominator : mNominators) {
            registeredNominator.update(scanDetails);
        }

        // Filter out unwanted networks.
        mFilteredNetworks = filterScanResults(scanDetails, bssidBlacklist,
                connected && wifiInfo.getScore() >= WIFI_POOR_SCORE, currentBssid);
        if (mFilteredNetworks.size() == 0) {
            // If network selection is skipped, update scan detail cache before exit.
            // Otherwise, scan detail cache will be updated in each nominator.
            updateScanDetailCache(scanDetails);
            return null;
        }

        // Determine the weight for the last user selection
        final int lastUserSelectedNetworkId = mWifiConfigManager.getLastSelectedNetwork();
        final double lastSelectionWeight = calculateLastSelectionWeight();

        WifiCandidates wifiCandidates = new WifiCandidates(mWifiScoreCard, mContext);
        if (currentNetwork != null) {
            wifiCandidates.setCurrent(currentNetwork.networkId, currentBssid);
        }
        for (NetworkNominator registeredNominator : mNominators) {
            localLog("About to run " + registeredNominator.getName() + " :");
            registeredNominator.nominateNetworks(
                    new ArrayList<>(mFilteredNetworks), currentNetwork, currentBssid, connected,
                    untrustedNetworkAllowed,
                    (scanDetail, config) -> {
                        if (config != null) {
                            mConnectableNetworks.add(Pair.create(scanDetail, config));
                            wifiCandidates.add(scanDetail, config,
                                    registeredNominator.getId(),
                                    0,
                                    (config.networkId == lastUserSelectedNetworkId)
                                            ? lastSelectionWeight : 0.0,
                                    WifiConfiguration.isMetered(config, wifiInfo),
                                    predictThroughput(scanDetail));
                            mWifiMetrics.setNominatorForNetwork(config.networkId,
                                    toProtoNominatorId(registeredNominator.getId()));
                        }
                    });
        }

        if (mConnectableNetworks.size() != wifiCandidates.size()) {
            localLog("Connectable: " + mConnectableNetworks.size()
                    + " Candidates: " + wifiCandidates.size());
        }

        // Update the NetworkSelectionStatus in the configs for the current candidates
        // This is needed for the legacy user connect choice, at least
        Collection<Collection<WifiCandidates.Candidate>> groupedCandidates =
                wifiCandidates.getGroupedCandidates();
        for (Collection<WifiCandidates.Candidate> group: groupedCandidates) {
            WifiCandidates.Candidate best = null;
            for (WifiCandidates.Candidate candidate: group) {
                // Of all the candidates with the same networkId, choose the
                // one with the smallest nominatorId, and break ties by
                // picking the one with the highest score.
                if (best == null
                        || candidate.getNominatorId() < best.getNominatorId()
                        || (candidate.getNominatorId() == best.getNominatorId()
                            && candidate.getNominatorScore() > best.getNominatorScore())) {
                    best = candidate;
                }
            }
            if (best != null) {
                ScanDetail scanDetail = best.getScanDetail();
                if (scanDetail != null) {
                    mWifiConfigManager.setNetworkCandidateScanResult(best.getNetworkConfigId(),
                            scanDetail.getScanResult(), best.getNominatorScore());
                }
            }
        }

        ArrayMap<Integer, Integer> experimentNetworkSelections = new ArrayMap<>(); // for metrics

        int selectedNetworkId = WifiConfiguration.INVALID_NETWORK_ID;

        // Run all the CandidateScorers
        boolean legacyOverrideWanted = true;
        final WifiCandidates.CandidateScorer activeScorer = getActiveCandidateScorer();
        for (WifiCandidates.CandidateScorer candidateScorer : mCandidateScorers.values()) {
            WifiCandidates.ScoredCandidate choice;
            try {
                choice = wifiCandidates.choose(candidateScorer);
            } catch (RuntimeException e) {
                Log.wtf(TAG, "Exception running a CandidateScorer", e);
                continue;
            }
            int networkId = choice.candidateKey == null
                    ? WifiConfiguration.INVALID_NETWORK_ID
                    : choice.candidateKey.networkId;
            String chooses = " would choose ";
            if (candidateScorer == activeScorer) {
                chooses = " chooses ";
                legacyOverrideWanted = choice.userConnectChoiceOverride;
                selectedNetworkId = networkId;
                updateChosenPasspointNetwork(choice);
            }
            String id = candidateScorer.getIdentifier();
            int expid = experimentIdFromIdentifier(id);
            localLog(id + chooses + networkId
                    + " score " + choice.value + "+/-" + choice.err
                    + " expid " + expid);
            experimentNetworkSelections.put(expid, networkId);
        }

        // Update metrics about differences in the selections made by various methods
        final int activeExperimentId = experimentIdFromIdentifier(activeScorer.getIdentifier());
        for (Map.Entry<Integer, Integer> entry :
                experimentNetworkSelections.entrySet()) {
            int experimentId = entry.getKey();
            if (experimentId == activeExperimentId) continue;
            int thisSelectedNetworkId = entry.getValue();
            mWifiMetrics.logNetworkSelectionDecision(experimentId, activeExperimentId,
                    selectedNetworkId == thisSelectedNetworkId,
                    groupedCandidates.size());
        }

        // Get a fresh copy of WifiConfiguration reflecting any scan result updates
        WifiConfiguration selectedNetwork =
                mWifiConfigManager.getConfiguredNetwork(selectedNetworkId);
        // TODO (b/136675430): the legacyOverrideWanted check seems unnecessary
        if (selectedNetwork != null && legacyOverrideWanted) {
            selectedNetwork = overrideCandidateWithUserConnectChoice(selectedNetwork);
            mLastNetworkSelectionTimeStamp = mClock.getElapsedSinceBootMillis();
        }
        return selectedNetwork;
    }

    private void updateChosenPasspointNetwork(WifiCandidates.ScoredCandidate choice) {
        if (choice.candidateKey == null) {
            return;
        }
        WifiConfiguration config =
                mWifiConfigManager.getConfiguredNetwork(choice.candidateKey.networkId);
        if (config.isPasspoint()) {
            config.SSID = choice.candidateKey.matchInfo.networkSsid;
            mWifiConfigManager.addOrUpdateNetwork(config, config.creatorUid, config.creatorName);
        }
    }

    private void updateScanDetailCache(List<ScanDetail> scanDetails) {
        for (ScanDetail scanDetail : scanDetails) {
            mWifiConfigManager.updateScanDetailCacheFromScanDetail(scanDetail);
        }
    }

    private static int toProtoNominatorId(@NetworkNominator.NominatorId int nominatorId) {
        switch (nominatorId) {
            case NetworkNominator.NOMINATOR_ID_SAVED:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_SAVED;
            case NetworkNominator.NOMINATOR_ID_SUGGESTION:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_SUGGESTION;
            case NetworkNominator.NOMINATOR_ID_PASSPOINT:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_PASSPOINT;
            case NetworkNominator.NOMINATOR_ID_CARRIER:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_CARRIER;
            case NetworkNominator.NOMINATOR_ID_SCORED:
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_EXTERNAL_SCORED;
            default:
                Log.e(TAG, "UnrecognizedNominatorId" + nominatorId);
                return WifiMetricsProto.ConnectionEvent.NOMINATOR_UNKNOWN;
        }
    }

    private double calculateLastSelectionWeight() {
        final int lastUserSelectedNetworkId = mWifiConfigManager.getLastSelectedNetwork();
        double lastSelectionWeight = 0.0;
        if (lastUserSelectedNetworkId != WifiConfiguration.INVALID_NETWORK_ID) {
            double timeDifference = mClock.getElapsedSinceBootMillis()
                    - mWifiConfigManager.getLastSelectedTimeStamp();
            double unclipped = 1.0 - (timeDifference / LAST_USER_SELECTION_DECAY_TO_ZERO_MS);
            lastSelectionWeight = Math.min(Math.max(unclipped, 0.0), 1.0);
        }
        return lastSelectionWeight;
    }

    private WifiCandidates.CandidateScorer getActiveCandidateScorer() {
        WifiCandidates.CandidateScorer ans = mCandidateScorers.get(PRESET_CANDIDATE_SCORER_NAME);
        int overrideExperimentId = mScoringParams.getExperimentIdentifier();
        if (overrideExperimentId >= MIN_SCORER_EXP_ID) {
            for (WifiCandidates.CandidateScorer candidateScorer : mCandidateScorers.values()) {
                int expId = experimentIdFromIdentifier(candidateScorer.getIdentifier());
                if (expId == overrideExperimentId) {
                    ans = candidateScorer;
                    break;
                }
            }
        }
        if (ans == null && PRESET_CANDIDATE_SCORER_NAME != null) {
            Log.wtf(TAG, PRESET_CANDIDATE_SCORER_NAME + " is not registered!");
        }
        mWifiMetrics.setNetworkSelectorExperimentId(ans == null
                ? LEGACY_CANDIDATE_SCORER_EXP_ID
                : experimentIdFromIdentifier(ans.getIdentifier()));
        return ans;
    }

    private int predictThroughput(@NonNull ScanDetail scanDetail) {
        if (scanDetail.getScanResult() == null || scanDetail.getNetworkDetail() == null) {
            return 0;
        }
        int channelUtilizationLinkLayerStats = BssLoad.INVALID;
        if (mWifiChannelUtilization != null) {
            channelUtilizationLinkLayerStats =
                    mWifiChannelUtilization.getUtilizationRatio(
                            scanDetail.getScanResult().frequency);
        }
        return mThroughputPredictor.predictThroughput(
                mWifiNative.getDeviceWiphyCapabilities(mWifiNative.getClientInterfaceName()),
                scanDetail.getScanResult().getWifiStandard(),
                scanDetail.getScanResult().channelWidth,
                scanDetail.getScanResult().level,
                scanDetail.getScanResult().frequency,
                scanDetail.getNetworkDetail().getMaxNumberSpatialStreams(),
                scanDetail.getNetworkDetail().getChannelUtilization(),
                channelUtilizationLinkLayerStats,
                mIsBluetoothConnected);
    }

    /**
     * Register a network nominator
     *
     * @param nominator the network nominator to be registered
     *
     */
    public void registerNetworkNominator(@NonNull NetworkNominator nominator) {
        mNominators.add(Preconditions.checkNotNull(nominator));
    }

    /**
     * Register a candidate scorer.
     *
     * Replaces any existing scorer having the same identifier.
     */
    public void registerCandidateScorer(@NonNull WifiCandidates.CandidateScorer candidateScorer) {
        String name = Preconditions.checkNotNull(candidateScorer).getIdentifier();
        if (name != null) {
            mCandidateScorers.put(name, candidateScorer);
        }
    }

    /**
     * Unregister a candidate scorer.
     */
    public void unregisterCandidateScorer(@NonNull WifiCandidates.CandidateScorer candidateScorer) {
        String name = Preconditions.checkNotNull(candidateScorer).getIdentifier();
        if (name != null) {
            mCandidateScorers.remove(name);
        }
    }

    /**
     * Derives a numeric experiment identifier from a CandidateScorer's identifier.
     *
     * @returns a positive number that starts with the decimal digits ID_PREFIX
     */
    public static int experimentIdFromIdentifier(String id) {
        final int digits = (int) (((long) id.hashCode()) & Integer.MAX_VALUE) % ID_SUFFIX_MOD;
        return ID_PREFIX * ID_SUFFIX_MOD + digits;
    }
    private static final int ID_SUFFIX_MOD = 1_000_000;
    private static final int ID_PREFIX = 42;
    private static final int MIN_SCORER_EXP_ID = ID_PREFIX * ID_SUFFIX_MOD;

    /**
     * Set Wifi channel utilization calculated from link layer stats
     */
    public void setWifiChannelUtilization(WifiChannelUtilization wifiChannelUtilization) {
        mWifiChannelUtilization = wifiChannelUtilization;
    }

    /**
     * Set whether bluetooth is in the connected state
     */
    public void setBluetoothConnected(boolean isBlueToothConnected) {
        mIsBluetoothConnected = isBlueToothConnected;
    }

    WifiNetworkSelector(Context context, WifiScoreCard wifiScoreCard, ScoringParams scoringParams,
            WifiConfigManager configManager, Clock clock, LocalLog localLog,
            WifiMetrics wifiMetrics, WifiNative wifiNative,
            ThroughputPredictor throughputPredictor) {
        mContext = context;
        mWifiConfigManager = configManager;
        mClock = clock;
        mWifiScoreCard = wifiScoreCard;
        mScoringParams = scoringParams;
        mLocalLog = localLog;
        mWifiMetrics = wifiMetrics;
        mWifiNative = wifiNative;
        mThroughputPredictor = throughputPredictor;
    }
}
