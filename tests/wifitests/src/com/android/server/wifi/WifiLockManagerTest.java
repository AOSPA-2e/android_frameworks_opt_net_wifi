/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.WorkSource;
import android.support.test.filters.SmallTest;

import com.android.internal.app.IBatteryStats;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Unit tests for {@link WifiLockManager}. */
@SmallTest
public class WifiLockManagerTest {

    private static final int DEFAULT_TEST_UID_1 = 52;
    private static final int DEFAULT_TEST_UID_2 = 53;
    private static final int DEFAULT_TEST_UID_3 = 54;
    private static final int DEFAULT_TEST_UID_4 = 55;
    private static final int WIFI_LOCK_MODE_INVALID = -1;
    private static final String TEST_WIFI_LOCK_TAG = "TestTag";

    WifiLockManager mWifiLockManager;
    @Mock IBatteryStats mBatteryStats;
    @Mock IBinder mBinder;
    @Mock IBinder mBinder2;
    WorkSource mWorkSource;
    WorkSource mChainedWorkSource;
    @Mock Context mContext;
    @Mock ClientModeImpl mClientModeImpl;

    /**
     * Method to setup a WifiLockManager for the tests.
     * The WifiLockManager uses mocks for BatteryStats and Context.
     */
    @Before
    public void setUp() {
        mWorkSource = new WorkSource(DEFAULT_TEST_UID_1);
        mChainedWorkSource = new WorkSource();
        mChainedWorkSource.createWorkChain()
                .addNode(DEFAULT_TEST_UID_1, "tag1")
                .addNode(DEFAULT_TEST_UID_2, "tag2");

        MockitoAnnotations.initMocks(this);
        mWifiLockManager = new WifiLockManager(mContext, mBatteryStats,
                mClientModeImpl);
    }

