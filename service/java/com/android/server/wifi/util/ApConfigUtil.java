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

package com.android.server.wifi.util;

import android.annotation.NonNull;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.util.Log;

import com.android.server.wifi.WifiNative;

import java.util.ArrayList;
import java.util.Random;

/**
 * Provide utility functions for updating soft AP related configuration.
 */
public class ApConfigUtil {
    private static final String TAG = "ApConfigUtil";

    public static final int DEFAULT_AP_BAND = SoftApConfiguration.BAND_2GHZ;
    public static final int DEFAULT_AP_CHANNEL = 6;
    public static final int HIGHEST_2G_AP_CHANNEL = 14;
    /* Return code for updateConfiguration. */
    public static final int SUCCESS = 0;
    public static final int ERROR_NO_CHANNEL = 1;
    public static final int ERROR_GENERIC = 2;

    /* Random number generator used for AP channel selection. */
    private static final Random sRandom = new Random();

    /**
     * Convert frequency to channel.
     * Note: the utility does not perform any regulatory domain compliance.
     * @param frequency frequency to convert
     * @return channel number associated with given frequency, -1 if no match
     */
    public static int convertFrequencyToChannel(int frequency) {
        if (frequency >= 2412 && frequency <= 2472) {
            return (frequency - 2412) / 5 + 1;
        } else if (frequency == 2484) {
            return 14;
        } else if (frequency >= 5170  &&  frequency <= 5865) {
            /* DFS is included. */
            return (frequency - 5170) / 5 + 34;
        }

        return -1;
    }

    /**
     * Return a channel number for AP setup based on the frequency band.
     * @param apBand one of the value of SoftApConfiguration.BAND_*.
     * @param allowed2GChannels list of allowed 2GHz channels
     * @param allowed5GFreqList list of allowed 5GHz frequencies
     * @return a valid channel number on success, -1 on failure.
     */
    public static int chooseApChannel(int apBand,
                                      ArrayList<Integer> allowed2GChannels,
                                      int[] allowed5GFreqList) {
        if (apBand != SoftApConfiguration.BAND_2GHZ
                && apBand != SoftApConfiguration.BAND_5GHZ
                        && apBand != SoftApConfiguration.BAND_ANY) {
            Log.e(TAG, "Invalid band: " + apBand);
            return -1;
        }

        // TODO(b/72120668): Create channel selection logic for AP_BAND_ANY.
        if (apBand == SoftApConfiguration.BAND_2GHZ
                || apBand == SoftApConfiguration.BAND_ANY)  {
            /* Select a channel from 2GHz band. */
            if (allowed2GChannels == null || allowed2GChannels.size() == 0) {
                Log.d(TAG, "2GHz allowed channel list not specified");
                /* Use default channel. */
                return DEFAULT_AP_CHANNEL;
            }

            /* Pick a random channel. */
            int index = sRandom.nextInt(allowed2GChannels.size());
            return allowed2GChannels.get(index).intValue();
        }

        /* 5G without DFS. */
        if (allowed5GFreqList != null && allowed5GFreqList.length > 0) {
            /* Pick a random channel from the list of supported channels. */
            return convertFrequencyToChannel(
                    allowed5GFreqList[sRandom.nextInt(allowed5GFreqList.length)]);
        }

        Log.e(TAG, "No available channels on 5GHz band");
        return -1;
    }

    /**
     * Update AP band and channel based on the provided country code and band.
     * This will also set
     * @param wifiNative reference to WifiNative
     * @param countryCode country code
     * @param allowed2GChannels list of allowed 2GHz channels
     * @param config configuration to update
     * @return an integer result code
     */
    public static int updateApChannelConfig(WifiNative wifiNative,
                                            String countryCode,
                                            ArrayList<Integer> allowed2GChannels,
                                            SoftApConfiguration.Builder configBuilder,
                                            SoftApConfiguration config) {
        /* Use default band and channel for device without HAL. */
        if (!wifiNative.isHalStarted()) {
            configBuilder.setBand(DEFAULT_AP_BAND);
            configBuilder.setChannel(DEFAULT_AP_CHANNEL);
            return SUCCESS;
        }

        /* Country code is mandatory for 5GHz band. */
        if (config.getBand() == SoftApConfiguration.BAND_5GHZ
                && countryCode == null) {
            Log.e(TAG, "5GHz band is not allowed without country code");
            return ERROR_GENERIC;
        }

        /* Select a channel if it is not specified. */
        if (config.getChannel() == 0) {
            int channel = chooseApChannel(config.getBand(), allowed2GChannels,
                    wifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ));
            if (channel == -1) {
                /* We're not able to get channel from wificond. */
                Log.e(TAG, "Failed to get available channel.");
                return ERROR_NO_CHANNEL;
            }
            configBuilder.setChannel(channel);
        }

        return SUCCESS;
    }

    /**
     * Helper function for converting SoftapConfiguration to WifiConfiguration.
     */
    @NonNull
    public static WifiConfiguration convertToWifiConfiguration(
            @NonNull SoftApConfiguration softApConfig) {
        WifiConfiguration wifiConfig = new WifiConfiguration();

        wifiConfig.SSID = softApConfig.getSsid();
        if (softApConfig.getBssid() != null) {
            wifiConfig.BSSID = softApConfig.getBssid().toString();
        }
        wifiConfig.preSharedKey = softApConfig.getWpa2Passphrase();
        wifiConfig.hiddenSSID = softApConfig.isHiddenSsid();
        switch (softApConfig.getBand()) {
            case SoftApConfiguration.BAND_2GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_2GHZ;
                break;
            case SoftApConfiguration.BAND_5GHZ:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_5GHZ;
                break;
            default:
                wifiConfig.apBand  = WifiConfiguration.AP_BAND_ANY;
                break;
        }
        wifiConfig.apChannel = softApConfig.getChannel();
        int authType = softApConfig.getSecurityType();
        switch (authType) {
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                authType = WifiConfiguration.KeyMgmt.WPA2_PSK;
                break;
            default:
                authType = WifiConfiguration.KeyMgmt.NONE;
                break;
        }
        wifiConfig.allowedKeyManagement.set(authType);

        return wifiConfig;
    }

    /**
     * Helper function for converting WifiConfiguration to SoftApConfiguration.
     *
     * Only Support None and WPA2 configuration conversion.
     */
    @NonNull
    public static SoftApConfiguration fromWifiConfiguration(
            @NonNull WifiConfiguration wifiConfig) {
        SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
        configBuilder.setSsid(wifiConfig.SSID);
        if (wifiConfig.BSSID != null) {
            configBuilder.setBssid(MacAddress.fromString(wifiConfig.BSSID));
        }
        if (wifiConfig.getAuthType() == WifiConfiguration.KeyMgmt.WPA2_PSK) {
            configBuilder.setWpa2Passphrase(wifiConfig.preSharedKey);
        }
        configBuilder.setHiddenSsid(wifiConfig.hiddenSSID);
        switch (wifiConfig.apBand) {
            case WifiConfiguration.AP_BAND_2GHZ:
                configBuilder.setBand(SoftApConfiguration.BAND_2GHZ);
                break;
            case WifiConfiguration.AP_BAND_5GHZ:
                configBuilder.setBand(SoftApConfiguration.BAND_5GHZ);
                break;
            default:
                configBuilder.setBand(SoftApConfiguration.BAND_ANY);
                break;
        }
        configBuilder.setChannel(wifiConfig.apChannel);
        return configBuilder.build();
    }
}
