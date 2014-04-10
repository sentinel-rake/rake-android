package com.skplanet.pdp.sentinel.validator;

import android.os.AsyncTask;
import com.rake.android.rkmetrics.HttpPoster;
import com.rake.android.rkmetrics.RKConfig;


public class SentinelLogValidatorAsyncTask extends AsyncTask {

    @Override
    protected Object doInBackground(Object[] params) {

        String defaultServer = RKConfig.SENTINEL_REMOTE_SERVER;

        final HttpPoster httpPoster = new HttpPoster(defaultServer);
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

