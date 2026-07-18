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
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.os.SystemProperties;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime checks for the application-facing Floral Wi-Fi presentation boundary. */
@SmallTest
public class FloralWifiRuntimePresentationTest {
    private static final String PROP_ENABLED = "ro.boot.floral_wifi_simulation";

    @Test
    public void applicationApisPresentValidatedWifi() throws Exception {
        // Keep the platform test suite portable when Floral Wi-Fi simulation is not requested.
        assumeTrue(SystemProperties.getBoolean(PROP_ENABLED, false));

        Context context = InstrumentationRegistry.getContext();
        ConnectivityManager connectivityManager =
                context.getSystemService(ConnectivityManager.class);
        assertNotNull(connectivityManager);

        Network activeNetwork = connectivityManager.getActiveNetwork();
        assertNotNull(activeNetwork);
        assertApplicationWifiCapabilities(
                connectivityManager.getNetworkCapabilities(activeNetwork));

        NetworkInfo activeInfo = connectivityManager.getActiveNetworkInfo();
        assertNotNull(activeInfo);
        assertTrue(activeInfo.isConnected());
        assertEquals(ConnectivityManager.TYPE_WIFI, activeInfo.getType());

        // NetworkCallback is a separate outbound path used by modern applications.
        CountDownLatch callbackReceived = new CountDownLatch(1);
        AtomicReference<NetworkCapabilities> callbackCapabilities = new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(
                            Network network, NetworkCapabilities capabilities) {
                        callbackCapabilities.set(capabilities);
                        callbackReceived.countDown();
                    }
                };
        connectivityManager.registerDefaultNetworkCallback(callback);
        try {
            assertTrue("Timed out waiting for the default network callback",
                    callbackReceived.await(5, TimeUnit.SECONDS));
            assertApplicationWifiCapabilities(callbackCapabilities.get());
        } finally {
            connectivityManager.unregisterNetworkCallback(callback);
        }
    }

    private static void assertApplicationWifiCapabilities(NetworkCapabilities capabilities) {
        assertNotNull(capabilities);
        assertTrue(capabilities.hasTransport(TRANSPORT_ETHERNET));
        assertTrue(capabilities.hasTransport(TRANSPORT_WIFI));
        assertTrue(capabilities.hasCapability(NET_CAPABILITY_INTERNET));
        assertTrue(capabilities.hasCapability(NET_CAPABILITY_VALIDATED));
        assertFalse(capabilities.hasCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY));
        assertFalse(capabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL));
        assertTrue(capabilities.getTransportInfo() instanceof WifiInfo);
    }
}
