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

import static com.android.wifitrackerlib.StandardWifiEntry.ssidAndSecurityToStandardWifiEntryKey;
import static com.android.wifitrackerlib.StandardWifiEntry.wifiConfigToStandardWifiEntryKey;
import static com.android.wifitrackerlib.TestUtils.buildScanResult;
import static com.android.wifitrackerlib.Utils.getAppLabelForSavedNetwork;
import static com.android.wifitrackerlib.Utils.getAutoConnectDescription;
import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getMeteredDescription;
import static com.android.wifitrackerlib.Utils.mapScanResultsToKey;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_NONE;
import static com.android.wifitrackerlib.WifiEntry.SECURITY_PSK;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.test.TestLooper;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class UtilsTest {

    private static final String LABEL_AUTO_CONNECTION_DISABLED = "Auto-Connection disabled";
    private static final String LABEL_METERED = "Metered";
    private static final String LABEL_UNMETERED = "Unmetered";

    private static final String SYSTEM_UID_APP_NAME = "systemUidAppName";
    private static final String APP_LABEL = "appLabel";
    private static final String SETTINGS_APP_NAME = "com.android.settings";

    @Mock private Context mMockContext;
    @Mock private Resources mMockResources;
    @Mock private NetworkScoreManager mMockNetworkScoreManager;

    private Handler mTestHandler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        TestLooper testLooper = new TestLooper();
        mTestHandler = new Handler(testLooper.getLooper());
        when(mMockContext.getResources()).thenReturn(mMockResources);
        when(mMockContext.getSystemService(Context.NETWORK_SCORE_SERVICE))
                .thenReturn(mMockNetworkScoreManager);
    }

    @Test
    public void testGetBestScanResult_emptyList_returnsNull() {
        assertThat(getBestScanResultByLevel(new ArrayList<>())).isNull();
    }

    @Test
    public void testGetBestScanResult_returnsBestRssiScan() {
        final ScanResult bestResult = buildScanResult("ssid", "bssid", 0, -50);
        final ScanResult okayResult = buildScanResult("ssid", "bssid", 0, -60);
        final ScanResult badResult = buildScanResult("ssid", "bssid", 0, -70);

        assertThat(getBestScanResultByLevel(Arrays.asList(bestResult, okayResult, badResult)))
                .isEqualTo(bestResult);
    }

    @Test
    public void testGetBestScanResult_singleScan_returnsScan() {
        final ScanResult scan = buildScanResult("ssid", "bssid", 0, -50);

        assertThat(getBestScanResultByLevel(Arrays.asList(scan))).isEqualTo(scan);
    }

    @Test
    public void testMapScanResultsToKey_filtersUnsupportedCapabilities() {
        final ScanResult wpa3SaeScan = new ScanResult();
        final ScanResult wpa3SuiteBScan = new ScanResult();
        final ScanResult oweScan = new ScanResult();
        wpa3SaeScan.SSID = "wpa3Sae";
        wpa3SaeScan.capabilities = "[SAE]";
        wpa3SuiteBScan.SSID = "wpa3SuiteB";
        wpa3SuiteBScan.capabilities = "[EAP_SUITE_B_192]";
        oweScan.SSID = "owe";
        oweScan.capabilities = "[OWE]";

        final Map<String, List<ScanResult>> scanResultsByKey = mapScanResultsToKey(
                Arrays.asList(wpa3SaeScan, wpa3SuiteBScan, oweScan),
                false /* chooseSingleSecurity */,
                null /* wifiConfigsByKey */,
                false /* isWpa3SaeSupported */,
                false /* isWpa3SuiteBSupported */,
                false /* isEnhancedOpenSupported */);

        assertThat(scanResultsByKey).isEmpty();
    }

    @Test
    public void testMapScanResultsToKey_convertsTransitionModeScansToSupportedSecurity() {
        final ScanResult wpa3TransitionScan = new ScanResult();
        final ScanResult oweTransitionScan = new ScanResult();
        wpa3TransitionScan.SSID = "wpa3Transition";
        wpa3TransitionScan.capabilities = "[PSK+SAE]";
        oweTransitionScan.SSID = "owe";
        oweTransitionScan.capabilities = "[OWE_TRANSITION]";

        final Map<String, List<ScanResult>> scanResultsByKey = mapScanResultsToKey(
                Arrays.asList(wpa3TransitionScan, oweTransitionScan),
                false /* chooseSingleSecurity */,
                null /* wifiConfigsByKey */,
                false /* isWpa3SaeSupported */,
                false /* isWpa3SuiteBSupported */,
                false /* isEnhancedOpenSupported */);

        assertThat(scanResultsByKey.keySet()).containsExactly(
                ssidAndSecurityToStandardWifiEntryKey(wpa3TransitionScan.SSID, SECURITY_PSK),
                ssidAndSecurityToStandardWifiEntryKey(oweTransitionScan.SSID, SECURITY_NONE));
    }

    @Test
    public void testGetAppLabelForSavedNetwork_returnAppLabel() {
        final PackageManager mockPackageManager = mock(PackageManager.class);
        when(mMockContext.getPackageManager()).thenReturn(mockPackageManager);
        when(mockPackageManager.getNameForUid(android.os.Process.SYSTEM_UID))
                .thenReturn(SYSTEM_UID_APP_NAME);
        final ApplicationInfo mockApplicationInfo = mock(ApplicationInfo.class);
        when(mMockContext.getApplicationInfo()).thenReturn(mockApplicationInfo);
        mockApplicationInfo.packageName = SYSTEM_UID_APP_NAME;
        when(mockApplicationInfo.loadLabel(mockPackageManager)).thenReturn(APP_LABEL);
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.creatorName = SYSTEM_UID_APP_NAME;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.settings_package))
                .thenReturn(SETTINGS_APP_NAME);

        final CharSequence appLabel = getAppLabelForSavedNetwork(mMockContext, entry);

        assertThat(appLabel).isEqualTo(APP_LABEL);
    }

    @Test
    public void testGetAutoConnectDescription_autoJoinEnabled_returnEmptyString() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.allowAutojoin = true;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.auto_connect_disable))
                .thenReturn(LABEL_AUTO_CONNECTION_DISABLED);

        final String autoConnectDescription = getAutoConnectDescription(mMockContext, entry);

        assertThat(autoConnectDescription).isEqualTo("");
    }

    @Test
    public void testGetAutoConnectDescription_autoJoinDisabled_returnDisable() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.allowAutojoin = false;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.auto_connect_disable))
                .thenReturn(LABEL_AUTO_CONNECTION_DISABLED);

        final String autoConnectDescription = getAutoConnectDescription(mMockContext, entry);

        assertThat(autoConnectDescription).isEqualTo(LABEL_AUTO_CONNECTION_DISABLED);
    }

    @Test
    public void testGetMeteredDescription_noOverrideNoHint_returnEmptyString() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        config.meteredHint = false;
        final StandardWifiEntry entry = getStandardWifiEntry(config);

        final String meteredDescription = getMeteredDescription(mMockContext, entry);

        assertThat(meteredDescription).isEqualTo("");
    }

    @Test
    public void testGetMeteredDescription_overrideMetered_returnMetered() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.wifi_metered_label)).thenReturn(LABEL_METERED);

        final String meteredDescription = getMeteredDescription(mMockContext, entry);

        assertThat(meteredDescription).isEqualTo(LABEL_METERED);
    }

    @Ignore // TODO(b/70983952): Remove ignore tag when StandardWifiEntry#isMetered() is ready.
    @Test
    public void testGetMeteredDescription__meteredHintTrueAndOverrideNone_returnMetered() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.meteredHint = true;
        config.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.wifi_metered_label)).thenReturn(LABEL_METERED);

        final String meteredDescription = getMeteredDescription(mMockContext, entry);

        assertThat(meteredDescription).isEqualTo(LABEL_METERED);
    }

    @Test
    public void testGetMeteredDescription__meteredHintTrueAndOverrideMetered_returnMetered() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.meteredHint = true;
        config.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.wifi_metered_label)).thenReturn(LABEL_METERED);

        final String meteredDescription = getMeteredDescription(mMockContext, entry);

        assertThat(meteredDescription).isEqualTo(LABEL_METERED);
    }

    @Test
    public void testGetMeteredDescription__meteredHintTrueAndOverrideNotMetered_returnUnmetered() {
        final WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"ssid\"";
        config.meteredHint = true;
        config.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        final StandardWifiEntry entry = getStandardWifiEntry(config);
        when(mMockResources.getString(R.string.wifi_unmetered_label)).thenReturn(LABEL_UNMETERED);

        final String meteredDescription = getMeteredDescription(mMockContext, entry);

        assertThat(meteredDescription).isEqualTo(LABEL_UNMETERED);
    }

    private StandardWifiEntry getStandardWifiEntry(WifiConfiguration config) {
        final WifiManager mockWifiManager = mock(WifiManager.class);
        final StandardWifiEntry entry = new StandardWifiEntry(mMockContext, mTestHandler,
                wifiConfigToStandardWifiEntryKey(config), config,
                mockWifiManager);
        final WifiInfo mockWifiInfo = mock(WifiInfo.class);
        final NetworkInfo mockNetworkInfo = mock(NetworkInfo.class);

        entry.updateConnectionInfo(mockWifiInfo, mockNetworkInfo);
        return entry;
    }
}
