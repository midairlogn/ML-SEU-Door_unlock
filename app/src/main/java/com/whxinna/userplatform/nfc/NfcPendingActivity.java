package com.whxinna.userplatform.nfc;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.util.Log;

import com.whxinna.userplatform.ui.MainActivity;
import com.whxinna.userplatform.storage.CredentialCache;

public class NfcPendingActivity extends Activity {

    private static final String TAG = "ZL_NfcPending";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent != null) {
            String action = intent.getAction();
            Log.d(TAG, "NFC intent received: " + action);

            // Forward the NFC intent to MainActivity
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.setAction(intent.getAction());
            if (intent.getData() != null) {
                mainIntent.setData(intent.getData());
            }
            if (intent.hasExtra(NfcAdapter.EXTRA_TAG)) {
                mainIntent.putExtra(NfcAdapter.EXTRA_TAG,
                    (android.os.Parcelable) intent.getParcelableExtra(NfcAdapter.EXTRA_TAG));
            }
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(mainIntent);
        }

        finish();
    }
}
