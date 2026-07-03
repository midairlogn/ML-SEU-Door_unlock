package com.whxinna.userplatform.wechat;

import android.content.Context;
import android.util.Log;

import com.tencent.mm.opensdk.modelmsg.SendAuth;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

public class WechatAuth {

    private static final String TAG = "ZL_WechatAuth";
    public static final String APP_ID = "wx9622aee7b9ae7536";
    public static final String APP_SECRET = "425b4b4ad72c0a317f28bc6b311adfd3";
    private static final String SCOPE = "snsapi_userinfo";
    private static final String STATE = "zhuli_wx_login";

    private IWXAPI wxApi;
    private static WechatAuth instance;
    private static WechatAuthCallback pendingCallback;

    public interface WechatAuthCallback {
        void onSuccess(String code);
        void onError(String message);
    }

    private WechatAuth() {}

    public static synchronized WechatAuth getInstance() {
        if (instance == null) {
            instance = new WechatAuth();
        }
        return instance;
    }

    public void init(Context context) {
        if (wxApi == null) {
            wxApi = WXAPIFactory.createWXAPI(context.getApplicationContext(), APP_ID, true);
            wxApi.registerApp(APP_ID);
            Log.d(TAG, "WeChat API initialized");
        }
    }

    public boolean isWechatInstalled() {
        return wxApi != null && wxApi.isWXAppInstalled();
    }

    public void sendAuth(Context context, WechatAuthCallback callback) {
        init(context);

        if (!isWechatInstalled()) {
            callback.onError("WeChat is not installed");
            return;
        }

        pendingCallback = callback;

        SendAuth.Req req = new SendAuth.Req();
        req.scope = SCOPE;
        req.state = STATE;

        boolean sent = wxApi.sendReq(req);
        Log.d(TAG, "sendReq result: " + sent);

        if (!sent) {
            pendingCallback = null;
            callback.onError("Failed to launch WeChat");
        }
    }

    public static void onAuthResponse(String code, boolean cancel) {
        WechatAuthCallback cb = pendingCallback;
        pendingCallback = null;

        if (cb == null) {
            Log.w(TAG, "No pending callback for WeChat auth response");
            return;
        }

        if (cancel) {
            cb.onError("WeChat authorization cancelled");
        } else if (code != null && !code.isEmpty()) {
            cb.onSuccess(code);
        } else {
            cb.onError("WeChat authorization failed: empty code");
        }
    }
}
