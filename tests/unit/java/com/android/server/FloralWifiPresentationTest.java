/*
 * Copyright (C) 2026 The FloralDroid Project
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

package com.android.server;

import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.os.Process;

import androidx.test.filters.SmallTest;

import com.android.server.connectivity.MockableSystemProperties;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link FloralWifiPresentation}. */
@SmallTest
public class FloralWifiPresentationTest {
    private static final int APP_UID = Process.FIRST_APPLICATION_UID;

    @Mock private MockableSystemProperties mSystemProperties;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mSystemProperties.get(anyString())).thenReturn("");
    }

    @Test
    public void disabledPresentationLeavesCapabilitiesUnchanged() {
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);
        FloralWifiPresentation presentation = new FloralWifiPresentation(mSystemProperties);

        assertFalse(presentation.isEnabledForUid(APP_UID));
        assertSame(ethernet, presentation.apply(ethernet, APP_UID));
    }

    @Test
    public void enabledPresentationAddsUsableWifiViewWithoutChangingSource() {
        when(mSystemProperties.getBoolean("ro.boot.floral_wifi_simulation", false))
                .thenReturn(true);
        FloralWifiPresentation presentation = new FloralWifiPresentation(mSystemProperties);
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);
        ethernet.addCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY);
        ethernet.addCapability(NET_CAPABILITY_CAPTIVE_PORTAL);

        NetworkCapabilities result = presentation.apply(ethernet, APP_UID);

        assertTrue(result.hasTransport(TRANSPORT_ETHERNET));
        assertTrue(result.hasTransport(TRANSPORT_WIFI));
        assertTrue(result.hasCapability(NET_CAPABILITY_INTERNET));
        WifiInfo info = (WifiInfo) result.getTransportInfo();
        assertEquals("\"FloralDroid\"", info.getSSID());
        assertTrue(result.hasCapability(NET_CAPABILITY_VALIDATED));
        assertFalse(result.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));
        assertFalse(result.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL));
        assertFalse(ethernet.hasTransport(TRANSPORT_WIFI));
        assertFalse(ethernet.hasCapability(NET_CAPABILITY_VALIDATED));
    }

    @Test
    public void enabledPresentationDoesNotDecorateNonEthernetNetwork() {
        when(mSystemProperties.getBoolean("ro.boot.floral_wifi_simulation", false))
                .thenReturn(true);
        FloralWifiPresentation presentation = new FloralWifiPresentation(mSystemProperties);
        NetworkCapabilities cellular = createCapabilities(TRANSPORT_CELLULAR);

        assertSame(cellular, presentation.apply(cellular, APP_UID));
    }

    @Test
    public void enabledPresentationDoesNotDecorateSystemUid() {
        when(mSystemProperties.getBoolean("ro.boot.floral_wifi_simulation", false))
                .thenReturn(true);
        FloralWifiPresentation presentation = new FloralWifiPresentation(mSystemProperties);
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);

        assertFalse(presentation.isEnabledForUid(Process.SYSTEM_UID));
        assertSame(ethernet, presentation.apply(ethernet, Process.SYSTEM_UID));
    }

    @Test
    public void legacyCopyReportsWifiAndKeepsOriginalType() {
        when(mSystemProperties.getBoolean("ro.boot.floral_wifi_simulation", false))
                .thenReturn(true);
        FloralWifiPresentation presentation = new FloralWifiPresentation(mSystemProperties);
        NetworkInfo ethernetInfo = new NetworkInfo(
                ConnectivityManager.TYPE_ETHERNET, 0, "ETHERNET", "");
        ethernetInfo.setIsAvailable(true);
        ethernetInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, "ethernet");
        NetworkCapabilities ethernetCapabilities = createCapabilities(TRANSPORT_ETHERNET);

        NetworkInfo result = presentation.applyLegacyType(
                ethernetInfo, ethernetCapabilities, APP_UID);

        assertEquals(ConnectivityManager.TYPE_WIFI, result.getType());
        assertEquals("WIFI", result.getTypeName());
        assertEquals("FloralDroid", result.getExtraInfo());
        assertTrue(result.isConnected());
        assertEquals(ConnectivityManager.TYPE_ETHERNET, ethernetInfo.getType());
    }

    private static NetworkCapabilities createCapabilities(int transport) {
        return new NetworkCapabilities.Builder()
                .addTransportType(transport)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
    }
}
