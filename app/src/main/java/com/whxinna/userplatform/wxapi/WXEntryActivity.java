package com.whxinna.userplatform.wxapi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.tencent.mm.opensdk.modelbase.BaseReq;
import com.tencent.mm.opensdk.modelbase.BaseResp;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;
import com.whxinna.userplatform.wechat.WechatAuth;

public class WXEntryActivity extends Activity implements IWXAPIEventHandler {

    private static final String TAG = "ZL_WXEntry";
    private IWXAPI wxApi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wxApi = WXAPIFactory.createWXAPI(this, WechatAuth.APP_ID, false);
        try {
            wxApi.handleIntent(getIntent(), this);
        } catch (Exception e) {
            Log.e(TAG, "handleIntent failed", e);
            finish();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        try {
            wxApi.handleIntent(intent, this);
        } catch (Exception e) {
            Log.e(TAG, "handleIntent failed", e);
            finish();
        }
    }

    @Override
    public void onReq(BaseReq req) {
        Log.d(TAG, "onReq type=" + req.getType());
        finish();
    }

    @Override
    public void onResp(BaseResp resp) {
        Log.d(TAG, "onResp errCode=" + resp.errCode + " type=" + resp.getType());

        if (resp instanceof com.tencent.mm.opensdk.modelmsg.SendAuth.Resp) {
            com.tencent.mm.opensdk.modelmsg.SendAuth.Resp authResp =
                (com.tencent.mm.opensdk.modelmsg.SendAuth.Resp) resp;

            String code = authResp.code;
            boolean cancel = (resp.errCode == BaseResp.ErrCode.ERR_USER_CANCEL
                || resp.errCode == -2); // -2 = ERR_USER_CANCEL_LITE in some SDK versions

            Log.d(TAG, "WeChat auth code=" + (code != null ? code.substring(0, Math.min(8, code.length())) + "..." : "null")
                + " cancel=" + cancel);

            WechatAuth.onAuthResponse(code, cancel);
        }

        finish();
    }
}
