/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.WifiNative.SendMgmtFrameCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.wificond.ChannelSettings;
import com.android.server.wifi.wificond.HiddenNetwork;
import com.android.server.wifi.wificond.IApInterface;
import com.android.server.wifi.wificond.IApInterfaceEventCallback;
import com.android.server.wifi.wificond.IClientInterface;
import com.android.server.wifi.wificond.IPnoScanEvent;
import com.android.server.wifi.wificond.IScanEvent;
import com.android.server.wifi.wificond.ISendMgmtFrameEvent;
import com.android.server.wifi.wificond.IWifiScannerImpl;
import com.android.server.wifi.wificond.IWificond;
import com.android.server.wifi.wificond.NativeScanResult;
import com.android.server.wifi.wificond.NativeWifiClient;
import com.android.server.wifi.wificond.PnoNetwork;
import com.android.server.wifi.wificond.PnoSettings;
import com.android.server.wifi.wificond.RadioChainInfo;
import com.android.server.wifi.wificond.SingleScanSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * This class provides methods for WifiNative to send control commands to wificond.
 * NOTE: This class should only be used from WifiNative.
 */
public class WificondControl implements IBinder.DeathRecipient {
    private boolean mVerboseLoggingEnabled = false;

    /**
     * The {@link #sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int) sendMgmtFrame()}
     * timeout, in milliseconds, after which
     * {@link SendMgmtFrameCallback#onFailure(int)} will be called with reason
     * {@link WifiNative#SEND_MGMT_FRAME_ERROR_TIMEOUT}.
     */
    public static final int SEND_MGMT_FRAME_TIMEOUT_MS = 1000;

    private static final String TAG = "WificondControl";

    private static final String TIMEOUT_ALARM_TAG = TAG + " Send Management Frame Timeout";

    /* Get scan results for a single scan */
    public static final int SCAN_TYPE_SINGLE_SCAN = 0;

    /* Get scan results for Pno Scan */
    public static final int SCAN_TYPE_PNO_SCAN = 1;

    private WifiInjector mWifiInjector;
    private WifiMonitor mWifiMonitor;
    private final CarrierNetworkConfig mCarrierNetworkConfig;
    private AlarmManager mAlarmManager;
    private Handler mEventHandler;
    private Clock mClock;
    private WifiNative mWifiNative = null;

    // Cached wificond binder handlers.
    private IWificond mWificond;
    private HashMap<String, IClientInterface> mClientInterfaces = new HashMap<>();
    private HashMap<String, IApInterface> mApInterfaces = new HashMap<>();
    private HashMap<String, IWifiScannerImpl> mWificondScanners = new HashMap<>();
    private HashMap<String, IScanEvent> mScanEventHandlers = new HashMap<>();
    private HashMap<String, IPnoScanEvent> mPnoScanEventHandlers = new HashMap<>();
    private HashMap<String, IApInterfaceEventCallback> mApInterfaceListeners = new HashMap<>();
    private WifiNative.WificondDeathEventHandler mDeathEventHandler;
    /**
     * Ensures that no more than one sendMgmtFrame operation runs concurrently.
     */
    private AtomicBoolean mSendMgmtFrameInProgress = new AtomicBoolean(false);
    private boolean mIsEnhancedOpenSupportedInitialized = false;
    private boolean mIsEnhancedOpenSupported;

    private static final int MAX_SSID_LEN = 32;

    private class ScanEventHandler extends IScanEvent.Stub {
        private String mIfaceName;

        ScanEventHandler(@NonNull String ifaceName) {
            mIfaceName = ifaceName;
        }

        @Override
        public void OnScanResultReady() {
            Log.d(TAG, "Scan result ready event");
            mWifiMonitor.broadcastScanResultEvent(mIfaceName);
        }

        @Override
        public void OnScanFailed() {
            Log.d(TAG, "Scan failed event");
            mWifiMonitor.broadcastScanFailedEvent(mIfaceName);
        }
    }

    WificondControl(WifiInjector wifiInjector, WifiMonitor wifiMonitor,
            CarrierNetworkConfig carrierNetworkConfig, AlarmManager alarmManager, Handler handler,
            Clock clock) {
        mWifiInjector = wifiInjector;
        mWifiMonitor = wifiMonitor;
        mCarrierNetworkConfig = carrierNetworkConfig;
        mAlarmManager = alarmManager;
        mEventHandler = handler;
        mClock = clock;
    }

