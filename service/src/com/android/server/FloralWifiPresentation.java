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
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import floral.device.wifi.IWifiState;
import floral.device.wifi.WifiSnapshot;

import java.nio.charset.StandardCharsets;

/**
 * Creates application-facing Wi-Fi views backed by redroid's existing Ethernet network.
 *
 * <p>Only outbound copies are decorated. ConnectivityService's internal NetworkAgent,
 * validation state, score, LinkProperties, interface, and routes remain unchanged.</p>
 */
final class FloralWifiPresentation {
    private static final String TAG = "FloralWifiPresentation";
    private static final int SIMULATED_NETWORK_ID = 0;

    interface BinderLookup {
        @Nullable IBinder getBinder();
    }

    interface StateProvider {
        @Nullable WifiSnapshot getSnapshot();
    }

    private static final class BinderStateProvider implements StateProvider {
        private final BinderLookup mBinderLookup;
        private IWifiState mService;

        BinderStateProvider(@NonNull BinderLookup binderLookup) {
            mBinderLookup = binderLookup;
        }

        @Nullable
        private synchronized IWifiState getService() {
            if (mService != null && mService.asBinder().isBinderAlive()) {
                return mService;
            }
            mService = null;
            IBinder binder = mBinderLookup.getBinder();
            if (binder != null) {
                mService = IWifiState.Stub.asInterface(binder);
            }
            return mService;
        }

        @Override
        @Nullable
        public WifiSnapshot getSnapshot() {
            IWifiState service = getService();
            if (service == null) return null;
            try {
                return service.getSnapshot();
            } catch (RemoteException | RuntimeException exception) {
                Log.w(TAG, "Unable to read Floral Wi-Fi snapshot", exception);
                synchronized (this) {
                    mService = null;
                }
                return null;
            }
        }
    }

    private final StateProvider mStateProvider;

    FloralWifiPresentation(@NonNull BinderLookup binderLookup) {
        this(new BinderStateProvider(binderLookup));
    }

    FloralWifiPresentation(@NonNull StateProvider stateProvider) {
        mStateProvider = stateProvider;
    }

    boolean isEnabledForUid(int uid) {
        return getConnectedSnapshot(uid) != null;
    }

    /** Decorates an outbound copy without changing ConnectivityService's internal capabilities. */
    @NonNull
    NetworkCapabilities apply(@NonNull NetworkCapabilities source, int uid) {
        WifiSnapshot snapshot = getConnectedSnapshot(uid);
        if (snapshot == null || !source.hasTransport(TRANSPORT_ETHERNET)
                || source.hasTransport(TRANSPORT_WIFI)) {
            return source;
        }

        NetworkCapabilities result = new NetworkCapabilities(source);
        result.addTransportType(TRANSPORT_WIFI);
        if (result.getTransportInfo() == null) {
            result.setTransportInfo(createWifiInfo(snapshot));
        }
        // The underlying Ethernet connection is usable even when host-side probe endpoints are
        // inaccessible. This affects only the copy observed by the requesting application.
        result.addCapability(NET_CAPABILITY_VALIDATED);
        result.removeCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY);
        result.removeCapability(NET_CAPABILITY_CAPTIVE_PORTAL);
        return result;
    }

    /** Converts only the copy returned to legacy callers; internal NetworkInfo stays Ethernet. */
    @NonNull
    NetworkInfo applyLegacyType(@NonNull NetworkInfo source,
            @NonNull NetworkCapabilities capabilities, int uid) {
        WifiSnapshot snapshot = getConnectedSnapshot(uid);
        if (snapshot == null || !capabilities.hasTransport(TRANSPORT_ETHERNET)) {
            return source;
        }

        NetworkInfo result = new NetworkInfo(ConnectivityManager.TYPE_WIFI, source.getSubtype(),
                "WIFI", source.getSubtypeName());
        result.setDetailedState(source.getDetailedState(), source.getReason(), snapshot.ssid);
        result.setIsAvailable(source.isAvailable());
        result.setFailover(source.isFailover());
        result.setRoaming(source.isRoaming());
        return result;
    }

    @Nullable
    private WifiSnapshot getConnectedSnapshot(int uid) {
        int appId = UserHandle.getAppId(uid);
        // SystemUI and Settings share the system UID and need the same outbound Wi-Fi view as apps.
        if (appId != Process.SYSTEM_UID && appId < Process.FIRST_APPLICATION_UID) return null;
        WifiSnapshot snapshot = mStateProvider.getSnapshot();
        return snapshot != null && snapshot.enabled && snapshot.connectedAccessPointId != 0
                ? snapshot : null;
    }

    @NonNull
    private static WifiInfo createWifiInfo(WifiSnapshot snapshot) {
        return new WifiInfo.Builder()
                .setSsid(snapshot.ssid.getBytes(StandardCharsets.UTF_8))
                .setBssid(snapshot.bssid)
                .setRssi(snapshot.rssiDbm)
                .setNetworkId(SIMULATED_NETWORK_ID)
                .setCurrentSecurityType(toFrameworkSecurity(snapshot.security))
                .build();
    }

    private static int toFrameworkSecurity(int security) {
        if (security == 0) return WifiConfiguration.SECURITY_TYPE_OPEN;
        if (security == 2) return WifiConfiguration.SECURITY_TYPE_SAE;
        return WifiConfiguration.SECURITY_TYPE_PSK;
    }
}
