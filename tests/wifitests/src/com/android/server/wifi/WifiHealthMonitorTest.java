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

import static com.android.server.wifi.WifiScoreCard.MIN_NUM_CONNECTION_ATTEMPT;
import static com.android.server.wifi.WifiScoreCard.TS_NONE;
import static com.android.server.wifi.util.NativeUtil.hexStringFromByteArray;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.app.test.TestAlarmManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.wifi.ScanResult.InformationElement;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanListener;
import android.net.wifi.WifiScanner.ScanSettings;
import android.net.wifi.WifiSsid;
import android.os.Build;
import android.os.Handler;
import android.os.test.TestLooper;

import androidx.test.filters.SmallTest;

import com.android.server.wifi.WifiConfigManager.OnNetworkUpdateListener;
import com.android.server.wifi.WifiHealthMonitor.ScanStats;
import com.android.server.wifi.WifiHealthMonitor.WifiSoftwareBuildInfo;
import com.android.server.wifi.WifiHealthMonitor.WifiSystemInfoStats;
import com.android.server.wifi.WifiScoreCard.PerNetwork;
import com.android.server.wifi.proto.WifiScoreCardProto.SystemInfoStats;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.server.wifi.WifiHealthMonitor}.
 */
@SmallTest
public class WifiHealthMonitorTest extends WifiBaseTest {

    static final WifiSsid TEST_SSID_1 = WifiSsid.createFromAsciiEncoded("Joe's Place");
    static final WifiSsid TEST_SSID_2 = WifiSsid.createFromAsciiEncoded("Poe's Place");
    static final MacAddress TEST_BSSID_1 = MacAddress.fromString("aa:bb:cc:dd:ee:ff");

    private WifiScoreCard mWifiScoreCard;
    private WifiHealthMonitor mWifiHealthMonitor;

    @Mock
    Clock mClock;
    @Mock
    WifiScoreCard.MemoryStore mMemoryStore;
    @Mock
    WifiInjector mWifiInjector;
    @Mock
    Context mContext;
    @Mock
    DeviceConfigFacade mDeviceConfigFacade;
    @Mock
    WifiNative mWifiNative;
    @Mock
    PackageManager mPackageManager;
    @Mock
    PackageInfo mPackageInfo;

    private final ArrayList<String> mKeys = new ArrayList<>();
    private final ArrayList<WifiScoreCard.BlobListener> mBlobListeners = new ArrayList<>();
    private final ArrayList<byte[]> mBlobs = new ArrayList<>();

    private ScanSettings mScanSettings = new ScanSettings();
    private WifiConfigManager mWifiConfigManager;
    private long mMilliSecondsSinceBoot;
    private ExtendedWifiInfo mWifiInfo;
    private WifiConfiguration mWifiConfig;
    private String mDriverVersion;
    private String mFirmwareVersion;
    private TestAlarmManager mAlarmManager;
    private TestLooper mLooper = new TestLooper();
    private List<WifiConfiguration> mConfiguredNetworks;
    private WifiScanner mWifiScanner;
    private ScanData mScanData;
    private ScanListener mScanListener;
    private OnNetworkUpdateListener mOnNetworkUpdateListener;

    private void millisecondsPass(long ms) {
        mMilliSecondsSinceBoot += ms;
        when(mClock.getElapsedSinceBootMillis()).thenReturn(mMilliSecondsSinceBoot);
        when(mClock.getWallClockMillis()).thenReturn(mMilliSecondsSinceBoot + 1_500_000_000_000L);
    }

    /**
     * Sets up for unit test.
     */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mKeys.clear();
        mBlobListeners.clear();
        mBlobs.clear();
        mConfiguredNetworks = new ArrayList<>();
        mMilliSecondsSinceBoot = 0;
        mWifiInfo = new ExtendedWifiInfo(mock(Context.class));
        mWifiInfo.setBSSID(TEST_BSSID_1.toString());
        mWifiInfo.setSSID(TEST_SSID_1);
        // Add 1st configuration
        mWifiConfig = new WifiConfiguration();
        mWifiConfig.SSID = mWifiInfo.getSSID();
        mConfiguredNetworks.add(mWifiConfig);
        // Add 2nd configuration
        mWifiInfo.setSSID(TEST_SSID_2);
        mWifiConfig = new WifiConfiguration();
        mWifiConfig.SSID = mWifiInfo.getSSID();
        mConfiguredNetworks.add(mWifiConfig);