    private class PnoScanEventHandler extends IPnoScanEvent.Stub {
        private String mIfaceName;

        PnoScanEventHandler(@NonNull String ifaceName) {
            mIfaceName = ifaceName;
        }

        @Override
        public void OnPnoNetworkFound() {
            Log.d(TAG, "Pno scan result event");
            mWifiMonitor.broadcastPnoScanResultEvent(mIfaceName);
            mWifiInjector.getWifiMetrics().incrementPnoFoundNetworkEventCount();
        }

        @Override
        public void OnPnoScanFailed() {
            Log.d(TAG, "Pno Scan failed event");
            mWifiInjector.getWifiMetrics().incrementPnoScanFailedCount();
        }
    }

    /**
     * Listener for AP Interface events.
     */
    private class ApInterfaceEventCallback extends IApInterfaceEventCallback.Stub {
        private SoftApListener mSoftApListener;

        ApInterfaceEventCallback(SoftApListener listener) {
            mSoftApListener = listener;
        }

        @Override
        public void onConnectedClientsChanged(NativeWifiClient[] clients) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "onConnectedClientsChanged called with " + clients.length + " clients");
                for (int i = 0; i < clients.length; i++) {
                    Log.d(TAG, " mac " + clients[i].macAddress);
                }
            }

            mSoftApListener.onConnectedClientsChanged(Arrays.asList(clients));
        }

        @Override
        public void onSoftApChannelSwitched(int frequency, int bandwidth) {
            mSoftApListener.onSoftApChannelSwitched(frequency, bandwidth);
        }
    }

    /**
     * Callback triggered by wificond.
     */
    private class SendMgmtFrameEvent extends ISendMgmtFrameEvent.Stub {
        private SendMgmtFrameCallback mCallback;
        private AlarmManager.OnAlarmListener mTimeoutCallback;
        /**
         * ensures that mCallback is only called once
         */
        private boolean mWasCalled;

        private void runIfFirstCall(Runnable r) {
            if (mWasCalled) return;
            mWasCalled = true;

            mSendMgmtFrameInProgress.set(false);
            r.run();
        }

        SendMgmtFrameEvent(@NonNull SendMgmtFrameCallback callback) {
            mCallback = callback;
            // called in main thread
            mTimeoutCallback = () -> runIfFirstCall(() -> {
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "Timed out waiting for ACK");
                }
                mCallback.onFailure(WifiNative.SEND_MGMT_FRAME_ERROR_TIMEOUT);
            });
            mWasCalled = false;

            mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    mClock.getElapsedSinceBootMillis() + SEND_MGMT_FRAME_TIMEOUT_MS,
                    TIMEOUT_ALARM_TAG, mTimeoutCallback, mEventHandler);
        }

        // called in binder thread
        @Override
        public void OnAck(int elapsedTimeMs) {
            // post to main thread
            mEventHandler.post(() -> runIfFirstCall(() -> {
                mAlarmManager.cancel(mTimeoutCallback);
                mCallback.onAck(elapsedTimeMs);
            }));
        }

        // called in binder thread
        @Override
        public void OnFailure(int reason) {
            // post to main thread
            mEventHandler.post(() -> runIfFirstCall(() -> {
                mAlarmManager.cancel(mTimeoutCallback);
                mCallback.onFailure(reason);
            }));
        }
    }

    /**
     * Called by the binder subsystem upon remote object death.
     * Invoke all the register death handlers and clear state.
     */
    @Override
    public void binderDied() {
        mEventHandler.post(() -> {
            Log.e(TAG, "Wificond died!");
            clearState();
            // Invalidate the global wificond handle on death. Will be refreshed
            // on the next setup call.
            mWificond = null;
            if (mDeathEventHandler != null) {
                mDeathEventHandler.onDeath();
            }
        });
    }

    /** Enable or disable verbose logging of WificondControl.
     *  @param enable True to enable verbose logging. False to disable verbose logging.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Initializes wificond & registers a death notification for wificond.
     * This method clears any existing state in wificond daemon.
     *
     * @return Returns true on success.
     */
    public boolean initialize(@NonNull WifiNative.WificondDeathEventHandler handler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
        }
        mDeathEventHandler = handler;
        tearDownInterfaces();
        return true;
    }

    /**
     * Helper method to retrieve the global wificond handle and register for
     * death notifications.
     */
    private boolean retrieveWificondAndRegisterForDeath() {
        if (mWificond != null) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Wificond handle already retrieved");
            }
            // We already have a wificond handle.
            return true;
        }
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return false;
        }
        try {
            mWificond.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register death notification for wificond");
            // The remote has already died.
            return false;
        }
        return true;
    }

    /**
    * Setup interface for client mode via wificond.
    * @return An IClientInterface as wificond client interface binder handler.
    * Returns null on failure.
    */
    public IClientInterface setupInterfaceForClientMode(@NonNull String ifaceName) {
        Log.d(TAG, "Setting up interface for client mode");
        if (!retrieveWificondAndRegisterForDeath()) {
            return null;
        }

        IClientInterface clientInterface = null;
        try {
            clientInterface = mWificond.createClientInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IClientInterface due to remote exception");
            return null;
        }

        if (clientInterface == null) {
            Log.e(TAG, "Could not get IClientInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(clientInterface.asBinder());

        // Refresh Handlers
        mClientInterfaces.put(ifaceName, clientInterface);
        try {
            IWifiScannerImpl wificondScanner = clientInterface.getWifiScannerImpl();
            if (wificondScanner == null) {
                Log.e(TAG, "Failed to get WificondScannerImpl");
                return null;
            }
            mWificondScanners.put(ifaceName, wificondScanner);
            Binder.allowBlocking(wificondScanner.asBinder());
            ScanEventHandler scanEventHandler = new ScanEventHandler(ifaceName);
            mScanEventHandlers.put(ifaceName,  scanEventHandler);
            wificondScanner.subscribeScanEvents(scanEventHandler);
            PnoScanEventHandler pnoScanEventHandler = new PnoScanEventHandler(ifaceName);
            mPnoScanEventHandlers.put(ifaceName,  pnoScanEventHandler);
            wificondScanner.subscribePnoScanEvents(pnoScanEventHandler);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to refresh wificond scanner due to remote exception");
        }

        return clientInterface;
    }

    /**
     * Unsubscribe scan for specific STA interface configured in wificond.
     * Additionally, trigger stopPnoScan() before invalidating wificond scanner object.
     *
     * @return Returns true on success.
     */
    public boolean unsubscribeScan(@NonNull String ifaceName) {
        if (getClientInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }

        // stop any active pno scan
        stopPnoScan(ifaceName);

        try {
            IWifiScannerImpl scannerImpl = mWificondScanners.get(ifaceName);
            if (scannerImpl != null) {
                scannerImpl.unsubscribeScanEvents();
                scannerImpl.unsubscribePnoScanEvents();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unsubscribe wificond scanner due to remote exception");
            return false;
        }

        mWificondScanners.remove(ifaceName);
        mScanEventHandlers.remove(ifaceName);
        mPnoScanEventHandlers.remove(ifaceName);
        return true;
    }

    /**
     * Teardown a specific STA interface configured in wificond.
     *
     * @return Returns true on success.
     */
    public boolean tearDownClientInterface(@NonNull String ifaceName) {
        if (getClientInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return false;
        }
        try {
            IWifiScannerImpl scannerImpl = mWificondScanners.get(ifaceName);
            if (scannerImpl != null) {
                scannerImpl.unsubscribeScanEvents();
                scannerImpl.unsubscribePnoScanEvents();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unsubscribe wificond scanner due to remote exception");
            return false;
        }

        if (mWificond == null) {
            Log.e(TAG, "Reference to wifiCond is null");
            return false;
        }

        boolean success;
        try {
            success = mWificond.tearDownClientInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to teardown client interface due to remote exception");
            return false;
        }
        if (!success) {
            Log.e(TAG, "Failed to teardown client interface");
            return false;
        }

        mClientInterfaces.remove(ifaceName);
        mWificondScanners.remove(ifaceName);
        mScanEventHandlers.remove(ifaceName);
        mPnoScanEventHandlers.remove(ifaceName);
        return true;
    }

    /**
    * Setup interface for softAp mode via wificond.
    * @return An IApInterface as wificond Ap interface binder handler.
    * Returns null on failure.
    */
    public IApInterface setupInterfaceForSoftApMode(@NonNull String ifaceName) {
        Log.d(TAG, "Setting up interface for soft ap mode");
        if (!retrieveWificondAndRegisterForDeath()) {
            return null;
        }

        IApInterface apInterface = null;
        try {
            apInterface = mWificond.createApInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IApInterface due to remote exception");
            return null;
        }

        if (apInterface == null) {
            Log.e(TAG, "Could not get IApInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(apInterface.asBinder());

        // Refresh Handlers
        mApInterfaces.put(ifaceName, apInterface);
        return apInterface;
    }

    /**
     * Teardown a specific AP interface configured in wificond.
     *
     * @return Returns true on success.
     */
    public boolean tearDownSoftApInterface(@NonNull String ifaceName) {
        if (getApInterface(ifaceName) == null) {
            Log.e(TAG, "No valid wificond ap interface handler");
            return false;
        }

        if (mWificond == null) {
            Log.e(TAG, "Reference to wifiCond is null");
            return false;
        }

        boolean success;
        try {
            success = mWificond.tearDownApInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to teardown AP interface due to remote exception");
            return false;
        }
        if (!success) {
            Log.e(TAG, "Failed to teardown AP interface");
            return false;
        }
        mApInterfaces.remove(ifaceName);
        mApInterfaceListeners.remove(ifaceName);
        return true;
    }

    /**
    * Teardown all interfaces configured in wificond.
    * @return Returns true on success.
    */
    public boolean tearDownInterfaces() {
        Log.d(TAG, "tearing down interfaces in wificond");
        // Explicitly refresh the wificodn handler because |tearDownInterfaces()|
        // could be used to cleanup before we setup any interfaces.
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }

        try {
            for (Map.Entry<String, IWifiScannerImpl> entry : mWificondScanners.entrySet()) {
                entry.getValue().unsubscribeScanEvents();
                entry.getValue().unsubscribePnoScanEvents();
            }
            mWificond.tearDownInterfaces();
            clearState();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to tear down interfaces due to remote exception");
        }

        return false;
    }

    /** Helper function to look up the interface handle using name */
    private IClientInterface getClientInterface(@NonNull String ifaceName) {
        return mClientInterfaces.get(ifaceName);
    }

    /**
     * Request signal polling to wificond.
     * @param ifaceName Name of the interface.
     * Returns an SignalPollResult object.
     * Returns null on failure.
     */
    public WifiNative.SignalPollResult signalPoll(@NonNull String ifaceName) {
        IClientInterface iface = getClientInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = iface.signalPoll();
            if (resultArray == null || resultArray.length != 4) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        WifiNative.SignalPollResult pollResult = new WifiNative.SignalPollResult();
        pollResult.currentRssi = resultArray[0];
        pollResult.txBitrate = resultArray[1];
        pollResult.associationFrequency = resultArray[2];
        pollResult.rxBitrate = resultArray[3];
        return pollResult;
    }

    /**
     * Fetch TX packet counters on current connection from wificond.
     * @param ifaceName Name of the interface.
     * Returns an TxPacketCounters object.
     * Returns null on failure.
     */
    public WifiNative.TxPacketCounters getTxPacketCounters(@NonNull String ifaceName) {
        IClientInterface iface = getClientInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = iface.getPacketCounters();
            if (resultArray == null || resultArray.length != 2) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        WifiNative.TxPacketCounters counters = new WifiNative.TxPacketCounters();
        counters.txSucceeded = resultArray[0];
        counters.txFailed = resultArray[1];
        return counters;
    }

    /** Helper function to look up the scanner impl handle using name */
    private IWifiScannerImpl getScannerImpl(@NonNull String ifaceName) {
        return mWificondScanners.get(ifaceName);
    }

    private static final int CAPABILITY_SIZE = 16;

    private static BitSet capabilityIntToBitset(int capabilityInt) {
        BitSet capabilityBitSet = new BitSet(CAPABILITY_SIZE);
        for (int i = 0; i < CAPABILITY_SIZE; i++) {
            if ((capabilityInt & (1 << i)) != 0) {
                capabilityBitSet.set(i);
            }
        }
        return capabilityBitSet;
    }

    /**
    * Fetch the latest scan result from kernel via wificond.
    * @param ifaceName Name of the interface.
    * @return Returns an ArrayList of ScanDetail.
    * Returns an empty ArrayList on failure.
    */
    public ArrayList<ScanDetail> getScanResults(@NonNull String ifaceName, int scanType) {
        ArrayList<ScanDetail> results = new ArrayList<>();
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return results;
        }
        try {
            NativeScanResult[] nativeResults;
            if (scanType == SCAN_TYPE_SINGLE_SCAN) {
                nativeResults = scannerImpl.getScanResults();
            } else {
                nativeResults = scannerImpl.getPnoScanResults();
            }
            WifiNative.WifiGenerationCapabilities wifiGenerationCapa = getWifiGenerationCapabilities();
            for (NativeScanResult result : nativeResults) {
                WifiSsid wifiSsid = WifiSsid.createFromByteArray(result.ssid);
                String bssid;
                try {
                    bssid = NativeUtil.macAddressFromByteArray(result.bssid);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + result.bssid, e);
                    continue;
                }
                if (bssid == null) {
                    Log.e(TAG, "Illegal null bssid");
                    continue;
                }
                ScanResult.InformationElement[] ies =
                        InformationElementUtil.parseInformationElements(result.infoElement);
                InformationElementUtil.Capabilities capabilities =
                        new InformationElementUtil.Capabilities();
                if (wifiGenerationCapa != null && result.frequency < 3000) {
                    capabilities.reportHt = wifiGenerationCapa.htSupport2g;
                    capabilities.reportVht = wifiGenerationCapa.vhtSupport2g;
                    capabilities.reportHe = wifiGenerationCapa.staHeSupport2g;
                } else if (wifiGenerationCapa != null) {
                    capabilities.reportHt = wifiGenerationCapa.htSupport5g;
                    capabilities.reportVht = wifiGenerationCapa.vhtSupport5g;
                    capabilities.reportHe = wifiGenerationCapa.staHeSupport5g;
                }
                capabilities.from(ies, capabilityIntToBitset(result.capability),
                        isEnhancedOpenSupported());
                String flags = capabilities.generateCapabilitiesString();
                NetworkDetail networkDetail;
                try {
                    networkDetail = new NetworkDetail(bssid, ies, null, result.frequency);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument for scan result with bssid: " + bssid, e);
                    continue;
                }

                ScanDetail scanDetail = new ScanDetail(networkDetail, wifiSsid, bssid, flags,
                        result.signalMbm / 100, result.frequency, result.tsf, ies, null,
                        result.infoElement);
                ScanResult scanResult = scanDetail.getScanResult();
                // Update carrier network info if this AP's SSID is associated with a carrier Wi-Fi
                // network and it uses EAP.
                if (ScanResultUtil.isScanResultForEapNetwork(scanDetail.getScanResult())
                        && mCarrierNetworkConfig.isCarrierNetwork(wifiSsid.toString())) {
                    scanResult.isCarrierAp = true;
                    scanResult.carrierApEapType =
                            mCarrierNetworkConfig.getNetworkEapType(wifiSsid.toString());
                    scanResult.carrierName =
                            mCarrierNetworkConfig.getCarrierName(wifiSsid.toString());
                }
                // Fill up the radio chain info.
                if (result.radioChainInfos != null) {
                    scanResult.radioChainInfos =
                        new ScanResult.RadioChainInfo[result.radioChainInfos.length];
                    int idx = 0;
                    for (RadioChainInfo nativeRadioChainInfo : result.radioChainInfos) {
                        scanResult.radioChainInfos[idx] = new ScanResult.RadioChainInfo();
                        scanResult.radioChainInfos[idx].id = nativeRadioChainInfo.chainId;
                        scanResult.radioChainInfos[idx].level = nativeRadioChainInfo.level;
                        idx++;
                    }
                }
                results.add(scanDetail);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to create ScanDetail ArrayList");
        }
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "get " + results.size() + " scan results from wificond");
        }

        return results;
    }

    /**
     * Return scan type for the parcelable {@link SingleScanSettings}
     */
    private static int getScanType(int scanType) {
        switch (scanType) {
            case WifiNative.SCAN_TYPE_LOW_LATENCY:
                return IWifiScannerImpl.SCAN_TYPE_LOW_SPAN;
            case WifiNative.SCAN_TYPE_LOW_POWER:
                return IWifiScannerImpl.SCAN_TYPE_LOW_POWER;
            case WifiNative.SCAN_TYPE_HIGH_ACCURACY:
                return IWifiScannerImpl.SCAN_TYPE_HIGH_ACCURACY;
            default:
                throw new IllegalArgumentException("Invalid scan type " + scanType);
        }
    }

    /**
     * HiddenNetwork is an AIDL generated classes, create a wrapper around it to implement a custom
     * equals() method.
     */
    private static final class HiddenNetworkWrapper {
        HiddenNetwork mHiddenNetwork;

        HiddenNetworkWrapper(HiddenNetwork hiddenNetwork) {
            mHiddenNetwork = hiddenNetwork;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof HiddenNetworkWrapper) {
                HiddenNetworkWrapper that = (HiddenNetworkWrapper) o;
                return Arrays.equals(this.mHiddenNetwork.ssid, that.mHiddenNetwork.ssid);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(mHiddenNetwork.ssid);
        }
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param ifaceName Name of the interface.
     * @param scanType Type of scan to perform.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(@NonNull String ifaceName,
                        int scanType,
                        Set<Integer> freqs,
                        List<String> hiddenNetworkSSIDs) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        SingleScanSettings settings = new SingleScanSettings();
        try {
            settings.scanType = getScanType(scanType);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid scan type ", e);
            return false;
        }

        if (freqs == null) {
            settings.channelSettings = new ChannelSettings[0];
        } else {
            settings.channelSettings = freqs.stream()
                    .map(freq -> {
                        ChannelSettings channel = new ChannelSettings();
                        channel.frequency = freq;
                        return channel;
                    })
                    .toArray(ChannelSettings[]::new);
        }

        if (hiddenNetworkSSIDs == null) {
            settings.hiddenNetworks = new HiddenNetwork[0];
        } else {
            settings.hiddenNetworks = hiddenNetworkSSIDs.stream()
                    .flatMap(ssid -> {
                        HiddenNetwork network = new HiddenNetwork();
                        try {
                            network.ssid = NativeUtil.byteArrayFromArrayList(
                                    NativeUtil.decodeSsid(ssid));
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Illegal argument " + ssid, e);
                            return Stream.empty();
                        }
                        if (network.ssid.length > MAX_SSID_LEN) {
                            Log.e(TAG, "SSID is too long after conversion, skipping this ssid! SSID = " +
                                        network.ssid + " , network.ssid.size = " + network.ssid.length);
                            return Stream.empty();
                        }
                        // wrap so that we can use custom equals() method
                        HiddenNetworkWrapper wrapper = new HiddenNetworkWrapper(network);
                        return Stream.of(wrapper);
                    })
                    .distinct() // use custom equals() method to dedupe
                    .map(wrapper -> wrapper.mHiddenNetwork) // unwrap
                    .toArray(HiddenNetwork[]::new);
        }

        try {
            return scannerImpl.scan(settings);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request scan due to remote exception");
        }
        return false;
    }

    /**
     * Start PNO scan.
     * @param ifaceName Name of the interface.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(@NonNull String ifaceName, WifiNative.PnoSettings pnoSettings) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        PnoSettings settings = new PnoSettings();
        settings.intervalMs = pnoSettings.periodInMs;
        settings.min2gRssi = pnoSettings.min24GHzRssi;
        settings.min5gRssi = pnoSettings.min5GHzRssi;
        if (pnoSettings.networkList == null) {
            settings.pnoNetworks = new PnoNetwork[0];
        } else {
            settings.pnoNetworks = Arrays.stream(pnoSettings.networkList)
                    .flatMap(network -> {
                        PnoNetwork condNetwork = new PnoNetwork();
                        condNetwork.isHidden = (network.flags
                                & WifiScanner.PnoSettings.PnoNetwork.FLAG_DIRECTED_SCAN) != 0;
                        try {
                            condNetwork.ssid = NativeUtil.byteArrayFromArrayList(
                                    NativeUtil.decodeSsid(network.ssid));
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Illegal argument " + network.ssid, e);
                            return Stream.empty();
                        }
                        condNetwork.frequencies = network.frequencies;
                        ArrayList<PnoNetwork> networks = new ArrayList<>();
                        if (condNetwork.ssid.length <= WifiGbk.MAX_SSID_LENGTH) { //wifigbk++
                            networks.add(condNetwork);
                        }
                        //wifigbk++
                        if (!WifiGbk.isAllAscii(condNetwork.ssid)) {
                            PnoNetwork condNetwork2 = new PnoNetwork();
                            condNetwork2.isHidden = condNetwork.isHidden;
                            condNetwork2.ssid = WifiGbk.toGbk(condNetwork.ssid);
                            condNetwork2.frequencies = condNetwork.frequencies;
                            if (condNetwork2.ssid != null) {
                                networks.add(condNetwork);
                                Log.i(TAG, "WifiGbk fixed - pnoScan add extra Gbk ssid for " + network.ssid);
                            }
                        }
                        //wifigbk--
                        return networks.stream();
                    })
                    .toArray(PnoNetwork[]::new);
        }

        try {
            boolean success = scannerImpl.startPnoScan(settings);
            mWifiInjector.getWifiMetrics().incrementPnoScanStartAttempCount();
            if (!success) {
                mWifiInjector.getWifiMetrics().incrementPnoScanFailedCount();
            }
            return success;
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to start pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Stop PNO scan.
     * @param ifaceName Name of the interface.
     * @return true on success.
     */
    public boolean stopPnoScan(@NonNull String ifaceName) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        try {
            return scannerImpl.stopPnoScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to stop pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Abort ongoing single scan.
     * @param ifaceName Name of the interface.
     */
    public void abortScan(@NonNull String ifaceName) {
        IWifiScannerImpl scannerImpl = getScannerImpl(ifaceName);
        if (scannerImpl == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return;
        }
        try {
            scannerImpl.abortScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request abortScan due to remote exception");
        }
    }

    /**
     * Query the list of valid frequencies for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * The following bands are supported:
     * WifiScanner.WIFI_BAND_24_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int [] getChannelsForBand(int band) {
        if (mWificond == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return null;
        }
        try {
            switch (band) {
                case WifiScanner.WIFI_BAND_24_GHZ:
                    return mWificond.getAvailable2gChannels();
                case WifiScanner.WIFI_BAND_5_GHZ:
                    return mWificond.getAvailable5gNonDFSChannels();
                case WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY:
                    return mWificond.getAvailableDFSChannels();
                default:
                    throw new IllegalArgumentException("unsupported band " + band);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request getChannelsForBand due to remote exception");
        }
        return null;
    }

    /** Helper function to look up the interface handle using name */
    private IApInterface getApInterface(@NonNull String ifaceName) {
        return mApInterfaces.get(ifaceName);
    }

    /**
     * Register the provided listener for SoftAp events.
     *
     * @param ifaceName Name of the interface.
     * @param listener Callback for AP events.
     * @return true on success, false otherwise.
     */
    public boolean registerApListener(@NonNull String ifaceName, SoftApListener listener) {
        IApInterface iface = getApInterface(ifaceName);
        if (iface == null) {
            Log.e(TAG, "No valid ap interface handler");
            return false;
        }
        try {
            IApInterfaceEventCallback  callback = new ApInterfaceEventCallback(listener);
            mApInterfaceListeners.put(ifaceName, callback);
            boolean success = iface.registerCallback(callback);
            if (!success) {
                Log.e(TAG, "Failed to register ap callback.");
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in registering AP callback: " + e);
            return false;
        }
        return true;
    }

    /**
     * See {@link WifiNative#sendMgmtFrame(String, byte[], SendMgmtFrameCallback, int)}
     */
    public void sendMgmtFrame(@NonNull String ifaceName, @NonNull byte[] frame,
            @NonNull SendMgmtFrameCallback callback, int mcs) {

        if (callback == null) {
            Log.e(TAG, "callback cannot be null!");
            return;
        }

        if (frame == null) {
            Log.e(TAG, "frame cannot be null!");
            callback.onFailure(WifiNative.SEND_MGMT_FRAME_ERROR_UNKNOWN);
            return;
        }

        // TODO (b/112029045) validate mcs
        IClientInterface clientInterface = getClientInterface(ifaceName);
        if (clientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            callback.onFailure(WifiNative.SEND_MGMT_FRAME_ERROR_UNKNOWN);
            return;
        }

        if (!mSendMgmtFrameInProgress.compareAndSet(false, true)) {
            Log.e(TAG, "An existing management frame transmission is in progress!");
            callback.onFailure(WifiNative.SEND_MGMT_FRAME_ERROR_ALREADY_STARTED);
            return;
        }

        SendMgmtFrameEvent sendMgmtFrameEvent = new SendMgmtFrameEvent(callback);
        try {
            clientInterface.SendMgmtFrame(frame, sendMgmtFrameEvent, mcs);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while starting link probe: " + e);
            // Call sendMgmtFrameEvent.OnFailure() instead of callback.onFailure() so that
            // sendMgmtFrameEvent can clean up internal state, such as cancelling the timer.
            sendMgmtFrameEvent.OnFailure(WifiNative.SEND_MGMT_FRAME_ERROR_UNKNOWN);
        }
    }

    /**
     * Clear all internal handles.
     */
    private void clearState() {
        // Refresh handlers
        mClientInterfaces.clear();
        mWificondScanners.clear();
        mPnoScanEventHandlers.clear();
        mScanEventHandlers.clear();
        mApInterfaces.clear();
        mApInterfaceListeners.clear();
        mSendMgmtFrameInProgress.set(false);
    }

    /**
     * Check if OWE (Enhanced Open) is supported on the device
     *
     * @return true if OWE is supported
     */
    private boolean isEnhancedOpenSupported() {
        if (mIsEnhancedOpenSupportedInitialized) {
            return mIsEnhancedOpenSupported;
        }

        // WifiNative handle might be null, check this here
        if (mWifiNative == null) {
            mWifiNative = mWifiInjector.getWifiNative();
            if (mWifiNative == null) {
                return false;
            }
        }

        String iface = mWifiNative.getClientInterfaceName();
        if (iface == null) {
            // Client interface might not be initialized during boot or Wi-Fi off
            return false;
        }

        mIsEnhancedOpenSupportedInitialized = true;
        mIsEnhancedOpenSupported = (mWifiNative.getSupportedFeatureSet(iface)
                & WIFI_FEATURE_OWE) != 0;
        return mIsEnhancedOpenSupported;
    }

    /**
     * Query the Wi-Fi generation capabilities for 2G and 5G bands.
     *
     * @return WifiNative.WifiGenerationCapabilities object, or null for error.
     */
    public WifiNative.WifiGenerationCapabilities getWifiGenerationCapabilities() {
        if (!retrieveWificondAndRegisterForDeath()) {
            return null;
        }

        int wifiGenerationCapaMask = 0;
        try {
            wifiGenerationCapaMask = mWificond.QcGetWifiGenerationCapabilities();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request getWifiGenerationCapabilities due to remote exception");
            return null;
        }

        if (wifiGenerationCapaMask == -1) {
            Log.e(TAG, "Failed to get Wifi generation capabilities.");
            return null;
        }

	WifiNative.WifiGenerationCapabilities wifiGenerationCapa = new WifiNative.WifiGenerationCapabilities();

        if ((wifiGenerationCapaMask & (1 << IWificond.QC_2G_HT_SUPPORT)) != 0) {
            wifiGenerationCapa.htSupport2g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_2G_VHT_SUPPORT)) != 0) {
            wifiGenerationCapa.vhtSupport2g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_2G_STA_HE_SUPPORT)) != 0) {
            wifiGenerationCapa.staHeSupport2g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_2G_SAP_HE_SUPPORT)) != 0) {
            wifiGenerationCapa.sapHeSupport2g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_5G_HT_SUPPORT)) != 0) {
            wifiGenerationCapa.htSupport5g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_5G_VHT_SUPPORT)) != 0) {
            wifiGenerationCapa.vhtSupport5g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_5G_STA_HE_SUPPORT)) != 0) {
            wifiGenerationCapa.staHeSupport5g = true;
        }
        if ((wifiGenerationCapaMask & (1 << IWificond.QC_5G_SAP_HE_SUPPORT)) != 0) {
            wifiGenerationCapa.sapHeSupport5g = true;
        }

        return wifiGenerationCapa;
    }
}
