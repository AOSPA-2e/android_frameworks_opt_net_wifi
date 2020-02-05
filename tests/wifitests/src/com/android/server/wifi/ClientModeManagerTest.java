/*
 * Copyright 2018 The Android Open Source Project
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

import static android.net.wifi.WifiManager.EXTRA_PREVIOUS_WIFI_STATE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_STATE;
import static android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLED;
import static android.net.wifi.WifiManager.WIFI_STATE_ENABLING;
import static android.net.wifi.WifiManager.WIFI_STATE_UNKNOWN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

import android.app.test.MockAnswerUtil.AnswerWithArguments;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.ims.ImsMmTelManager;
import android.telephony.ims.RegistrationManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Unit tests for {@link ClientModeManager}.
 */
@SmallTest
public class ClientModeManagerTest extends WifiBaseTest {
    private static final String TAG = "ClientModeManagerTest";
    private static final String TEST_INTERFACE_NAME = "testif0";
    private static final String OTHER_INTERFACE_NAME = "notTestIf";
    private static final int TEST_WIFI_OFF_DEFERRING_TIME_MS = 4000;

    TestLooper mLooper;

    ClientModeManager mClientModeManager;

    @Mock Context mContext;
    @Mock WifiMetrics mWifiMetrics;
    @Mock WifiNative mWifiNative;
    @Mock ClientModeManager.Listener mListener;
    @Mock SarManager mSarManager;
    @Mock WakeupController mWakeupController;
    @Mock ClientModeImpl mClientModeImpl;
    @Mock TelephonyManager mTelephonyManager;
    @Mock CarrierConfigManager mCarrierConfigManager;
    @Mock PersistableBundle mCarrierConfigBundle;
    @Mock ImsMmTelManager mImsMmTelManager;
    private RegistrationManager.RegistrationCallback mImsMmTelManagerRegistrationCallback = null;

    private MockitoSession mStaticMockSession = null;

    final ArgumentCaptor<WifiNative.InterfaceCallback> mInterfaceCallbackCaptor =
            ArgumentCaptor.forClass(WifiNative.InterfaceCallback.class);

    /**
     * If mContext is reset, call it again to ensure system services could be retrieved
     * from the context.
     */
    private void setUpSystemServiceForContext() {
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        setUpSystemServiceForContext();

        /**
         * default mock for IMS deregistration:
         * * No wifi calling
         * * No network
         * * no deferring time for wifi off
         */
        mStaticMockSession = mockitoSession()
            .mockStatic(ImsMmTelManager.class)
            .mockStatic(SubscriptionManager.class)
            .startMocking();
        lenient().when(ImsMmTelManager.createForSubscriptionId(anyInt()))
                .thenReturn(mImsMmTelManager);
        lenient().when(SubscriptionManager.getDefaultVoiceSubscriptionId())
                .thenReturn(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);
        doAnswer(new AnswerWithArguments() {
            public void answer(Executor executor, RegistrationManager.RegistrationCallback c) {
                mImsMmTelManagerRegistrationCallback = c;
            }
        }).when(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        doAnswer(new AnswerWithArguments() {
            public void answer(RegistrationManager.RegistrationCallback c) {
                if (mImsMmTelManagerRegistrationCallback == c) {
                    mImsMmTelManagerRegistrationCallback = null;
                }
            }
        }).when(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        when(mImsMmTelManager.isAvailable(anyInt(), anyInt())).thenReturn(false);

        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);
        when(mTelephonyManager.getVoiceNetworkType())
                .thenReturn(TelephonyManager.NETWORK_TYPE_UNKNOWN);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mCarrierConfigBundle);
        when(mCarrierConfigBundle
                .getInt(eq(CarrierConfigManager.Ims.KEY_WIFI_OFF_DEFERRING_TIME_INT)))
                .thenReturn(0);

        mLooper = new TestLooper();
        mClientModeManager = createClientModeManager();
        mLooper.dispatchAll();
    }

