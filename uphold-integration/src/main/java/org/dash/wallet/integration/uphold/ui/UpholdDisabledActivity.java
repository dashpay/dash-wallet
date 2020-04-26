package org.dash.wallet.integration.uphold.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import org.dash.wallet.common.InteractionAwareActivity;
import org.dash.wallet.integration.uphold.R;

public class UpholdDisabledActivity extends  InteractionAwareActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.uphold_disabled_screen);
        findViewById(R.id.go_to_uphold).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = "https://uphold.com";
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
            }
        });

    }
}
