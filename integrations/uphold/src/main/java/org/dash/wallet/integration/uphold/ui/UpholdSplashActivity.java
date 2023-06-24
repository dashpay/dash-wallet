///*
// * Copyright 2015-present the original author or authors.
// *
// * This program is free software: you can redistribute it and/or modify
// * it under the terms of the GNU General Public License as published by
// * the Free Software Foundation, either version 3 of the License, or
// * (at your option) any later version.
// *
// * This program is distributed in the hope that it will be useful,
// * but WITHOUT ANY WARRANTY; without even the implied warranty of
// * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// * GNU General Public License for more details.
// *
// * You should have received a copy of the GNU General Public License
// * along with this program.  If not, see <http://www.gnu.org/licenses/>.
// */
//
//package org.dash.wallet.integration.uphold.ui;
//
//import android.app.ProgressDialog;
//import android.content.BroadcastReceiver;
//import android.content.Context;
//import android.content.Intent;
//import android.content.IntentFilter;
//import android.os.Bundle;
//import android.view.MenuItem;
//import android.view.View;
//
//import androidx.annotation.Nullable;
//import androidx.appcompat.app.ActionBar;
//import androidx.appcompat.widget.Toolbar;
//import androidx.localbroadcastmanager.content.LocalBroadcastManager;
//
//import org.dash.wallet.common.InteractionAwareActivity;
//import org.dash.wallet.common.services.analytics.AnalyticsConstants;
//import org.dash.wallet.common.services.analytics.AnalyticsService;
//import org.dash.wallet.common.ui.dialogs.AdaptiveDialog;
//import org.dash.wallet.common.util.ActivityExtKt;
//import org.dash.wallet.integration.uphold.R;
//import org.dash.wallet.integration.uphold.api.UpholdClient;
//import org.dash.wallet.integration.uphold.data.UpholdConstants;
//
//import javax.inject.Inject;
//
//import dagger.hilt.android.AndroidEntryPoint;
//import kotlin.Unit;
//
//@AndroidEntryPoint
//// TODO: move this into IntegrationOverviewFragment
//public class UpholdSplashActivity extends InteractionAwareActivity {
//
//    public static final String UPHOLD_EXTRA_CODE = "uphold_extra_code";
//    public static final String UPHOLD_EXTRA_STATE = "uphold_extra_state";
//    @Override
//    protected void onCreate(@Nullable Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//
//        handleIntent(getIntent());
//    }
//
//    @Override
//    protected void onNewIntent(Intent intent) {
//        super.onNewIntent(intent);
//        handleIntent(intent);
//    }
//
//    private void handleIntent(final Intent intent) {
//        Bundle extras = intent.getExtras();
//        if (extras != null && extras.containsKey(UPHOLD_EXTRA_CODE)
//                && extras.containsKey(UPHOLD_EXTRA_STATE)) {
//            String code = extras.getString(UPHOLD_EXTRA_CODE);
//            String state = extras.getString(UPHOLD_EXTRA_STATE);
//            getAccessToken(code, state);
//        }
//    }
//
//
//    private void getAccessToken(String code, String state) {
//        if (code != null && UpholdClient.getInstance().getEncryptionKey().equals(state)) {
//            UpholdClient.getInstance().getAccessToken(code)
//            startUpholdAccountActivity();
//
////            on error:
//
////                    getString(R.string.loading_error),
//        } else {
////            getString(R.string.loading_error),
//        }
//    }
//}
