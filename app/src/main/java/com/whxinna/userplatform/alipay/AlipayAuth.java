package com.whxinna.userplatform.alipay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.alipay.android.app.IAlixPay;
import com.alipay.android.app.IRemoteServiceCallback;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AlipayAuth {

    private static final String TAG = "ZL_AlipayAuth";
    private static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";
    private static final String BIND_ACTION = "com.eg.android.AlipayGphone.IAlixPay";
    private static final long BIND_TIMEOUT_MS = 15_000L;
    private static final String SDK_VER = "15.8.17";

    public static class AlipayAuthException extends Exception {
        public AlipayAuthException(String message) {
            super(message);
        }
    }

    public static String authorize(Context context, String authInfo) throws AlipayAuthException {
        Context appContext = context.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        final IBinder[] binderHolder = new IBinder[1];
        long bindStart = System.currentTimeMillis();

        // Wake Alipay process lightly
        try {
            Intent wake = new Intent();
            wake.setClassName(ALIPAY_PACKAGE, "com.alipay.android.app.TransProcessPayActivity");
            wake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(wake);
            Thread.sleep(200);
        } catch (Throwable ignored) {
        }

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                Log.d(TAG, "onServiceConnected " + name);
                binderHolder[0] = binder;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binderHolder[0] = null;
            }
        };

        Intent intent = new Intent(BIND_ACTION);
        intent.setPackage(ALIPAY_PACKAGE);
        boolean bound;
        try {
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable e) {
            Log.e(TAG, "bindService threw", e);
            bound = false;
        }
        Log.d(TAG, "bindService bound=" + bound);

        if (!bound) {
            try { appContext.unbindService(connection); } catch (Exception ignored) {}
            throw new AlipayAuthException("Cannot connect to Alipay. Please install Alipay first.");
        }

        try {
            if (!latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new AlipayAuthException("Alipay connection timeout");
            }
            long bindEnd = System.currentTimeMillis();
            IAlixPay alixPay = IAlixPay.Stub.asInterface(binderHolder[0]);
            if (alixPay == null) {
                throw new AlipayAuthException("Alipay service unavailable");
            }

            IRemoteServiceCallback callback = new IRemoteServiceCallback.Stub() {
                @Override
                public void startActivity(String packageName, String className, int flag, Bundle data) {
                    Log.d(TAG, "cb.startActivity pkg=" + packageName + " cls=" + className + " flag=" + flag);
                    try {
                        Intent ui = new Intent(Intent.ACTION_MAIN);
                        if (packageName != null && className != null) {
                            ui.setClassName(packageName, className);
                        }
                        if (flag != 0) ui.addFlags(flag);
                        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        Bundle extras = data != null ? data : new Bundle();
                        extras.putInt("CallingPid", android.os.Process.myPid());
                        ui.putExtras(extras);
                        appContext.startActivity(ui);
                    } catch (Throwable e) {
                        Log.e(TAG, "cb.startActivity failed", e);
                    }
                }

                @Override
                public void payEnd(boolean isOk, String result) {
                    Log.d(TAG, "cb.payEnd isOk=" + isOk + " result=" + result);
                }

                @Override
                public boolean isHideLoadingScreen() {
                    return false;
                }

                @Override
                public int getVersion() {
                    return 3;
                }

                @Override
                public void r03(String a, String b, Map data) {
                    Log.d(TAG, "cb.r03 a=" + a + " b=" + b);
                }
            };

            int version;
            try {
                version = alixPay.getVersion();
            } catch (Throwable e) {
                Log.e(TAG, "getVersion failed", e);
                version = 0;
            }
            Log.d(TAG, "IAlixPay version=" + version);

            try {
                if (version >= 3) {
                    alixPay.registerCallback03(callback, authInfo, null);
                    try {
                        alixPay.r03("alipaySdk", "bind_pay", null);
                    } catch (Throwable e) {
                        Log.e(TAG, "r03 failed", e);
                    }
                } else {
                    alixPay.registerCallback(callback);
                }

                Log.d(TAG, "invoking pay (version=" + version + ")...");
                String raw;
                if (version >= 2) {
                    raw = alixPay.pay02(authInfo, buildTraceMap(authInfo, bindStart, bindEnd));
                } else {
                    raw = alixPay.Pay(authInfo);
                }
                Log.d(TAG, "pay returned: " + raw);
                return parseAuthCode(raw);
            } catch (AlipayAuthException e) {
                throw e;
            } catch (Throwable e) {
                Log.e(TAG, "pay invoke failed", e);
                throw new AlipayAuthException("Alipay authorization call failed: " + e.getMessage());
            } finally {
                try { alixPay.unregisterCallback(callback); } catch (Throwable ignored) {}
            }
        } catch (AlipayAuthException e) {
            throw e;
        } catch (Throwable e) {
            Log.e(TAG, "authorize error", e);
            throw new AlipayAuthException("Alipay authorization error: " + e.getMessage());
        } finally {
            try { appContext.unbindService(connection); } catch (Throwable ignored) {}
        }
    }

    private static Map<String, Object> buildTraceMap(String authInfo, long bindStart, long bindEnd) {
        long now = System.currentTimeMillis();
        String appName = authInfoField(authInfo, "app_name");
        if (appName == null) appName = "mc";
        String token = authInfoField(authInfo, "target_id");
        if (token == null) token = "";

        Map<String, Object> trace = new HashMap<>();
        trace.put("sdk_ver", SDK_VER);
        trace.put("app_name", appName);
        trace.put("token", token);
        trace.put("call_type", "authV2");
        trace.put("ts_api_invoke", now);
        trace.put("ts_bind", bindStart);
        trace.put("ts_bend", bindEnd);
        trace.put("ts_pay", now);
        return trace;
    }

    private static String authInfoField(String authInfo, String key) {
        if (authInfo == null) return null;
        for (String part : authInfo.split("&")) {
            if (part.startsWith(key + "=")) {
                String val = part.substring(key.length() + 1);
                return val.isEmpty() ? null : val;
            }
        }
        return null;
    }

    private static String parseAuthCode(String raw) throws AlipayAuthException {
        if (raw == null || raw.isEmpty()) {
            throw new AlipayAuthException("Alipay returned empty result");
        }

        String status = extractBraceValue(raw, "resultStatus");
        if (!"9000".equals(status)) {
            String memo = extractBraceValue(raw, "memo");
            String detail;
            if (memo != null && !memo.isEmpty()) {
                detail = memo;
            } else if ("6001".equals(status)) {
                detail = "Alipay authorization cancelled";
            } else {
                detail = "Alipay authorization incomplete (" + status + ")";
            }
            throw new AlipayAuthException(detail);
        }

        String result = extractBraceValue(raw, "result");
        if (result == null) result = "";

        for (String part : result.split("&")) {
            if (part.startsWith("auth_code=")) {
                String code = part.substring("auth_code=".length());
                if (!code.isEmpty()) return code;
            }
        }
        throw new AlipayAuthException("Alipay result missing auth_code");
    }

    private static String extractBraceValue(String raw, String key) {
        String marker = key + "=";
        int keyIdx = raw.indexOf(marker);
        if (keyIdx < 0) return null;
        int open = raw.indexOf('{', keyIdx + marker.length());
        if (open < 0) return null;
        int close = raw.indexOf('}', open);
        if (close < 0) return null;
        return raw.substring(open + 1, close);
    }
}
