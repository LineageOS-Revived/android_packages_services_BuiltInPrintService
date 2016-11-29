/*
 * Copyright (C) 2016 The Android Open Source Project
 * Copyright (C) 2016 Mopria Alliance, Inc.
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

package com.android.bips.ipp;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;

import com.android.bips.discovery.DiscoveredPrinter;
import com.android.bips.jni.LocalPrinterCapabilities;
import com.android.bips.util.WifiMonitor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A cache of printer URIs (see {@link DiscoveredPrinter#getUri}) to printer capabilities,
 * with the ability to fetch them on cache misses. {@link #close} must be called when use
 * is complete..
 */
public class CapabilitiesCache extends LruCache<Uri, LocalPrinterCapabilities> implements
        AutoCloseable {
    private static final String TAG = CapabilitiesCache.class.getSimpleName();
    private static final boolean DEBUG = false;

    // Maximum number of capability queries to perform at any one time, so as not to overwhelm
    // AsyncTask.THREAD_POOL_EXECUTOR
    public static final int DEFAULT_MAX_CONCURRENT = 3;

    // Maximum number of printers expected on a single network
    private static final int CACHE_SIZE = 100;

    private final Map<Uri, Request> mRequests = new HashMap<>();
    private final Set<Uri> mToEvict = new HashSet<>();
    private final int mMaxConcurrent;
    private final Backend mBackend;
    private final WifiMonitor mWifiMonitor;
    private boolean mClosed = false;

    /**
     * @param maxConcurrent Maximum number of capabilities requests to make at any one time
     */
    public CapabilitiesCache(Context context, Backend backend, int maxConcurrent) {
        super(CACHE_SIZE);
        if (DEBUG) Log.d(TAG, "CapabilitiesCache()");

        mBackend = backend;
        mMaxConcurrent = maxConcurrent;
        mWifiMonitor = new WifiMonitor(context, connected -> {
            if (!connected) {
                // Evict specified device capabilities when network is lost.
                if (DEBUG) Log.d(TAG, "Evicting " + mToEvict);
                mToEvict.forEach(this::remove);
                mToEvict.clear();
            }
        });
    }

    @Override
    public void close() {
        if (DEBUG) Log.d(TAG, "close()");
        mClosed = true;
        mWifiMonitor.close();
    }

    /**
     * Indicate that a device should be evicted when this object is closed or network
     * parameters change.
     */
    public void evictOnNetworkChange(Uri printerUri) {
        mToEvict.add(printerUri);
    }

    /** Callback for receiving capabilities */
    public interface OnLocalPrinterCapabilities {
        void onCapabilities(LocalPrinterCapabilities capabilities);
    }

    /**
     * Query capabilities and return full results to the listener. A full result includes
     * enough backend data and is suitable for printing. If full data is already available
     * it will be returned to the callback immediately.
     *
     * @param highPriority if true, perform this query before others
     * @param onLocalPrinterCapabilities listener to receive capabilities. Receives null
     *                                   if the attempt fails
     */
    public void request(DiscoveredPrinter printer, boolean highPriority,
            OnLocalPrinterCapabilities onLocalPrinterCapabilities) {
        if (DEBUG) Log.d(TAG, "request() printer=" + printer + " high=" + highPriority);

        Uri printerUri = printer.getUri();
        Uri printerPath = printer.path;
        LocalPrinterCapabilities capabilities = get(printer.getUri());
        if (capabilities != null && capabilities.nativeData != null) {
            onLocalPrinterCapabilities.onCapabilities(capabilities);
            return;
        }

        Request request = mRequests.get(printerUri);
        if (request == null) {
            request = new Request(printer);
            mRequests.put(printerUri, request);
        } else if (!request.printer.path.equals(printerPath)) {
            Log.w(TAG, "Capabilities request for printer " + printer +
                    " overlaps with different path " + request.printer.path);
            onLocalPrinterCapabilities.onCapabilities(null);
            return;
        }

        request.callbacks.add(onLocalPrinterCapabilities);

        if (highPriority) {
            request.highPriority = true;
        }

        startNextRequest();
    }

    /** Look for next query and launch it */
    private void startNextRequest() {
        final Request request = getNextRequest();
        if (request == null) return;

        request.querying = true;
        mBackend.getCapabilities(request.printer.path, capabilities -> {
            DiscoveredPrinter printer = request.printer;
            if (DEBUG) Log.d(TAG, "Capabilities for " + printer + " cap=" + capabilities);

            if (mClosed) return;
            mRequests.remove(printer.getUri());

            // Grab uuid from capabilities if possible
            Uri capUuid = null;
            if (capabilities != null) {
                if (!TextUtils.isEmpty(capabilities.uuid)) {
                    capUuid = Uri.parse(capabilities.uuid);
                }
                if (printer.uuid != null && !printer.uuid.equals(capUuid)) {
                    Log.w(TAG, "UUID mismatch for " + printer + "; rejecting capabilities");
                    capabilities = null;
                }
            }

            if (capabilities == null) {
                remove(printer.getUri());
            } else {
                Uri key = printer.getUri();
                if (printer.uuid == null) {
                    // For non-uuid URIs, evict later
                    evictOnNetworkChange(key);
                    if (capUuid != null) {
                        // Upgrade to UUID if we have it
                        key = capUuid;
                    }
                }
                put(key, capabilities);
            }

            for (OnLocalPrinterCapabilities callback : request.callbacks) {
                callback.onCapabilities(capabilities);
            }
            startNextRequest();
        });
    }

    /** Return the next request if it is appropriate to perform one */
    private Request getNextRequest() {
        Request found = null;
        int total = 0;
        for (Request request : mRequests.values()) {
            if (request.querying) {
                total++;
            } else if (found == null || (!found.highPriority && request.highPriority)) {
                // First outstanding, or higher highPriority request
                found = request;
            }
        }

        if (total >= mMaxConcurrent) return null;

        return found;
    }

    /** Holds an outstanding capabilities request */
    private class Request {
        final DiscoveredPrinter printer;
        final Set<OnLocalPrinterCapabilities> callbacks = new HashSet<>();
        boolean querying = false;
        boolean highPriority = true;

        Request(DiscoveredPrinter printer) {
            this.printer = printer;
        }
    }
}