        millisecondsPass(0);

        mDriverVersion = "build 1.1";
        mFirmwareVersion = "HW 1.1";
        mPackageInfo.versionCode = 1;
        when(mContext.getPackageName()).thenReturn("WifiAPK");
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(mPackageInfo);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);

        mWifiConfigManager = mockConfigManager();

        mWifiScoreCard = new WifiScoreCard(mClock, "some seed");
        mAlarmManager = new TestAlarmManager();
        when(mContext.getSystemService(Context.ALARM_SERVICE))
                .thenReturn(mAlarmManager.getAlarmManager());

        mScanData = mockScanData();
        mWifiScanner = mockWifiScanner(WifiScanner.WIFI_BAND_BOTH_WITH_DFS);
        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        when(mWifiNative.getDriverVersion()).thenReturn(mDriverVersion);
        when(mWifiNative.getFirmwareVersion()).thenReturn(mFirmwareVersion);

        mWifiHealthMonitor = new WifiHealthMonitor(mContext, mWifiInjector, mClock,
                mWifiConfigManager, mWifiScoreCard, new Handler(mLooper.getLooper()), mWifiNative,
                "some seed", mDeviceConfigFacade);
    }

    private WifiConfigManager mockConfigManager() {
        WifiConfigManager wifiConfigManager = mock(WifiConfigManager.class);
        when(wifiConfigManager.getConfiguredNetworks()).thenReturn(mConfiguredNetworks);
        when(wifiConfigManager.findScanRssi(anyInt(), anyInt()))
                .thenReturn(-53);

        doAnswer(new AnswerWithArguments() {
            public void answer(OnNetworkUpdateListener listener) throws Exception {
                mOnNetworkUpdateListener = listener;
            }
        }).when(wifiConfigManager).addOnNetworkUpdateListener(anyObject());

        doAnswer(new AnswerWithArguments() {
            public boolean answer(int networkId, int uid, String packageName) throws Exception {
                mOnNetworkUpdateListener.onNetworkRemoved(mWifiConfig);
                return true;
            }
        }).when(wifiConfigManager).removeNetwork(anyInt(), anyInt(), anyString());

        doAnswer(new AnswerWithArguments() {
            public NetworkUpdateResult answer(WifiConfiguration config, int uid) throws Exception {
                mOnNetworkUpdateListener.onNetworkAdded(config);
                return new NetworkUpdateResult(1);
            }
        }).when(wifiConfigManager).addOrUpdateNetwork(any(), anyInt());

        return wifiConfigManager;
    }

    ScanData mockScanData() {
        ScanData[] scanDatas =
                ScanTestUtil.createScanDatas(new int[][]{{5150, 5175, 2412, 2437}}, new int[]{0});
        // Scan result does require to have an IE.
        scanDatas[0].getResults()[0].informationElements = new InformationElement[0];
        scanDatas[0].getResults()[1].informationElements = new InformationElement[0];
        scanDatas[0].getResults()[2].informationElements = new InformationElement[0];
        scanDatas[0].getResults()[3].informationElements = new InformationElement[0];

        return scanDatas[0];
    }

    ScanData mockScanDataAbove2GOnly() {
        ScanData[] scanDatas =
                ScanTestUtil.createScanDatas(new int[][]{{5150, 5175, 5500, 5845}}, new int[]{0});
        // Scan result does require to have an IE.
        scanDatas[0].getResults()[0].informationElements = new InformationElement[0];
        scanDatas[0].getResults()[1].informationElements = new InformationElement[0];
        scanDatas[0].getResults()[2].informationElements = new InformationElement[0];
        scanDatas[0].getResults()[3].informationElements = new InformationElement[0];

        return scanDatas[0];
    }

    WifiScanner mockWifiScanner(@WifiScanner.WifiBand int wifiBand) {
        WifiScanner scanner = mock(WifiScanner.class);

        doAnswer(new AnswerWithArguments() {
            public void answer(ScanListener listener) throws Exception {
                mScanListener = listener;
            }
        }).when(scanner).registerScanListener(anyObject());

        ScanData[] scanDatas = new ScanData[1];
        scanDatas[0] = mock(ScanData.class);
        when(scanDatas[0].getBandScanned()).thenReturn(wifiBand);
        doAnswer(new AnswerWithArguments() {
            public void answer(ScanSettings settings, ScanListener listener) throws Exception {
                if (mScanData != null && mScanData.getResults() != null) {
                    for (int i = 0; i < mScanData.getResults().length; i++) {
                        listener.onFullResult(
                                mScanData.getResults()[i]);
                    }
                }
                listener.onResults(scanDatas);
            }
        }).when(scanner).startScan(anyObject(), anyObject());

        return scanner;
    }


    private void makeNetworkConnectionExample() {
        mWifiScoreCard.noteConnectionAttempt(mWifiInfo, -53, mWifiInfo.getSSID());
        millisecondsPass(5000);
        mWifiInfo.setRssi(-55);
        mWifiScoreCard.noteValidationSuccess(mWifiInfo);
        millisecondsPass(1000);
        mWifiScoreCard.noteSignalPoll(mWifiInfo);
        millisecondsPass(2000);
        int disconnectionReason = 3;
        mWifiScoreCard.noteNonlocalDisconnect(disconnectionReason);
        millisecondsPass(10);
        mWifiScoreCard.resetConnectionState();
    }

    private void makeRecentStatsWithSufficientConnectionAttempt() {
        for (int i = 0; i < MIN_NUM_CONNECTION_ATTEMPT; i++) {
            makeNetworkConnectionExample();
        }
    }

    private byte[] makeSerializedExample() {
        // Install a dummy memoryStore
        // trigger extractCurrentSoftwareBuildInfo() call to update currSoftwareBuildInfo
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
        mWifiHealthMonitor.setWifiEnabled(true);
        millisecondsPass(5000);
        mWifiScanner.startScan(mScanSettings, mScanListener);
        mAlarmManager.dispatch(WifiHealthMonitor.POST_BOOT_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();
        // serialized now has currSoftwareBuildInfo and scan results
        return mWifiHealthMonitor.getWifiSystemInfoStats().toSystemInfoStats().toByteArray();
    }

    private void makeSwBuildChangeExample(String firmwareVersion) {
        byte[] serialized = makeSerializedExample();
        // Install a real MemoryStore object, which records read requests
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(new WifiScoreCard.MemoryStore() {
            @Override
            public void read(String key, String name, WifiScoreCard.BlobListener listener) {
                mBlobListeners.add(listener);
            }

            @Override
            public void write(String key, String name, byte[] value) {
                mKeys.add(key);
                mBlobs.add(value);
            }
        });
        mBlobListeners.get(0).onBlobRetrieved(serialized);

        // Change current FW version
        when(mWifiNative.getFirmwareVersion()).thenReturn(firmwareVersion);
    }

    /**
     * Test read and write around SW change.
     */
    @Test
    public void testReadWriteAndSWChange() throws Exception {
        String firmwareVersion = "HW 1.2";
        makeSwBuildChangeExample(firmwareVersion);
        mAlarmManager.dispatch(WifiHealthMonitor.POST_BOOT_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();
        // Now it should detect SW change, disable WiFi to trigger write
        mWifiHealthMonitor.setWifiEnabled(false);

        // Check current and previous FW version of WifiSystemInfoStats
        WifiSystemInfoStats wifiSystemInfoStats = mWifiHealthMonitor.getWifiSystemInfoStats();
        assertEquals(firmwareVersion, wifiSystemInfoStats.getCurrSoftwareBuildInfo()
                .getWifiFirmwareVersion());
        assertEquals(mFirmwareVersion, wifiSystemInfoStats.getPrevSoftwareBuildInfo()
                .getWifiFirmwareVersion());

        // Check write
        String writtenHex = hexStringFromByteArray(mBlobs.get(mKeys.size() - 1));
        String currFirmwareVersionHex = hexStringFromByteArray(
                firmwareVersion.getBytes(StandardCharsets.UTF_8));
        String prevFirmwareVersionHex = hexStringFromByteArray(
                mFirmwareVersion.getBytes(StandardCharsets.UTF_8));
        assertTrue(writtenHex, writtenHex.contains(currFirmwareVersionHex));
        assertTrue(writtenHex, writtenHex.contains(prevFirmwareVersionHex));
    }

    /**
     * Test serialization and deserialization of WifiSystemInfoStats.
     */
    @Test
    public void testSerializationDeserialization() throws Exception  {
        // Install a dummy memoryStore
        // trigger extractCurrentSoftwareBuildInfo() call to update currSoftwareBuildInfo
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
        mWifiHealthMonitor.setWifiEnabled(true);
        millisecondsPass(5000);
        mWifiScanner.startScan(mScanSettings, mScanListener);
        mAlarmManager.dispatch(WifiHealthMonitor.POST_BOOT_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();
        WifiSystemInfoStats wifiSystemInfoStats = mWifiHealthMonitor.getWifiSystemInfoStats();
        // serialized now has currSoftwareBuildInfo and recent scan info
        byte[] serialized = wifiSystemInfoStats.toSystemInfoStats().toByteArray();
        SystemInfoStats systemInfoStats = SystemInfoStats.parseFrom(serialized);
        WifiSoftwareBuildInfo currSoftwareBuildInfoFromMemory = wifiSystemInfoStats
                .fromSoftwareBuildInfo(systemInfoStats.getCurrSoftwareBuildInfo());
        assertEquals(mPackageInfo.versionCode,
                currSoftwareBuildInfoFromMemory.getWifiStackVersion());
        assertEquals(mDriverVersion, currSoftwareBuildInfoFromMemory.getWifiDriverVersion());
        assertEquals(mFirmwareVersion, currSoftwareBuildInfoFromMemory.getWifiFirmwareVersion());
        assertEquals(Build.DISPLAY, currSoftwareBuildInfoFromMemory.getOsBuildVersion());
        assertEquals(1_500_000_005_000L, systemInfoStats.getLastScanTimeMs());
        assertEquals(2, systemInfoStats.getNumBssidLastScan2G());
        assertEquals(2, systemInfoStats.getNumBssidLastScanAbove2G());
    }

    /**
     * Check stats after two daily detections.
     */
    @Test
    public void testTwoDailyDetections() throws Exception {
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
        // day 1
        makeRecentStatsWithSufficientConnectionAttempt();
        mAlarmManager.dispatch(WifiHealthMonitor.DAILY_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();
        // day 2
        makeRecentStatsWithSufficientConnectionAttempt();
        mAlarmManager.dispatch(WifiHealthMonitor.DAILY_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();

        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertEquals(MIN_NUM_CONNECTION_ATTEMPT * 2,
                perNetwork.getStatsCurrBuild().getCount(WifiScoreCard.CNT_CONNECTION_ATTEMPT));
    }

    /**
     * test stats after a SW build change
     */
    @Test
    public void testAfterSwBuildChange() throws Exception {
        // Day 1
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
        makeRecentStatsWithSufficientConnectionAttempt();
        mAlarmManager.dispatch(WifiHealthMonitor.DAILY_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();

        // Day 2
        String firmwareVersion = "HW 1.2";
        makeSwBuildChangeExample(firmwareVersion);
        mAlarmManager.dispatch(WifiHealthMonitor.POST_BOOT_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();
        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertEquals(0,
                perNetwork.getStatsCurrBuild().getCount(WifiScoreCard.CNT_CONNECTION_ATTEMPT));
        assertEquals(MIN_NUM_CONNECTION_ATTEMPT * 1,
                perNetwork.getStatsPrevBuild().getCount(WifiScoreCard.CNT_CONNECTION_ATTEMPT));
    }

    /**
     * Installing a MemoryStore after startup should issue reads.
     */
    @Test
    public void testReadAfterDelayedMemoryStoreInstallation() throws Exception {
        makeNetworkConnectionExample();
        assertEquals(2, mConfiguredNetworks.size());
        mWifiScoreCard.installMemoryStore(mMemoryStore);
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);

        // 1 for WifiSystemInfoStats, 1 for requestReadBssid and 2 for requestReadNetwork
        verify(mMemoryStore, times(4)).read(any(), any(), any());
    }

    /**
     * Installing a MemoryStore during startup should issue a proper number of reads.
     */
    @Test
    public void testReadAfterStartupMemoryStoreInstallation() throws Exception {
        mWifiScoreCard.installMemoryStore(mMemoryStore);
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
        makeNetworkConnectionExample();
        assertEquals(2, mConfiguredNetworks.size());

        // 1 for WifiSystemInfoStats, 1 for requestReadBssid and 2 for requestReadNetwork
        verify(mMemoryStore, times(4)).read(any(), any(), any());
    }

    /**
     * Installing a MemoryStore twice should not cause crash.
     */
    @Test
    public void testInstallMemoryStoreTwiceNoCrash() throws Exception {
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
        makeNetworkConnectionExample();
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(mMemoryStore);
    }

    /**
     * Check if scan results are reported correctly after full band scan.
     */
    @Test
    public void testFullBandScan() throws Exception {
        millisecondsPass(5000);
        mWifiHealthMonitor.setWifiEnabled(true);
        mWifiScanner.startScan(mScanSettings, mScanListener);
        ScanStats scanStats = mWifiHealthMonitor.getWifiSystemInfoStats().getCurrScanStats();
        assertEquals(1_500_000_005_000L, scanStats.getLastScanTimeMs());
        assertEquals(2, scanStats.getNumBssidLastScanAbove2g());
        assertEquals(2, scanStats.getNumBssidLastScan2g());
    }

    /**
     * Check if scan results are reported correctly after 2G only scan.
     */
    @Test
    public void test2GScan() throws Exception {
        mWifiScanner = mockWifiScanner(WifiScanner.WIFI_BAND_24_GHZ);
        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        millisecondsPass(5000);
        mWifiHealthMonitor.setWifiEnabled(true);
        mWifiScanner.startScan(mScanSettings, mScanListener);
        ScanStats scanStats = mWifiHealthMonitor.getWifiSystemInfoStats().getCurrScanStats();
        assertEquals(TS_NONE, scanStats.getLastScanTimeMs());
        assertEquals(0, scanStats.getNumBssidLastScanAbove2g());
        assertEquals(0, scanStats.getNumBssidLastScan2g());
    }

    @Test
    public void testClearReallyDoesClearTheState() throws Exception {
        byte[] init = mWifiHealthMonitor.getWifiSystemInfoStats()
                .toSystemInfoStats().toByteArray();
        byte[] serialized = makeSerializedExample();
        assertNotEquals(0, serialized.length);
        mWifiHealthMonitor.clear();
        byte[] leftovers = mWifiHealthMonitor.getWifiSystemInfoStats()
                .toSystemInfoStats().toByteArray();
        assertEquals(init.length, leftovers.length);
    }

    @Test
    public void testPostBootAbnormalScanDetection() throws Exception {
        // Serialized has the last scan result
        byte [] serialized = makeSerializedExample();
        // Startup DUT again to mimic reboot
        setUp();
        // Install a real MemoryStore object, which records read requests
        mWifiHealthMonitor.installMemoryStoreSetUpDetectionAlarm(new WifiScoreCard.MemoryStore() {
            @Override
            public void read(String key, String name, WifiScoreCard.BlobListener listener) {
                mBlobListeners.add(listener);
            }
            @Override
            public void write(String key, String name, byte[] value) {
                mKeys.add(key);
                mBlobs.add(value);
            }
        });
        mBlobListeners.get(0).onBlobRetrieved(serialized);

        SystemInfoStats systemInfoStats = SystemInfoStats.parseFrom(serialized);
        assertEquals(1_500_000_005_000L, systemInfoStats.getLastScanTimeMs());
        assertEquals(2, systemInfoStats.getNumBssidLastScan2G());
        assertEquals(2, systemInfoStats.getNumBssidLastScanAbove2G());

        // Add Above2G only scan data
        mScanData = mockScanDataAbove2GOnly();
        mWifiScanner = mockWifiScanner(WifiScanner.WIFI_BAND_BOTH_WITH_DFS);
        when(mWifiInjector.getWifiScanner()).thenReturn(mWifiScanner);
        millisecondsPass(5000);
        mWifiHealthMonitor.setWifiEnabled(true);
        mWifiScanner.startScan(mScanSettings, mScanListener);

        mAlarmManager.dispatch(WifiHealthMonitor.POST_BOOT_DETECTION_TIMER_TAG);
        mLooper.dispatchAll();

        // It should detect abnormal scan failure now.
        assertEquals(4, mWifiHealthMonitor.getWifiSystemInfoStats().getScanFailure());
    }

    @Test
    public void testRemoveNetwork() throws Exception {
        makeNetworkConnectionExample();
        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertNotNull(perNetwork);

        // Now remove the network
        mWifiConfigManager.removeNetwork(1, 1, "some package");
        perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertNull(perNetwork);
    }

    @Test
    public void testAddNetwork() throws Exception {
        PerNetwork perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertNull(perNetwork);

        // Now add network
        mWifiConfigManager.addOrUpdateNetwork(mWifiConfig, 1);
        perNetwork = mWifiScoreCard.fetchByNetwork(mWifiInfo.getSSID());
        assertNotNull(perNetwork);
    }
    // TODO: add test for metric report
}
