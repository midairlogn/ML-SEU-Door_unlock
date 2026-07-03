package com.whxinna.userplatform.nfc;

import android.app.Activity;
import android.content.Intent;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.IntentCompat;

import com.whxinna.userplatform.ui.MainActivity;

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
                Tag tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag.class);
                mainIntent.putExtra(NfcAdapter.EXTRA_TAG, tag);
            }
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(mainIntent);
        }

        finish();
    }
}