    @After
    public void cleanUp() throws Exception {
        mStaticMockSession.finishMocking();
    }

    private ClientModeManager createClientModeManager() {
        return new ClientModeManager(mContext, mLooper.getLooper(), mWifiNative, mListener,
                mWifiMetrics, mSarManager, mWakeupController, mClientModeImpl);
    }

    private void startClientInScanOnlyModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.setupInterfaceForClientInScanMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);
        mClientModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).setupInterfaceForClientInScanMode(
                mInterfaceCallbackCaptor.capture());
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager).setScanOnlyWifiState(WIFI_STATE_ENABLED);

        // now mark the interface as up
        mInterfaceCallbackCaptor.getValue().onUp(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        // Ensure that no public broadcasts were sent.
        verifyNoMoreInteractions(mContext);
        verify(mListener).onStarted();
    }

    private void startClientInConnectModeAndVerifyEnabled() throws Exception {
        when(mWifiNative.setupInterfaceForClientInScanMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.switchClientInterfaceToConnectivityMode(any()))
                .thenReturn(true);
        mClientModeManager.start();
        mLooper.dispatchAll();

        verify(mWifiNative).setupInterfaceForClientInScanMode(
                mInterfaceCallbackCaptor.capture());
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        mLooper.dispatchAll();

        verify(mWifiNative).switchClientInterfaceToConnectivityMode(TEST_INTERFACE_NAME);
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.CONNECT_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager).setClientWifiState(WIFI_STATE_ENABLED);

        // now mark the interface as up
        mInterfaceCallbackCaptor.getValue().onUp(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        Log.d(TAG, "captured intents: " + intents);
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_ENABLING,
                WIFI_STATE_DISABLED);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_ENABLED,
                WIFI_STATE_ENABLING);

        verify(mListener, times(2)).onStarted();
    }

    private void checkWifiConnectModeStateChangedBroadcast(
            Intent intent, int expectedCurrentState, int expectedPrevState) {
        String action = intent.getAction();
        assertEquals(WIFI_STATE_CHANGED_ACTION, action);
        int currentState = intent.getIntExtra(EXTRA_WIFI_STATE, WIFI_STATE_UNKNOWN);
        assertEquals(expectedCurrentState, currentState);
        int prevState = intent.getIntExtra(EXTRA_PREVIOUS_WIFI_STATE, WIFI_STATE_UNKNOWN);
        assertEquals(expectedPrevState, prevState);

        verify(mClientModeImpl, atLeastOnce()).setWifiStateForApiCalls(expectedCurrentState);
    }

    private void verifyConnectModeNotificationsForCleanShutdown(int fromState) {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce())
                .sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertTrue(intents.size() >= 2);
        checkWifiConnectModeStateChangedBroadcast(intents.get(intents.size() - 2),
                WIFI_STATE_DISABLING, fromState);
        checkWifiConnectModeStateChangedBroadcast(intents.get(intents.size() - 1),
                WIFI_STATE_DISABLED, WIFI_STATE_DISABLING);
    }

    private void verifyConnectModeNotificationsForFailure() {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce())
                .sendStickyBroadcastAsUser(intentCaptor.capture(), eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_DISABLING,
                WIFI_STATE_UNKNOWN);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_DISABLED,
                WIFI_STATE_DISABLING);
    }

    /**
     * ClientMode start sets up an interface in ClientMode.
     */
    @Test
    public void clientInConnectModeStartCreatesClientInterface() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
    }

    /**
     * ClientMode start sets up an interface in ClientMode.
     */
    @Test
    public void clientInScanOnlyModeStartCreatesClientInterface() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();
    }

    /**
     * Switch ClientModeManager from ScanOnly mode To Connect mode.
     */
    @Test
    public void switchFromScanOnlyModeToConnectMode() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();

        when(mWifiNative.switchClientInterfaceToConnectivityMode(any()))
                .thenReturn(true);
        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        mLooper.dispatchAll();

        verify(mSarManager).setScanOnlyWifiState(WIFI_STATE_DISABLED);
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        verify(mClientModeImpl).setOperationalMode(
                ClientModeImpl.CONNECT_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager).setClientWifiState(WIFI_STATE_ENABLED);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));

        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        Log.d(TAG, "captured intents: " + intents);
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_ENABLING,
                WIFI_STATE_DISABLED);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_ENABLED,
                WIFI_STATE_ENABLING);

        verify(mListener, times(2)).onStarted();
    }

    /**
     * Switch ClientModeManager from Connect mode to ScanOnly mode.
     */
    @Test
    public void switchFromConnectModeToScanOnlyMode() throws Exception {
        startClientInConnectModeAndVerifyEnabled();

        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);
        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        verify(mSarManager).setClientWifiState(WIFI_STATE_DISABLED);
        verify(mWifiNative).setupInterfaceForClientInScanMode(
                mInterfaceCallbackCaptor.capture());
        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mClientModeImpl, times(2)).setOperationalMode(
                ClientModeImpl.SCAN_ONLY_MODE, TEST_INTERFACE_NAME);
        verify(mSarManager, times(2)).setScanOnlyWifiState(WIFI_STATE_ENABLED);

        verify(mImsMmTelManager, never()).registerImsRegistrationCallback(any(), any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // Ensure that no public broadcasts were sent.
        verifyNoMoreInteractions(mContext);
        verify(mListener, times(3)).onStarted();
    }

    /**
     * ClientMode increments failure metrics when failing to setup client mode in connectivity mode.
     */
    @Test
    public void detectAndReportErrorWhenSetupForClientInConnectivityModeWifiNativeFailure()
            throws Exception {
        when(mWifiNative.setupInterfaceForClientInScanMode(any()))
                .thenReturn(TEST_INTERFACE_NAME);
        when(mWifiNative.switchClientInterfaceToConnectivityMode(any())).thenReturn(false);

        mClientModeManager.start();
        mLooper.dispatchAll();

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_PRIMARY);
        mLooper.dispatchAll();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mContext, atLeastOnce()).sendStickyBroadcastAsUser(intentCaptor.capture(),
                eq(UserHandle.ALL));
        List<Intent> intents = intentCaptor.getAllValues();
        assertEquals(2, intents.size());
        checkWifiConnectModeStateChangedBroadcast(intents.get(0), WIFI_STATE_ENABLING,
                WIFI_STATE_DISABLED);
        checkWifiConnectModeStateChangedBroadcast(intents.get(1), WIFI_STATE_DISABLED,
                WIFI_STATE_UNKNOWN);
        verify(mListener).onStartFailure();
    }

    /**
     * Calling ClientModeManager.start twice does not crash or restart client mode.
     */
    @Test
    public void clientModeStartCalledTwice() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mWifiNative, mContext);
        mClientModeManager.start();
        mLooper.dispatchAll();
        verifyNoMoreInteractions(mWifiNative, mContext);
    }

    /**
     * ClientMode stop properly cleans up state
     */
    @Test
    public void clientModeStopCleansUpState() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verify(mImsMmTelManager, never()).registerImsRegistrationCallback(any(), any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * Calling stop when ClientMode is not started should not send scan state updates
     */
    @Test
    public void clientModeStopWhenNotStartedDoesNotUpdateScanStateUpdates() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        reset(mContext, mListener);
        setUpSystemServiceForContext();
        // now call stop again
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
        verifyNoMoreInteractions(mListener);
    }

    /**
     * Triggering interface down when ClientMode is active properly exits the active state.
     */
    @Test
    public void clientModeStartedStopsWhenInterfaceDown() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        setUpSystemServiceForContext();
        when(mClientModeImpl.isConnectedMacRandomizationEnabled()).thenReturn(false);
        mInterfaceCallbackCaptor.getValue().onDown(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verify(mClientModeImpl).failureDetected(eq(SelfRecovery.REASON_STA_IFACE_DOWN));
        verifyConnectModeNotificationsForFailure();
        verify(mListener).onStopped();
    }

    /**
     * Triggering interface down when ClientMode is active and Connected MacRandomization is enabled
     * does not exit the active state.
     */
    @Test
    public void clientModeStartedWithConnectedMacRandDoesNotStopWhenInterfaceDown()
            throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        setUpSystemServiceForContext();
        when(mClientModeImpl.isConnectedMacRandomizationEnabled()).thenReturn(true);
        mInterfaceCallbackCaptor.getValue().onDown(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verify(mClientModeImpl, never()).failureDetected(eq(SelfRecovery.REASON_STA_IFACE_DOWN));
        verify(mContext, never()).sendStickyBroadcastAsUser(any(), any());
    }

    /**
     * Testing the handling of an interface destroyed notification.
     */
    @Test
    public void clientModeStartedStopsOnInterfaceDestroyed() throws Exception {
        startClientInConnectModeAndVerifyEnabled();
        reset(mContext);
        setUpSystemServiceForContext();
        mInterfaceCallbackCaptor.getValue().onDestroyed(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);
        verify(mClientModeImpl).handleIfaceDestroyed();
        verify(mListener).onStopped();
    }

    /**
     * Verify that onDestroyed after client mode is stopped doesn't trigger a callback.
     */
    @Test
    public void noCallbackOnInterfaceDestroyedWhenAlreadyStopped() throws Exception {
        startClientInConnectModeAndVerifyEnabled();

        reset(mListener);

        mClientModeManager.stop();
        mLooper.dispatchAll();

        // now trigger interface destroyed and make sure callback doesn't get called
        mInterfaceCallbackCaptor.getValue().onDestroyed(TEST_INTERFACE_NAME);
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verifyNoMoreInteractions(mListener);
        verify(mClientModeImpl, never()).handleIfaceDestroyed();
    }

    /**
     * Entering ScanOnly state starts the WakeupController.
     */
    @Test
    public void scanModeEnterStartsWakeupController() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();

        verify(mWakeupController).start();
    }

    /**
     * Exiting ScanOnly state stops the WakeupController.
     */
    @Test
    public void scanModeExitStopsWakeupController() throws Exception {
        startClientInScanOnlyModeAndVerifyEnabled();

        mClientModeManager.stop();
        mLooper.dispatchAll();

        InOrder inOrder = inOrder(mWakeupController, mWifiNative, mListener);

        inOrder.verify(mListener).onStarted();
        inOrder.verify(mWakeupController).start();
        inOrder.verify(mWakeupController).stop();
        inOrder.verify(mWifiNative).teardownInterface(eq(TEST_INTERFACE_NAME));
    }

    private void setUpVoWifiTest(
            boolean isWifiCallingAvailable,
            int voiceNetworkType,
            int wifiOffDeferringTimeMs) {
        when(mImsMmTelManager.isAvailable(anyInt(), anyInt())).thenReturn(isWifiCallingAvailable);
        when(mTelephonyManager.getVoiceNetworkType())
                .thenReturn(voiceNetworkType);
        when(mCarrierConfigBundle
                .getInt(eq(CarrierConfigManager.Ims.KEY_WIFI_OFF_DEFERRING_TIME_INT)))
                .thenReturn(wifiOffDeferringTimeMs);
    }

    /**
     * ClientMode stop properly with IMS deferring time without WifiCalling.
     */
    @Test
    public void clientModeStopWithWifiOffDeferringTimeNoWifiCalling() throws Exception {
        setUpVoWifiTest(false,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        verify(mImsMmTelManager, never()).registerImsRegistrationCallback(any(), any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * ClientMode stop properly with IMS deferring time, Wifi calling, and LTE.
     */
    @Test
    public void clientModeStopWithWifiOffDeferringTimeWithWifiCallingAndLte() throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_LTE,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mListener).onStopped();

        verify(mImsMmTelManager, never()).registerImsRegistrationCallback(any(), any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * ClientMode stop properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is done before reaching the timeout.
     */
    @Test
    public void clientModeStopWithWifiOffDeferringTimeAndWifiCallingNoLteOnImsUnregistered()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();

        // Not yet finish IMS deregistration.
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
        verify(mListener, never()).onStopped();

        // Notify wifi service IMS service is de-registered.
        assertNotNull(mImsMmTelManagerRegistrationCallback);
        mImsMmTelManagerRegistrationCallback.onUnregistered(null);
        mLooper.dispatchAll();

        // Now Wifi could be turned off actually.
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
        verify(mListener).onStopped();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * ClientMode stop properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is done before reaching the timeout.
     */
    @Test
    public void clientModeStopWithWifiOffDeferringTimeAndWifiCallingNoLteOnImsRegistered()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();

        // Not yet finish IMS deregistration.
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
        verify(mListener, never()).onStopped();

        // Notify wifi service IMS service is de-registered.
        assertNotNull(mImsMmTelManagerRegistrationCallback);
        mImsMmTelManagerRegistrationCallback.onRegistered(0);
        mLooper.dispatchAll();

        // Now Wifi could be turned off actually.
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
        verify(mListener).onStopped();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * ClientMode stop properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is NOT done before reaching the timeout.
     */
    @Test
    public void clientModeStopWithWifiOffDeferringTimeAndWifiCallingNoLteTimedOut()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
        verify(mListener, never()).onStopped();

        // 1/2 deferring time passed, should be still waiting for the callback.
        mLooper.moveTimeForward(TEST_WIFI_OFF_DEFERRING_TIME_MS / 2);
        mLooper.dispatchAll();
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
        verify(mListener, never()).onStopped();

        // Exceeding the timeout, wifi should be stopped.
        mLooper.moveTimeForward(TEST_WIFI_OFF_DEFERRING_TIME_MS / 2 + 1000);
        mLooper.dispatchAll();
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
        verify(mListener).onStopped();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * ClientMode stop properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is NOT done before reaching the timeout with multiple stop calls.
     */
    @Test
    public void clientModeStopWithWifiOffDeferringTimeAndWifiCallingNoLteTimedOutMultipleStop()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        mClientModeManager.stop();
        mLooper.dispatchAll();
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
        verify(mListener, never()).onStopped();

        mClientModeManager.stop();
        mLooper.dispatchAll();
        // should not register another listener.
        verify(mImsMmTelManager, times(1)).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
        verify(mListener, never()).onStopped();

        // Exceeding the timeout, wifi should be stopped.
        mLooper.moveTimeForward(TEST_WIFI_OFF_DEFERRING_TIME_MS + 1000);
        mLooper.dispatchAll();
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
        verify(mListener).onStopped();

        verifyConnectModeNotificationsForCleanShutdown(WIFI_STATE_ENABLED);

        // on an explicit stop, we should not trigger the callback
        verifyNoMoreInteractions(mListener);
    }

    /**
     * Switch to scan mode properly with IMS deferring time without WifiCalling.
     */
    @Test
    public void switchToScanOnlyModeWithWifiOffDeferringTimeNoWifiCalling() throws Exception {
        setUpVoWifiTest(false,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mImsMmTelManager, never()).registerImsRegistrationCallback(any(), any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
    }

    /**
     * Switch to scan mode properly with IMS deferring time, Wifi calling, and LTE.
     */
    @Test
    public void
            switchToScanOnlyModeWithWifiOffDeferringTimeAndWifiCallingAndLte() throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_LTE,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mImsMmTelManager, never()).registerImsRegistrationCallback(any(), any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());
    }

    /**
     * Switch to scan mode properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is done before reaching the timeout.
     */
    @Test
    public void switchToScanOnlyModeWithWifiOffDeferringTimeAndWifiCallingNoLteOnImsUnregistered()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        // Not yet finish IMS deregistration.
        verify(mWifiNative, never()).switchClientInterfaceToScanMode(any());
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // Notify wifi service IMS service is de-registered.
        assertNotNull(mImsMmTelManagerRegistrationCallback);
        mImsMmTelManagerRegistrationCallback.onUnregistered(null);
        mLooper.dispatchAll();

        // Now Wifi could be switched to scan mode actually.
        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
    }

    /**
     * Switch to scan mode properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is done before reaching the timeout.
     */
    @Test
    public void switchToScanOnlyModeWithWifiOffDeferringTimeAndWifiCallingNoLteOnImsRegistered()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        // Not yet finish IMS deregistration.
        verify(mWifiNative, never()).switchClientInterfaceToScanMode(any());
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // Notify wifi service IMS service is de-registered.
        assertNotNull(mImsMmTelManagerRegistrationCallback);
        mImsMmTelManagerRegistrationCallback.onRegistered(0);
        mLooper.dispatchAll();

        // Now Wifi could be switched to scan mode actually.
        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
    }

    /**
     * Switch to scan mode properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is NOT done before reaching the timeout.
     */
    @Test
    public void switchToScanOnlyModeWithWifiOffDeferringTimeAndWifiCallingNoLteTimedOut()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        verify(mWifiNative, never()).switchClientInterfaceToScanMode(any());
        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // 1/2 deferring time passed, should be still waiting for the callback.
        mLooper.moveTimeForward(TEST_WIFI_OFF_DEFERRING_TIME_MS / 2);
        mLooper.dispatchAll();
        verify(mWifiNative, never()).switchClientInterfaceToScanMode(any());
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // Exceeding the timeout, wifi should be stopped.
        mLooper.moveTimeForward(TEST_WIFI_OFF_DEFERRING_TIME_MS / 2 + 1000);
        mLooper.dispatchAll();
        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
    }

    /**
     * Switch to scan mode properly with IMS deferring time and Wifi calling, but no LTE.
     *
     * IMS deregistration is NOT done before reaching the timeout with multiple stop calls.
     */
    @Test
    public void
            switchToScanOnlyModeWithWifiOffDeferringTimeAndWifiCallingNoLteTimedOutMultipleSwitch()
            throws Exception {
        setUpVoWifiTest(true,
                TelephonyManager.NETWORK_TYPE_UNKNOWN,
                TEST_WIFI_OFF_DEFERRING_TIME_MS);

        startClientInConnectModeAndVerifyEnabled();
        reset(mContext, mListener);
        setUpSystemServiceForContext();
        when(mWifiNative.switchClientInterfaceToScanMode(any()))
                .thenReturn(true);

        mClientModeManager.setRole(ActiveModeManager.ROLE_CLIENT_SCAN_ONLY);
        mLooper.dispatchAll();

        verify(mImsMmTelManager).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        mClientModeManager.stop();
        mLooper.dispatchAll();
        // should not register another listener.
        verify(mWifiNative, never()).switchClientInterfaceToScanMode(any());
        verify(mImsMmTelManager, times(1)).registerImsRegistrationCallback(
                any(Executor.class),
                any(RegistrationManager.RegistrationCallback.class));
        verify(mImsMmTelManager, never()).unregisterImsRegistrationCallback(any());

        // Exceeding the timeout, wifi should be stopped.
        mLooper.moveTimeForward(TEST_WIFI_OFF_DEFERRING_TIME_MS + 1000);
        mLooper.dispatchAll();
        verify(mWifiNative).switchClientInterfaceToScanMode(TEST_INTERFACE_NAME);
        verify(mImsMmTelManager).unregisterImsRegistrationCallback(
                any(RegistrationManager.RegistrationCallback.class));
        assertNull(mImsMmTelManagerRegistrationCallback);
    }
}
