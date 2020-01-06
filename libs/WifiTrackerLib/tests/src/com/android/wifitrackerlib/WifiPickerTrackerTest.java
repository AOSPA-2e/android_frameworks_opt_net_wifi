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

import static com.android.wifitrackerlib.TestUtils.buildScanResult;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
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
import android.os.test.TestLooper;

import androidx.lifecycle.Lifecycle;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WifiPickerTrackerTest {

    private static final long START_MILLIS = 123_456_789;

    private static final long MAX_SCAN_AGE_MILLIS = 15_000;
    private static final long SCAN_INTERVAL_MILLIS = 10_000;

    @Mock
    private Lifecycle mMockLifecycle;
    @Mock
    private Context mMockContext;
    @Mock
    private WifiManager mMockWifiManager;
    @Mock
    private ConnectivityManager mMockConnectivityManager;
    @Mock
    private NetworkScoreManager mMockNetworkScoreManager;
    @Mock
    private Clock mMockClock;
    @Mock
    private WifiPickerTracker.WifiPickerTrackerCallback mMockCallback;
    @Mock
    private WifiInfo mMockWifiInfo;
    @Mock
    private NetworkInfo mMockNetworkInfo;

    private TestLooper mTestLooper;

    private final ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor =
            ArgumentCaptor.forClass(BroadcastReceiver.class);

    private WifiPickerTracker createTestWifiPickerTracker() {
        final Handler testHandler = new Handler(mTestLooper.getLooper());

        return new WifiPickerTracker(mMockLifecycle, mMockContext,
                mMockWifiManager,
                mMockConnectivityManager,
                mMockNetworkScoreManager,
                testHandler,
                testHandler,
                mMockClock,
                MAX_SCAN_AGE_MILLIS,
                SCAN_INTERVAL_MILLIS,
                mMockCallback);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTestLooper = new TestLooper();

        when(mMockWifiManager.getScanResults()).thenReturn(new ArrayList<>());
        when(mMockWifiManager.getConnectionInfo()).thenReturn(mMockWifiInfo);
        when(mMockConnectivityManager.getActiveNetworkInfo()).thenReturn(mMockNetworkInfo);
        when(mMockClock.millis()).thenReturn(START_MILLIS);
        when(mMockWifiInfo.getNetworkId()).thenReturn(WifiConfiguration.INVALID_NETWORK_ID);
        when(mMockWifiInfo.getRssi()).thenReturn(WifiInfo.INVALID_RSSI);
        when(mMockNetworkInfo.getDetailedState()).thenReturn(
                NetworkInfo.DetailedState.DISCONNECTED);
    }

    /**
     * Tests that receiving a wifi state change broadcast updates getWifiState().
     */
    @Test
    public void testWifiStateChangeBroadcast_updatesWifiState() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        // Set the wifi state to disabled
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_DISABLED);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));

        assertThat(wifiPickerTracker.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_DISABLED);

        // Change the wifi state to enabled
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_ENABLED);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));

        assertThat(wifiPickerTracker.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_ENABLED);

    }

    /**
     * Tests that receiving a wifi state change broadcast notifies the listener.
     */
    @Test
    public void testWifiStateChangeBroadcast_notifiesListener() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION));
        mTestLooper.dispatchAll();

        verify(mMockCallback, atLeastOnce()).onWifiStateChanged();
    }

    /**
     * Tests that a CONFIGURED_NETWORKS_CHANGED broadcast notifies the listener for
     * numSavedNetworksChanged.
     */
    @Test
    public void testConfiguredNetworksChanged_notifiesListener() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION));
        mTestLooper.dispatchAll();

        verify(mMockCallback, atLeastOnce()).onNumSavedNetworksChanged();
    }

    /**
     * Tests that the wifi state is set correctly after onStart, even if no broadcast was received.
     */
    @Test
    public void testOnStart_setsWifiState() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();

        // Set the wifi state to disabled
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_DISABLED);
        wifiPickerTracker.onStart();
        mTestLooper.dispatchAll();

        assertThat(wifiPickerTracker.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_DISABLED);

        // Change the wifi state to enabled
        wifiPickerTracker.onStop();
        when(mMockWifiManager.getWifiState()).thenReturn(WifiManager.WIFI_STATE_ENABLED);
        wifiPickerTracker.onStart();
        mTestLooper.dispatchAll();

        assertThat(wifiPickerTracker.getWifiState()).isEqualTo(WifiManager.WIFI_STATE_ENABLED);
    }

    /**
     * Tests that receiving a scan results available broadcast notifies the listener.
     */
    @Test
    public void testScanResultsAvailableAction_notifiesListener() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        mTestLooper.dispatchAll();

        verify(mMockCallback, atLeastOnce()).onWifiEntriesChanged();
    }

    /**
     * Tests that an empty list of WifiEntries is returned if no scans are available.
     */
    @Test
    public void testGetWifiEntries_noScans_emptyList() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        when(mMockWifiManager.getScanResults()).thenReturn(new ArrayList<>());

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        assertThat(wifiPickerTracker.getWifiEntries()).isEmpty();
    }


    /**
     * Tests that a StandardWifiEntry is returned by getWifiEntries() for each non-null, non-empty
     * SSID/Security pair in the tracked scan results.
     */
    @Test
    public void testGetWifiEntries_wifiNetworkEntries_createdForEachSsidAndSecurityPair() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        final ScanResult openNetwork = buildScanResult("Open Network", "bssid0", START_MILLIS);
        final ScanResult openNetworkDup = buildScanResult("Open Network", "bssid1", START_MILLIS);
        final ScanResult secureNetwork = buildScanResult("Secure Network", "bssid2", START_MILLIS);
        secureNetwork.capabilities = "EAP";

        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                openNetwork,
                openNetworkDup,
                secureNetwork,
                // Ignore null and empty SSIDs
                buildScanResult(null, "bssidNull", START_MILLIS),
                buildScanResult("", "bssidEmpty", START_MILLIS)));

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        List<String> seenKeys = new ArrayList<>();
        for (WifiEntry wifiEntry : wifiPickerTracker.getWifiEntries()) {
            seenKeys.add(wifiEntry.getKey());
        }

        assertThat(seenKeys).containsExactly(
                StandardWifiEntry.scanResultToStandardWifiEntryKey(openNetwork),
                StandardWifiEntry.scanResultToStandardWifiEntryKey(secureNetwork));
    }

    /**
     * Tests that old WifiEntries are timed out if their scans are older than the max scan age.
     */
    @Test
    public void testGetWifiEntries_wifiNetworkEntries_oldEntriesTimedOut() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        // Initial entries
        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                buildScanResult("ssid0", "bssid0", START_MILLIS),
                buildScanResult("ssid1", "bssid1", START_MILLIS),
                buildScanResult("ssid2", "bssid2", START_MILLIS),
                buildScanResult("ssid3", "bssid3", START_MILLIS),
                buildScanResult("ssid4", "bssid4", START_MILLIS)));
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        // Advance clock to max scan age. Entries should still be valid.
        when(mMockClock.millis()).thenReturn(START_MILLIS + MAX_SCAN_AGE_MILLIS);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        assertThat(wifiPickerTracker.getWifiEntries()).isNotEmpty();


        // Advance the clock to time out old entries
        when(mMockClock.millis()).thenReturn(START_MILLIS + MAX_SCAN_AGE_MILLIS + 1);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        // All entries timed out
        assertThat(wifiPickerTracker.getWifiEntries()).isEmpty();
    }

    /**
     * Tests that a failed scan will result in extending the max scan age by the scan interval.
     * This is to allow the WifiEntry list to stay stable and not clear out if a single scan fails.
     */
    @Test
    public void testGetWifiEntries_wifiNetworkEntries_useOldEntriesOnFailedScan() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        // Initial entries
        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                buildScanResult("ssid0", "bssid0", START_MILLIS),
                buildScanResult("ssid1", "bssid1", START_MILLIS),
                buildScanResult("ssid2", "bssid2", START_MILLIS),
                buildScanResult("ssid3", "bssid3", START_MILLIS),
                buildScanResult("ssid4", "bssid4", START_MILLIS)));
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        final List<WifiEntry> previousEntries = wifiPickerTracker.getWifiEntries();

        // Advance the clock to time out old entries and simulate failed scan
        when(mMockClock.millis())
                .thenReturn(START_MILLIS + MAX_SCAN_AGE_MILLIS + SCAN_INTERVAL_MILLIS);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                        .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false));

        // Failed scan should result in old WifiEntries still being shown
        assertThat(previousEntries).containsExactlyElementsIn(wifiPickerTracker.getWifiEntries());

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                        .putExtra(WifiManager.EXTRA_RESULTS_UPDATED, true));

        // Successful scan should time out old entries.
        assertThat(wifiPickerTracker.getWifiEntries()).isEmpty();
    }

    /**
     * Tests that a CONFIGURED_NETWORKS_CHANGED broadcast updates the correct WifiEntry from
     * unsaved to saved.
     */
    @Test
    public void testGetWifiEntries_configuredNetworksChanged_unsavedToSaved() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());

        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                buildScanResult("ssid", "bssid", START_MILLIS)));
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        final WifiEntry entry = wifiPickerTracker.getWifiEntries().get(0);

        assertThat(entry.isSaved()).isFalse();

        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION)
                        .putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, config)
                        .putExtra(WifiManager.EXTRA_CHANGE_REASON,
                                WifiManager.CHANGE_REASON_ADDED));

        assertThat(entry.isSaved()).isTrue();
    }

    /**
     * Tests that a CONFIGURED_NETWORKS_CHANGED broadcast updates the correct WifiEntry from
     * saved to unsaved.
     */
    @Test
    public void testGetWifiEntries_configuredNetworksChanged_savedToUnsaved() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        when(mMockWifiManager.getConfiguredNetworks())
                .thenReturn(Collections.singletonList(config));
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());
        mTestLooper.dispatchAll();

        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                buildScanResult("ssid", "bssid", START_MILLIS)));
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        final WifiEntry entry = wifiPickerTracker.getWifiEntries().get(0);

        assertThat(entry.isSaved()).isTrue();

        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION)
                        .putExtra(WifiManager.EXTRA_WIFI_CONFIGURATION, config)
                        .putExtra(WifiManager.EXTRA_CHANGE_REASON,
                                WifiManager.CHANGE_REASON_REMOVED));

        assertThat(entry.isSaved()).isFalse();
    }

    /**
     * Tests that getConnectedEntry() returns the connected WifiEntry if we start already connected
     * to a network.
     */
    @Test
    public void testGetConnectedEntry_alreadyConnectedOnStart_returnsConnectedEntry() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.networkId = 1;
        when(mMockWifiManager.getConfiguredNetworks())
                .thenReturn(Collections.singletonList(config));
        when(mMockWifiInfo.getNetworkId()).thenReturn(1);
        when(mMockWifiInfo.getRssi()).thenReturn(-50);
        when(mMockNetworkInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.CONNECTED);

        wifiPickerTracker.onStart();
        mTestLooper.dispatchAll();

        assertThat(wifiPickerTracker.getConnectedWifiEntry()).isNotNull();
    }

    /**
     * Tests that connecting to a network will update getConnectedEntry() to return the connected
     * WifiEntry and remove that entry from getWifiEntries().
     */
    @Test
    public void testGetConnectedEntry_connectToNetwork_returnsConnectedEntry() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.networkId = 1;
        when(mMockWifiManager.getConfiguredNetworks())
                .thenReturn(Collections.singletonList(config));
        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                buildScanResult("ssid", "bssid", START_MILLIS)));
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());
        mTestLooper.dispatchAll();
        final WifiEntry entry = wifiPickerTracker.getWifiEntries().get(0);

        when(mMockWifiInfo.getNetworkId()).thenReturn(1);
        when(mMockWifiInfo.getRssi()).thenReturn(-50);
        when(mMockNetworkInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.CONNECTED);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                        .putExtra(WifiManager.EXTRA_NETWORK_INFO, mMockNetworkInfo));

        verify(mMockCallback, atLeastOnce()).onWifiEntriesChanged();
        assertThat(wifiPickerTracker.getWifiEntries()).isEmpty();
        assertThat(wifiPickerTracker.getConnectedWifiEntry()).isEqualTo(entry);
    }

    /**
     * Tests that disconnecting from a network will update getConnectedEntry() to return null.
     */
    @Test
    public void testGetConnectedEntry_disconnectFromNetwork_returnsNull() {
        final WifiPickerTracker wifiPickerTracker = createTestWifiPickerTracker();
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.networkId = 1;
        when(mMockWifiManager.getConfiguredNetworks())
                .thenReturn(Collections.singletonList(config));
        when(mMockWifiManager.getScanResults()).thenReturn(Arrays.asList(
                buildScanResult("ssid", "bssid", START_MILLIS)));
        when(mMockWifiInfo.getNetworkId()).thenReturn(1);
        when(mMockWifiInfo.getRssi()).thenReturn(-50);
        when(mMockNetworkInfo.getDetailedState()).thenReturn(NetworkInfo.DetailedState.CONNECTED);
        wifiPickerTracker.onStart();
        verify(mMockContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(), any(), any());
        mTestLooper.dispatchAll();

        when(mMockNetworkInfo.getDetailedState())
                .thenReturn(NetworkInfo.DetailedState.DISCONNECTED);
        mBroadcastReceiverCaptor.getValue().onReceive(mMockContext,
                new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                        .putExtra(WifiManager.EXTRA_NETWORK_INFO, mMockNetworkInfo));

        verify(mMockCallback, atLeastOnce()).onWifiEntriesChanged();
        assertThat(wifiPickerTracker.getConnectedWifiEntry()).isNull();
    }
}
