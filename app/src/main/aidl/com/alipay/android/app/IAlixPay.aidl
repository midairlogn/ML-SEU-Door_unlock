// Reverse-engineered from Alipay SDK's IAlixPay interface (msp service).
// Method declaration order == transaction code. Must match Alipay app's service exactly.
package com.alipay.android.app;

import com.alipay.android.app.IRemoteServiceCallback;

interface IAlixPay {
    String Pay(String strConfig);                                              // 1
    void test();                                                               // 2
    void registerCallback(IRemoteServiceCallback cb);                          // 3
    void unregisterCallback(IRemoteServiceCallback cb);                        // 4
    String prePay(String strConfig);                                           // 5
    void deployFastConnect();                                                  // 6
    String manager(String strConfig);                                          // 7
    int getVersion();                                                          // 8
    String pay02(String strConfig, in Map data);                              // 9
    String r03(String a, String b, in Map data);                              // 10
    void registerCallback03(IRemoteServiceCallback cb, String a, in Map data); // 11
}
