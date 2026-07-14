// Reverse-engineered from Alipay SDK's IAlixPay interface (msp service).
// Method declaration order == transaction code. Must match Alipay app's service exactly.
package com.alipay.android.app;

import com.alipay.android.app.IRemoteServiceCallback;

interface IAlixPay {
    String Pay(String strConfig);
    void test();
    void registerCallback(IRemoteServiceCallback cb);
    void unregisterCallback(IRemoteServiceCallback cb);
    String prePay(String strConfig);
    void deployFastConnect();
    String manager(String strConfig);
    int getVersion();
    String pay02(String strConfig, in Map data);
    String r03(String a, String b, in Map data);
    void registerCallback03(IRemoteServiceCallback cb, String a, in Map data);
}
