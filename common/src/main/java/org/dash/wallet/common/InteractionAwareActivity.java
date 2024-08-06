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
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.Nullable;
import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class InteractionAwareActivity extends SecureActivity {
    public static final String FORCE_FINISH_ACTION = "InteractionAwareActivity.FORCE_FINISH_ACTION";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        IntentFilter filter = new IntentFilter(FORCE_FINISH_ACTION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(forceFinishReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(forceFinishReceiver, filter);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        ((AutoLogoutTimerHandler) getApplication()).resetAutoLogoutTimer();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(forceFinishReceiver);
        } catch (Exception e) {
            // already unregistered
        }
        super.onDestroy();
    }

    private final BroadcastReceiver forceFinishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };
}
