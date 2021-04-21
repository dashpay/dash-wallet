/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.common;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class InteractionAwareActivity extends AppCompatActivity {

    public static final String FORCE_FINISH_ACTION = "InteractionAwareActivity.FORCE_FINISH_ACTION";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IntentFilter filter = new IntentFilter(FORCE_FINISH_ACTION);
        registerReceiver(forceFinishReceiver, filter);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        ((ResetAutoLogoutTimerHandler) getApplication()).resetAutoLogoutTimer();
    }

    @Override
    protected void onPause() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onPause();
    }

    @Override
    protected void onResume() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        super.onResume();
    }

    @Override
    public void finish() {
        unregisterReceiver(forceFinishReceiver);
        super.finish();
    }

    private final BroadcastReceiver forceFinishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };
}
