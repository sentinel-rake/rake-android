package com.skplanet.pdp.sentinel.validator;

import android.os.AsyncTask;
import android.util.Log;
import com.mixpanel.android.mpmetrics.HttpPoster;
import com.mixpanel.android.mpmetrics.MPConfig;
import org.json.JSONObject;


public class SentinelLogValidatorAsyncTask extends AsyncTask {

    @Override
    protected Object doInBackground(Object[] params) {

        String defaultServer = MPConfig.SENTINEL_REMOTE_SERVER;
        String fallBackServer = MPConfig.SENTINEL_REMOTE_SERVER;

        final HttpPoster httpPoster = new HttpPoster(defaultServer, fallBackServer);
        HttpPoster.PostResult postResult = null;

        try {
            String log = (String) params[0];
            String schemaId = (String) params[1];
            String ssToken = (String) params[2];
            postResult = httpPoster.postHttpValidationRequest(log, schemaId, ssToken, "/validator/remote.json");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return postResult;
    }
}

