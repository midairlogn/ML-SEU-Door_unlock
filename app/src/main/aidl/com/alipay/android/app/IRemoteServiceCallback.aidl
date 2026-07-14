// Reverse-engineered from Alipay SDK. Method order == transaction code.
// During pay02/Pay, Alipay invokes startActivity to launch the authorization UI.
package com.alipay.android.app;

interface IRemoteServiceCallback {
    void startActivity(String packageName, String className, int flag, in Bundle data);
    void payEnd(boolean isOk, String result);
    boolean isHideLoadingScreen();
    int getVersion();
    void r03(String a, String b, in Map data);
}
