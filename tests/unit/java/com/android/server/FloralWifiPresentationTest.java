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
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.os.Process;

import androidx.test.filters.SmallTest;

import floral.device.wifi.WifiSnapshot;

import org.junit.Test;

/** Unit tests for {@link FloralWifiPresentation}. */
@SmallTest
public class FloralWifiPresentationTest {
    private static final int APP_UID = Process.FIRST_APPLICATION_UID;

    @Test
    public void disabledPresentationLeavesCapabilitiesUnchanged() {
        FakeStateProvider provider = new FakeStateProvider();
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);
        FloralWifiPresentation presentation = new FloralWifiPresentation(provider);

        assertFalse(presentation.isEnabledForUid(APP_UID));
        assertSame(ethernet, presentation.apply(ethernet, APP_UID));
    }

    @Test
    public void connectedPresentationAddsUsableWifiViewWithoutChangingSource() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);
        ethernet.addCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY);
        ethernet.addCapability(NET_CAPABILITY_CAPTIVE_PORTAL);

        NetworkCapabilities result = presentation.apply(ethernet, APP_UID);

        assertTrue(result.hasTransport(TRANSPORT_ETHERNET));
        assertTrue(result.hasTransport(TRANSPORT_WIFI));
        assertTrue(result.hasCapability(NET_CAPABILITY_INTERNET));
        assertEquals(-48, result.getSignalStrength());
        assertEquals("FloralDroid", result.getSsid());
        WifiInfo info = (WifiInfo) result.getTransportInfo();
        assertEquals("\"FloralDroid\"", info.getSSID());
        assertTrue(result.hasCapability(NET_CAPABILITY_VALIDATED));
        assertFalse(result.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));
        assertFalse(result.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL));
        assertFalse(ethernet.hasTransport(TRANSPORT_WIFI));
        assertFalse(ethernet.hasCapability(NET_CAPABILITY_VALIDATED));
    }

    @Test
    public void networkAgentWifiViewSatisfiesWifiRequestWithoutChangingEthernet() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);
        NetworkCapabilities wifiRequest = new NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .build();

        NetworkCapabilities result = presentation.applyToNetworkAgent(ethernet);

        assertTrue(wifiRequest.satisfiedByNetworkCapabilities(result));
        assertTrue(result.hasTransport(TRANSPORT_ETHERNET));
        assertTrue(result.hasTransport(TRANSPORT_WIFI));
        assertFalse(ethernet.hasTransport(TRANSPORT_WIFI));
    }

    @Test
    public void networkAgentWifiViewUsesLatestSignalStrength() {
        FakeStateProvider provider = new FakeStateProvider(connectedSnapshot());
        FloralWifiPresentation presentation = new FloralWifiPresentation(provider);
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);

        assertEquals(-48, presentation.applyToNetworkAgent(ethernet).getSignalStrength());
        WifiSnapshot changed = connectedSnapshot();
        changed.rssiDbm = -72;
        provider.setSnapshot(changed);
        assertEquals(-72, presentation.applyToNetworkAgent(ethernet).getSignalStrength());
    }

    @Test
    public void disconnectedNetworkAgentViewRestoresDeclaredEthernet() {
        FakeStateProvider provider = new FakeStateProvider(connectedSnapshot());
        FloralWifiPresentation presentation = new FloralWifiPresentation(provider);
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);

        assertTrue(presentation.applyToNetworkAgent(ethernet).hasTransport(TRANSPORT_WIFI));
        WifiSnapshot disconnected = connectedSnapshot();
        disconnected.connectedAccessPointId = 0;
        provider.setSnapshot(disconnected);

        assertSame(ethernet, presentation.applyToNetworkAgent(ethernet));
        assertFalse(ethernet.hasTransport(TRANSPORT_WIFI));
    }

    @Test
    public void networkAgentWifiViewLeavesVpnCapabilitiesUntouched() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
        NetworkCapabilities vpn = createCapabilities(TRANSPORT_VPN);
        vpn.addTransportType(TRANSPORT_ETHERNET);

        assertSame(vpn, presentation.applyToNetworkAgent(vpn));
        assertFalse(vpn.hasTransport(TRANSPORT_WIFI));
    }

    @Test
    public void connectedPresentationDoesNotDecorateNonEthernetNetwork() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
        NetworkCapabilities cellular = createCapabilities(TRANSPORT_CELLULAR);

        assertSame(cellular, presentation.apply(cellular, APP_UID));
    }

    @Test
    public void connectedPresentationDecoratesSystemUidForSystemUi() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);

        assertTrue(presentation.isEnabledForUid(Process.SYSTEM_UID));
        assertTrue(presentation.apply(ethernet, Process.SYSTEM_UID).hasTransport(TRANSPORT_WIFI));
    }

    @Test
    public void connectedPresentationDoesNotDecorateShellUid() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
        NetworkCapabilities ethernet = createCapabilities(TRANSPORT_ETHERNET);

        assertFalse(presentation.isEnabledForUid(Process.SHELL_UID));
        assertSame(ethernet, presentation.apply(ethernet, Process.SHELL_UID));
    }

    @Test
    public void legacyCopyReportsWifiAndKeepsOriginalType() {
        FloralWifiPresentation presentation =
                new FloralWifiPresentation(new FakeStateProvider(connectedSnapshot()));
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

    private static WifiSnapshot connectedSnapshot() {
        WifiSnapshot snapshot = new WifiSnapshot();
        snapshot.enabled = true;
        snapshot.connectedAccessPointId = 1;
        snapshot.ssid = "FloralDroid";
        snapshot.bssid = "02:00:00:12:00:01";
        snapshot.security = 1;
        snapshot.rssiDbm = -48;
        snapshot.frequencyMhz = 5180;
        snapshot.channelWidthMhz = 80;
        snapshot.linkSpeedMbps = 866;
        return snapshot;
    }

    private static NetworkCapabilities createCapabilities(int transport) {
        return new NetworkCapabilities.Builder()
                .addTransportType(transport)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
    }

    private static final class FakeStateProvider implements FloralWifiPresentation.StateProvider {
        private WifiSnapshot mSnapshot;

        FakeStateProvider() {
            this(null);
        }

        FakeStateProvider(WifiSnapshot snapshot) {
            mSnapshot = snapshot;
        }

        void setSnapshot(WifiSnapshot snapshot) {
            mSnapshot = snapshot;
        }

        @Override
        public WifiSnapshot getSnapshot() {
            return mSnapshot;
        }
    }
}
