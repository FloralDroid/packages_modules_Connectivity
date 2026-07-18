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
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.connectivity.MockableSystemProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Creates application-facing Wi-Fi views backed by redroid's existing Ethernet network.
 *
 * <p>Only copies returned to application UIDs are decorated. ConnectivityService's internal
 * NetworkAgent, validation state, score, LinkProperties, interface, and routes remain unchanged.
 * This separation prevents Android system components from treating the real Ethernet network as a
 * Wi-Fi network while still providing applications with a consistent simulated Wi-Fi view.</p>
 */
final class FloralWifiPresentation {
    private static final String TAG = "FloralWifiPresentation";

    private static final String PROP_ENABLED = "ro.boot.floral_wifi_simulation";
    private static final String PROP_SSID = "ro.boot.floral_wifi_ssid";
    private static final String PROP_SSID_BASE64 = "ro.boot.floral_wifi_ssid_b64";
    private static final String PROP_BSSID = "ro.boot.floral_wifi_bssid";
    private static final String PROP_RSSI = "ro.boot.floral_wifi_rssi";
    private static final String PROP_SECURITY = "ro.boot.floral_wifi_security";

    private static final String DEFAULT_SSID = "FloralDroid";
    private static final String DEFAULT_BSSID = "02:00:00:12:00:01";
    private static final int DEFAULT_RSSI = -45;
    private static final int SIMULATED_NETWORK_ID = 0;

    private final boolean mEnabled;
    private final byte[] mSsidBytes;
    private final String mSsid;
    private final String mBssid;
    private final int mRssi;
    private final int mSecurityType;

    FloralWifiPresentation(@NonNull MockableSystemProperties systemProperties) {
        mEnabled = systemProperties.getBoolean(PROP_ENABLED, false);
        mSsidBytes = readSsid(systemProperties);
        mSsid = new String(mSsidBytes, StandardCharsets.UTF_8);
        mBssid = readBssid(systemProperties);
        mRssi = readRssi(systemProperties);

        String security = systemProperties.get(PROP_SECURITY).trim().toLowerCase(Locale.ROOT);
        if ("open".equals(security)) {
            mSecurityType = WifiConfiguration.SECURITY_TYPE_OPEN;
        } else if ("wpa3".equals(security)) {
            mSecurityType = WifiConfiguration.SECURITY_TYPE_SAE;
        } else {
            mSecurityType = WifiConfiguration.SECURITY_TYPE_PSK;
        }
    }

    boolean isEnabledForUid(int uid) {
        return mEnabled
                && UserHandle.getAppId(uid) >= Process.FIRST_APPLICATION_UID;
    }

    /** Decorates an outbound copy without changing ConnectivityService's internal capabilities. */
    @NonNull
    NetworkCapabilities apply(@NonNull NetworkCapabilities source, int uid) {
        if (!isEnabledForUid(uid) || !source.hasTransport(TRANSPORT_ETHERNET)
                || source.hasTransport(TRANSPORT_WIFI)) {
            return source;
        }

        NetworkCapabilities result = new NetworkCapabilities(source);
        result.addTransportType(TRANSPORT_WIFI);
        if (result.getTransportInfo() == null) {
            result.setTransportInfo(createWifiInfo());
        }
        // The simulated view represents a usable connection even if host-side probe endpoints are
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
        if (!isEnabledForUid(uid) || !capabilities.hasTransport(TRANSPORT_ETHERNET)) {
            return source;
        }

        NetworkInfo result = new NetworkInfo(ConnectivityManager.TYPE_WIFI, source.getSubtype(),
                "WIFI", source.getSubtypeName());
        result.setDetailedState(source.getDetailedState(), source.getReason(), mSsid);
        result.setIsAvailable(source.isAvailable());
        result.setFailover(source.isFailover());
        result.setRoaming(source.isRoaming());
        return result;
    }

    @NonNull
    private WifiInfo createWifiInfo() {
        return new WifiInfo.Builder()
                .setSsid(mSsidBytes)
                .setBssid(mBssid)
                .setRssi(mRssi)
                .setNetworkId(SIMULATED_NETWORK_ID)
                .setCurrentSecurityType(mSecurityType)
                .build();
    }

    private static byte[] readSsid(MockableSystemProperties systemProperties) {
        String encodedSsid = systemProperties.get(PROP_SSID_BASE64);
        if (!TextUtils.isEmpty(encodedSsid)) {
            try {
                byte[] decoded = Base64.getDecoder().decode(encodedSsid);
                if (isValidSsid(decoded)) {
                    return decoded;
                }
                Log.w(TAG, "Ignoring floral Wi-Fi SSID outside the 1..32 byte range");
            } catch (IllegalArgumentException exception) {
                Log.w(TAG, "Ignoring invalid floral Wi-Fi base64 SSID", exception);
            }
        }

        String rawValue = systemProperties.get(PROP_SSID);
        byte[] rawSsid = (TextUtils.isEmpty(rawValue) ? DEFAULT_SSID : rawValue)
                .getBytes(StandardCharsets.UTF_8);
        if (isValidSsid(rawSsid)) {
            return rawSsid;
        }
        Log.w(TAG, "Using default floral Wi-Fi SSID because the configured value is invalid");
        return DEFAULT_SSID.getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isValidSsid(byte[] ssid) {
        return ssid.length > 0 && ssid.length <= 32;
    }

    private static String readBssid(MockableSystemProperties systemProperties) {
        String value = systemProperties.get(PROP_BSSID).trim();
        if (value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
            return value.toLowerCase(Locale.ROOT);
        }
        return DEFAULT_BSSID;
    }

    private static int readRssi(MockableSystemProperties systemProperties) {
        try {
            int value = Integer.parseInt(systemProperties.get(PROP_RSSI));
            if (value >= -127 && value <= -1) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Missing and invalid boot properties use the deterministic default.
        }
        return DEFAULT_RSSI;
    }
}
