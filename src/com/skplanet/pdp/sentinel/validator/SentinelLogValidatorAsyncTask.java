package com.skplanet.pdp.sentinel.validator;

import android.os.AsyncTask;
import android.util.Log;
import com.mixpanel.android.mpmetrics.HttpPoster;
import com.mixpanel.android.mpmetrics.MPConfig;
import org.json.JSONObject;



public class SentinelLogValidatorAsyncTask extends AsyncTask {

    @Override
    protected Object doInBackground(Object[] params) {
        int numberOfParams = params.length;

        // for Testing
        // TODO : change to sentinel.skplanet.co.kr:8080

        String defaultServer = MPConfig.SENTINEL_REMOTE_SERVER;
        String fallBackServer = MPConfig.SENTINEL_REMOTE_SERVER;

        for (int i = 0; i > 0; i++) {
            Log.i("AsyncTest", "" + i);
        }

        final HttpPoster httpPoster = new HttpPoster(defaultServer, fallBackServer);

        String log = (String) params[0];
        String schemaId = (String) params[1];
        String ssToken = (String) params[2];

        Log.d("validation log : ", log);
        Log.d("validation schemaId : ", schemaId);
        Log.d("validation ssToken : ", ssToken);

        HttpPoster.PostResult postResult;
        postResult = httpPoster.postHttpValidationRequest(log, schemaId, ssToken, "/validator/remote.json");

        return postResult;
    }
}