    private void acquireWifiLockSuccessful(int lockMode, String tag, IBinder binder, WorkSource ws)
            throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);

        assertTrue(mWifiLockManager.acquireWifiLock(lockMode, tag, binder, ws));
        assertThat(mWifiLockManager.getStrongestLockMode(),
                not(WifiManager.WIFI_MODE_NO_LOCKS_HELD));
        InOrder inOrder = inOrder(binder, mBatteryStats);
        inOrder.verify(binder).linkToDeath(deathRecipient.capture(), eq(0));
        inOrder.verify(mBatteryStats).noteFullWifiLockAcquiredFromSource(ws);
    }

    private void releaseWifiLockSuccessful(IBinder binder) throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> deathRecipient =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);

        assertTrue(mWifiLockManager.releaseWifiLock(binder));
        InOrder inOrder = inOrder(binder, mBatteryStats);
        inOrder.verify(binder).unlinkToDeath(deathRecipient.capture(), eq(0));
        inOrder.verify(mBatteryStats).noteFullWifiLockReleasedFromSource(any(WorkSource.class));
    }

    /**
     * Test to check that a new WifiLockManager should not be holding any locks.
     */
    @Test
    public void newWifiLockManagerShouldNotHaveAnyLocks() {
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD, mWifiLockManager.getStrongestLockMode());
    }

    /**
     * Test to verify that the lock mode is verified before adding a lock.
     *
     * Steps: call acquireWifiLock with an invalid lock mode.
     * Expected: the call should throw an IllegalArgumentException.
     */
    @Test(expected = IllegalArgumentException.class)
    public void acquireWifiLockShouldThrowExceptionOnInivalidLockMode() throws Exception {
        mWifiLockManager.acquireWifiLock(WIFI_LOCK_MODE_INVALID, "", mBinder, mWorkSource);
    }

    /**
     * Test that a call to acquireWifiLock with valid parameters works.
     *
     * Steps: call acquireWifiLock on the empty WifiLockManager.
     * Expected: A subsequent call to getStrongestLockMode should reflect the type of the lock we
     * just added
     */
    @Test
    public void acquireWifiLockWithValidParamsShouldSucceed() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "", mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL, mWifiLockManager.getStrongestLockMode());
    }

    /**
     * Test that a call to acquireWifiLock will not succeed if there is already a lock for the same
     * binder instance.
     *
     * Steps: call acquireWifiLock twice
     * Expected: Second call should return false
     */
    @Test
    public void secondCallToAcquireWifiLockWithSameBinderShouldFail() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_SCAN_ONLY, "", mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY, mWifiLockManager.getStrongestLockMode());
        assertFalse(mWifiLockManager.acquireWifiLock(
                WifiManager.WIFI_MODE_SCAN_ONLY, "", mBinder, mWorkSource));
    }

    /**
     * After acquiring a lock, we should be able to remove it.
     *
     * Steps: acquire a WifiLock and then remove it.
     * Expected: Since a single lock was added, removing it should leave the WifiLockManager without
     * any locks.  We should not see any errors.
     */
    @Test
    public void releaseWifiLockShouldSucceed() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "", mBinder, mWorkSource);
        releaseWifiLockSuccessful(mBinder);
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD, mWifiLockManager.getStrongestLockMode());
    }

    /**
     * Releasing locks for one caller should not release locks for a different caller.
     *
     * Steps: acquire locks for two callers and remove locks for one.
     * Expected: locks for remaining caller should still be active.
     */
    @Test
    public void releaseLocksForOneCallerNotImpactOtherCallers() throws Exception {
        IBinder toReleaseBinder = mock(IBinder.class);
        WorkSource toReleaseWS = new WorkSource(DEFAULT_TEST_UID_1);
        WorkSource toKeepWS = new WorkSource(DEFAULT_TEST_UID_2);

        acquireWifiLockSuccessful(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", toReleaseBinder, toReleaseWS);
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_SCAN_ONLY, "", mBinder, toKeepWS);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, mWifiLockManager.getStrongestLockMode());
        releaseWifiLockSuccessful(toReleaseBinder);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY, mWifiLockManager.getStrongestLockMode());
        releaseWifiLockSuccessful(mBinder);
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD, mWifiLockManager.getStrongestLockMode());
    }

    /**
     * Attempting to release a lock that we do not hold should return false.
     *
     * Steps: release a WifiLock
     * Expected: call to releaseWifiLock should return false.
     */
    @Test
    public void releaseWifiLockWithoutAcquireWillReturnFalse() {
        assertFalse(mWifiLockManager.releaseWifiLock(mBinder));
    }

    /**
     * Test used to verify call for getStrongestLockMode.
     *
     * Steps: The test first checks the return value for no held locks and then proceeds to test
     * with a single lock of each type.
     * Expected: getStrongestLockMode should reflect the type of lock we just added.
     */
    @Test
    public void checkForProperValueForGetStrongestLockMode() throws Exception {
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD, mWifiLockManager.getStrongestLockMode());

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, mWifiLockManager.getStrongestLockMode());
        releaseWifiLockSuccessful(mBinder);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "", mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL, mWifiLockManager.getStrongestLockMode());
        releaseWifiLockSuccessful(mBinder);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_SCAN_ONLY, "", mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY, mWifiLockManager.getStrongestLockMode());
    }

    /**
     * We should be able to create a merged WorkSource holding WorkSources for all active locks.
     *
     * Steps: call createMergedWorkSource and verify it is empty, add a lock and call again, it
     * should have one entry.
     * Expected: the first call should return a worksource with size 0 and the second should be size
     * 1.
     */
    @Test
    public void createMergedWorkSourceShouldSucceed() throws Exception {
        WorkSource checkMWS = mWifiLockManager.createMergedWorkSource();
        assertEquals(0, checkMWS.size());

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder, mWorkSource);
        checkMWS = mWifiLockManager.createMergedWorkSource();
        assertEquals(1, checkMWS.size());
    }

    /**
     * Checks that WorkChains are preserved when merged WorkSources are created.
     */
    @Test
    public void createMergedworkSourceWithChainsShouldSucceed() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder, mWorkSource);
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder2,
                mChainedWorkSource);

        WorkSource merged = mWifiLockManager.createMergedWorkSource();
        assertEquals(1, merged.size());
        assertEquals(1, merged.getWorkChains().size());
    }

    /**
     * A smoke test for acquiring, updating and releasing WifiLocks with chained WorkSources.
     */
    @Test
    public void smokeTestLockLifecycleWithChainedWorkSource() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder,
                mChainedWorkSource);

        WorkSource updated = new WorkSource();
        updated.set(mChainedWorkSource);
        updated.createWorkChain().addNode(
                DEFAULT_TEST_UID_1, "chain2");

        mWifiLockManager.updateWifiLockWorkSource(mBinder, updated);
        InOrder inOrder = inOrder(mBatteryStats);
        inOrder.verify(mBatteryStats).noteFullWifiLockAcquiredFromSource(eq(updated));
        inOrder.verify(mBatteryStats).noteFullWifiLockReleasedFromSource(mChainedWorkSource);

        releaseWifiLockSuccessful(mBinder);
    }

    /**
     * Test the ability to update a WifiLock WorkSource with a new WorkSource.
     *
     * Steps: acquire a WifiLock with the default test worksource, then attempt to update it.
     * Expected: Verify calls to release the original WorkSource and acquire with the new one to
     * BatteryStats.
     */
    @Test
    public void testUpdateWifiLockWorkSourceCalledWithWorkSource() throws Exception {
        WorkSource newWorkSource = new WorkSource(DEFAULT_TEST_UID_2);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder, mWorkSource);

        mWifiLockManager.updateWifiLockWorkSource(mBinder, newWorkSource);
        InOrder inOrder = inOrder(mBatteryStats);
        inOrder.verify(mBatteryStats).noteFullWifiLockAcquiredFromSource(eq(newWorkSource));
        inOrder.verify(mBatteryStats).noteFullWifiLockReleasedFromSource(mWorkSource);
    }

    /**
     * Test the ability to update a WifiLock WorkSource with the callers UID.
     *
     * Steps: acquire a WifiLock with the default test worksource, then attempt to update it.
     * Expected: Verify calls to release the original WorkSource and acquire with the new one to
     * BatteryStats.
     */
    @Test
    public void testUpdateWifiLockWorkSourceCalledWithUID()  throws Exception {
        WorkSource newWorkSource = new WorkSource(Binder.getCallingUid());

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder, mWorkSource);

        mWifiLockManager.updateWifiLockWorkSource(mBinder, null);
        InOrder inOrder = inOrder(mBatteryStats);
        inOrder.verify(mBatteryStats).noteFullWifiLockAcquiredFromSource(eq(newWorkSource));
        inOrder.verify(mBatteryStats).noteFullWifiLockReleasedFromSource(mWorkSource);
    }

    /**
     * Test an attempt to update a WifiLock that is not active.
     *
     * Steps: call updateWifiLockWorkSource
     * Expected: catch an IllegalArgumentException
     */
    @Test(expected = IllegalArgumentException.class)
    public void testUpdateWifiLockWorkSourceCalledWithoutActiveLock()  throws Exception {
        mWifiLockManager.updateWifiLockWorkSource(mBinder, null);
    }

    /**
     * Test when acquiring a hi-perf lock,
     * WifiLockManager calls to disable power save mechanism.
     */
    @Test
    public void testHiPerfLockAcquireCauseDisablePS() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        verify(mClientModeImpl).setPowerSave(eq(false));
    }

    /**
     * Test when releasing a hi-perf lock,
     * WifiLockManager calls to enable power save mechanism.
     */
    @Test
    public void testHiPerfLockReleaseCauseEnablePS() throws Exception {
        InOrder inOrder = inOrder(mClientModeImpl);
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

        releaseWifiLockSuccessful(mBinder);
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));
    }

    /**
     * Test when acquiring two hi-perf locks, then releasing them.
     * WifiLockManager calls to disable/enable power save mechanism only once.
     */
    @Test
    public void testHiPerfLockAcquireReleaseTwice() throws Exception {
        InOrder inOrder = inOrder(mClientModeImpl);
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);

        // Acquire the first lock
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

        // Now acquire another lock
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder2, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Release the first lock
        releaseWifiLockSuccessful(mBinder);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Release the second lock
        releaseWifiLockSuccessful(mBinder2);
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));
    }

    /**
     * Test when acquiring/releasing multiple locks of different types.
     * This is a combination of test cases described above.
     */
    @Test
    public void testHiPerfLockAcquireReleaseMultiple() throws Exception {
        IBinder fullLockBinder = mock(IBinder.class);
        WorkSource fullLockWS = new WorkSource(DEFAULT_TEST_UID_1);
        IBinder fullLockBinder2 = mock(IBinder.class);
        WorkSource fullLockWS2 = new WorkSource(DEFAULT_TEST_UID_2);
        IBinder scanOnlyLockBinder = mock(IBinder.class);
        WorkSource scanOnlyLockWS = new WorkSource(DEFAULT_TEST_UID_3);
        IBinder hiPerfLockBinder = mock(IBinder.class);
        WorkSource hiPerfLockWS = new WorkSource(DEFAULT_TEST_UID_4);

        InOrder inOrder = inOrder(mClientModeImpl);
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);

        // Acquire the first lock as FULL
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "",
                fullLockBinder, fullLockWS);
        assertEquals(WifiManager.WIFI_MODE_FULL,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Now acquire another lock with SCAN-ONLY
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_SCAN_ONLY, "",
                scanOnlyLockBinder, scanOnlyLockWS);
        assertEquals(WifiManager.WIFI_MODE_FULL,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Now acquire another lock with HIGH-PERF
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                hiPerfLockBinder, hiPerfLockWS);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

       // Release the FULL lock
        releaseWifiLockSuccessful(fullLockBinder);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Release the HIGH-PERF lock
        releaseWifiLockSuccessful(hiPerfLockBinder);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));

        // Acquire the FULL lock again
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "",
                fullLockBinder2, fullLockWS2);
        assertEquals(WifiManager.WIFI_MODE_FULL,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Release the FULL lock
        releaseWifiLockSuccessful(fullLockBinder2);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        // Release the SCAN-ONLY lock
        releaseWifiLockSuccessful(scanOnlyLockBinder);
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());
    }

    /**
     * Test failure case when setPowerSave() fails, during acquisition of hi-perf lock
     * Note, the lock is still acquired despite the failure in setPowerSave().
     * On any new lock activity, the setPowerSave() will be attempted if still needed.
     */
    @Test
    public void testHiPerfLockAcquireFail() throws Exception {
        IBinder fullLockBinder = mock(IBinder.class);
        WorkSource fullLockWS = new WorkSource(DEFAULT_TEST_UID_1);

        InOrder inOrder = inOrder(mClientModeImpl);
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(false);
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

        // Now attempting adding some other lock, WifiLockManager should retry setPowerSave()
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "",
                fullLockBinder, fullLockWS);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));
    }

    /**
     * Test failure case when setPowerSave() fails, during release of hi-perf lock
     * Note, the lock is still released despite the failure in setPowerSave().
     * On any new lock activity, the setPowerSave() will be re-attempted if still needed.
     */
    @Test
    public void testHiPerfLockReleaseFail() throws Exception {
        IBinder fullLockBinder = mock(IBinder.class);
        WorkSource fullLockWS = new WorkSource(DEFAULT_TEST_UID_1);

        InOrder inOrder = inOrder(mClientModeImpl);
        when(mClientModeImpl.setPowerSave(false)).thenReturn(true);
        when(mClientModeImpl.setPowerSave(true)).thenReturn(false);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

        releaseWifiLockSuccessful(mBinder);
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));

        // Now attempting adding some other lock, WifiLockManager should retry setPowerSave()
        when(mClientModeImpl.setPowerSave(true)).thenReturn(true);
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL, "",
                fullLockBinder, fullLockWS);
        assertEquals(WifiManager.WIFI_MODE_FULL,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));
    }

    /**
     * Test when forcing hi-perf mode, that it overrides apps requests
     * until it is no longer forced.
     */
    @Test
    public void testForceHiPerf() throws Exception {
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);
        InOrder inOrder = inOrder(mClientModeImpl);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_SCAN_ONLY, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY,
                mWifiLockManager.getStrongestLockMode());
        assertTrue(mWifiLockManager.forceHiPerfMode(true));
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));
        assertTrue(mWifiLockManager.forceHiPerfMode(false));
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));
    }

    /**
     * Test when forcing hi-perf mode, and aquire/release of hi-perf locks
     */
    @Test
    public void testForceHiPerfAcqRelHiPerf() throws Exception {
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);
        InOrder inOrder = inOrder(mClientModeImpl);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

        assertTrue(mWifiLockManager.forceHiPerfMode(true));
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        releaseWifiLockSuccessful(mBinder);
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());

        assertTrue(mWifiLockManager.forceHiPerfMode(false));
        assertEquals(WifiManager.WIFI_MODE_NO_LOCKS_HELD,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(true));
    }

    /**
     * Test when trying to force hi-perf to true twice back to back
     */
    @Test
    public void testForceHiPerfTwice() throws Exception {
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(true);
        InOrder inOrder = inOrder(mClientModeImpl);

        assertTrue(mWifiLockManager.forceHiPerfMode(true));
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));

        assertTrue(mWifiLockManager.forceHiPerfMode(true));
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl, never()).setPowerSave(anyBoolean());
    }

    /**
     * Test when failure when forcing hi-perf mode
     */
    @Test
    public void testForceHiPerfFailure() throws Exception {
        when(mClientModeImpl.setPowerSave(anyBoolean())).thenReturn(false);
        InOrder inOrder = inOrder(mClientModeImpl);

        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_SCAN_ONLY, "",
                mBinder, mWorkSource);
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY,
                mWifiLockManager.getStrongestLockMode());

        assertFalse(mWifiLockManager.forceHiPerfMode(true));
        assertEquals(WifiManager.WIFI_MODE_SCAN_ONLY,
                mWifiLockManager.getStrongestLockMode());
        inOrder.verify(mClientModeImpl).setPowerSave(eq(false));
    }

    /**
     * Verfies that dump() does not fail when no locks are held.
     */
    @Test
    public void dumpDoesNotFailWhenNoLocksAreHeld() {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLockManager.dump(pw);

        String wifiLockManagerDumpString = sw.toString();
        assertTrue(wifiLockManagerDumpString.contains(
                "Locks acquired: 0 full, 0 full high perf, 0 scan"));
        assertTrue(wifiLockManagerDumpString.contains(
                "Locks released: 0 full, 0 full high perf, 0 scan"));
        assertTrue(wifiLockManagerDumpString.contains("Locks held:"));
        assertFalse(wifiLockManagerDumpString.contains("WifiLock{"));
    }

    /**
     * Verifies that dump() contains lock information when there are locks held.
     */
    @Test
    public void dumpOutputsCorrectInformationWithActiveLocks() throws Exception {
        acquireWifiLockSuccessful(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "", mBinder, mWorkSource);
        releaseWifiLockSuccessful(mBinder);

        acquireWifiLockSuccessful(
                WifiManager.WIFI_MODE_FULL, TEST_WIFI_LOCK_TAG, mBinder, mWorkSource);

        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        mWifiLockManager.dump(pw);

        String wifiLockManagerDumpString = sw.toString();
        assertTrue(wifiLockManagerDumpString.contains(
                "Locks acquired: 1 full, 1 full high perf, 0 scan"));
        assertTrue(wifiLockManagerDumpString.contains(
                "Locks released: 0 full, 1 full high perf, 0 scan"));
        assertTrue(wifiLockManagerDumpString.contains("Locks held:"));
        assertTrue(wifiLockManagerDumpString.contains(
                "WifiLock{" + TEST_WIFI_LOCK_TAG + " type=" + WifiManager.WIFI_MODE_FULL
                + " uid=" + Binder.getCallingUid() + " workSource=WorkSource{"
                        + DEFAULT_TEST_UID_1 + "}"));
    }
}
