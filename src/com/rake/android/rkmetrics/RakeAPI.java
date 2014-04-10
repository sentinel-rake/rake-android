package com.rake.android.rkmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import com.skplanet.pdp.sentinel.validator.SentinelLogValidatorAsyncTask;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

public class RakeAPI {
    public static final String VERSION = "0.0.1";
    private boolean isDevServer = false;

    /**
     * You shouldn't instantiate RakeAPI objects directly. Use
     * RakeAPI.getInstance to get an instance.
     */
    private RakeAPI(Context context, String token) {
        mContext = context;
        mToken = token;

        mMessages = getAnalyticsMessages();
        mSystemInformation = getSystemInformation();

        mStoredPreferences = context.getSharedPreferences("com.rake.android.rkmetrics.RakeAPI_" + token, Context.MODE_PRIVATE);
        readSuperProperties();
    }


    public static RakeAPI getInstance(Context context, String token, Boolean isDevServer) {
        synchronized (sInstanceMap) {
            Context appContext = context.getApplicationContext();
            Map<Context, RakeAPI> instances = sInstanceMap.get(token);


            if (instances == null) {
                instances = new HashMap<Context, RakeAPI>();
                sInstanceMap.put(token, instances);
            }

            RakeAPI instance = instances.get(appContext);

            if (instance == null) {
                instance = new RakeAPI(appContext, token);
                instances.put(appContext, instance);

                instance.isDevServer = isDevServer;
                if (isDevServer) {
                    instance.setBaseServer(context, RKConfig.DEV_BASE_ENDPOINT);
                } else {
                    instance.setBaseServer(context, RKConfig.BASE_ENDPOINT);
                }
            }
            return instance;
        }
    }

    public static void setFlushInterval(Context context, long milliseconds) {
        AnalyticsMessages msgs = AnalyticsMessages.getInstance(context);
        msgs.setFlushInterval(milliseconds);
    }

    public void track(JSONObject properties) {
        try {
            Date now = new Date();

            String baseTime = baseTimeFormat.format(now);
            String localTime = localTimeFormat.format(now);

            JSONObject dataObj = new JSONObject();
            dataObj.put("token", mToken);
            dataObj.put("baseTime", baseTime);
            dataObj.put("localTime", localTime);


            JSONObject propertiesObj = new JSONObject();

            // set superProperties
            for (Iterator<?> iter = mSuperProperties.keys(); iter.hasNext(); ) {
                String key = (String) iter.next();
                propertiesObj.put(key, mSuperProperties.get(key));
            }

            // set user's custom properties
            if (properties != null) {
                for (Iterator<?> iter = properties.keys(); iter.hasNext(); ) {
                    String key = (String) iter.next();
                    propertiesObj.put(key, properties.get(key));
                }
            }

            // overwrite default properties
            JSONObject defaultProperties = getDefaultEventProperties();
            if (defaultProperties != null) {
                for (Iterator<?> iter = defaultProperties.keys(); iter.hasNext(); ) {
                    String key = (String) iter.next();
                    propertiesObj.put(key, defaultProperties.get(key));
                }
            }

            propertiesObj.put("baseTime", baseTime);
            propertiesObj.put("localTime", localTime);

            // set dataObj
            dataObj.put("properties", propertiesObj);


            if (properties.has("_$ssToken")) {
                if (this.isDevServer) {

                    StringBuilder log = new StringBuilder();
                    Log.d("fullLog", propertiesObj.toString());

                    JSONArray schemaOrder = propertiesObj.getJSONArray("_$ssSchemaOrder");
                    String schemaId = (String) propertiesObj.get("_$ssSchemaId");
                    String ssToken = (String) propertiesObj.get("_$ssToken");

                    for (int i = 0; i < schemaOrder.length(); i++) {
                        log.append(propertiesObj.get(schemaOrder.get(i).toString()).toString()).append('\t');
                    }
                    log.deleteCharAt(log.length() - 1);

                    if (propertiesObj.has("body")) {
                        log.append('\t').append(propertiesObj.get("body").toString());
                    }

                    Log.d("fullLog String : ", log.toString());
                    new SentinelLogValidatorAsyncTask().execute(log.toString(), schemaId, ssToken);

                }
                try {
                    ((JSONObject) (dataObj.get("properties"))).remove("_$ssToken");
                    ((JSONObject) (dataObj.get("properties"))).remove("_$ssVersion");
                    ((JSONObject) (dataObj.get("properties"))).remove("_$ssSchemaOrder");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                // nothing have to do now
            }

            //Log.d("send message to rake server",dataObj.toString());

            mMessages.eventsMessage(dataObj);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Exception tracking event ", e);
        }
    }

    private void setBaseServer(Context context, String server) {

        AnalyticsMessages msgs = AnalyticsMessages.getInstance(context);
        msgs.setEndpointHost(server);

    }
    public static void setDebug(Boolean debug) {
        RKConfig.DEBUG = debug;
        Log.d(LOGTAG, "RKConfig.DEBUG : " + RKConfig.DEBUG);
    }


    public void flush() {
        if (RKConfig.DEBUG)
            Log.d(LOGTAG, "flushEvents");

        mMessages.postToServer();
    }


    public void registerSuperProperties(JSONObject superProperties) {
        if (RKConfig.DEBUG)
            Log.d(LOGTAG, "registerSuperProperties");

        for (Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            String key = (String) iter.next();
            try {
                mSuperProperties.put(key, superProperties.get(key));
            } catch (JSONException e) {
                Log.e(LOGTAG, "Exception registering super property.", e);
            }
        }

        storeSuperProperties();
    }

    public void unregisterSuperProperty(String superPropertyName) {
        mSuperProperties.remove(superPropertyName);

        storeSuperProperties();
    }


    public void registerSuperPropertiesOnce(JSONObject superProperties) {
        if (RKConfig.DEBUG)
            Log.d(LOGTAG, "registerSuperPropertiesOnce");

        for (Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            String key = (String) iter.next();
            if (!mSuperProperties.has(key)) {
                try {
                    mSuperProperties.put(key, superProperties.get(key));
                } catch (JSONException e) {
                    Log.e(LOGTAG, "Exception registering super property.", e);
                }
            }
        }// for

        storeSuperProperties();
    }

    public void clearSuperProperties() {
        if (RKConfig.DEBUG)
            Log.d(LOGTAG, "clearSuperProperties");
        mSuperProperties = new JSONObject();
    }


    // Package-level access. Used (at least) by GCMReceiver
    // when OS-level events occur.
//    interface InstanceProcessor {
//        public void process(RakeAPI m);
//    }
//
//    static void allInstances(InstanceProcessor processor) {
//        synchronized (sInstanceMap) {
//            for (Map<Context, RakeAPI> contextInstances : sInstanceMap
//                    .values()) {
//                for (RakeAPI instance : contextInstances.values()) {
//                    processor.process(instance);
//                }
//            }
//        }
//    }

    // //////////////////////////////////////////////////////////////////
    // Conveniences for testing. These methods should not be called by
    // non-test client code.

    AnalyticsMessages getAnalyticsMessages() {
        return AnalyticsMessages.getInstance(mContext);
    }

    SystemInformation getSystemInformation() {
        return new SystemInformation(mContext);
    }

    void clearPreferences() {
        // Will clear distinct_ids, superProperties,
        // and waiting People Analytics properties. Will have no effect
        // on messages already queued to send with AnalyticsMessages.

        SharedPreferences.Editor prefsEdit = mStoredPreferences.edit();
        prefsEdit.clear().commit();
        readSuperProperties();
    }

    // //////////////////////////////////////////////////

    private JSONObject getDefaultEventProperties() throws JSONException {
        JSONObject ret = new JSONObject();

        ret.put("rakeLib", "android");
        ret.put("rakeLibVersion", VERSION);

        // For querying together with data from other libraries
        ret.put("osName", "Android");
        ret.put("osVersion", Build.VERSION.RELEASE == null ? "UNKNOWN"
                : Build.VERSION.RELEASE);

        ret.put("manufacturer", Build.MANUFACTURER == null ? "UNKNOWN"
                : Build.MANUFACTURER);
        // ret.put("brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
        ret.put("deviceModel", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);
        ret.put("deviceId", mSystemInformation.getDeviceId());

        DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
        // ret.put("screenDpi", displayMetrics.densityDpi);
        ret.put("screenHeight", displayMetrics.heightPixels);
        ret.put("screenWidth", displayMetrics.widthPixels);
        StringBuilder resolutionBuilder = new StringBuilder();
        resolutionBuilder.append(displayMetrics.widthPixels);
        resolutionBuilder.append("*");
        resolutionBuilder.append(displayMetrics.heightPixels);
        ret.put("resolution", resolutionBuilder.toString());

        String applicationVersionName = mSystemInformation.getAppVersionName();

        if (null != applicationVersionName) {
            ret.put("appVersion", applicationVersionName);
        } else {
            ret.put("appVersion", "UNKNOWN");
        }

        String carrier = mSystemInformation.getCurrentNetworkOperator();
        if (null != carrier && carrier.length() > 0) {
            ret.put("carrierName", carrier);
        } else {
            ret.put("carrierName", "UNKNOWN");
        }

        Boolean isWifi = mSystemInformation.isWifiConnected();
        if (null != isWifi) {
            if (isWifi.booleanValue()) {
                ret.put("networkType", "WIFI");
            } else {
                ret.put("networkType", "NOT WIFI");
            }
        } else {
            ret.put("networkType", "UNKNOWN");
        }

        ret.put("languageCode", mContext.getResources().getConfiguration().locale.getCountry());

        // MDN
//		String permission = "android.permission.READ_PHONE_STATE";

//        int res = this.mContext.checkCallingOrSelfPermission("android.permission.READ_PHONE_STATE");
//        if (res == PackageManager.PERMISSION_GRANTED) {
//            TelephonyManager tMgr = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
//            String MDN = tMgr.getLine1Number();
//            if (MDN == null) {
//                MDN = "";
//            }
//            ret.put("MDN", MDN);
//        } else {
//            ret.put("MDN", "NO PERMISSION");
//        }


        return ret;
    }


    private void readSuperProperties() {
        String props = mStoredPreferences.getString("super_properties", "{}");
        if (RKConfig.DEBUG)
            Log.d(LOGTAG, "Loading Super Properties " + props);

        try {
            mSuperProperties = new JSONObject(props);
        } catch (JSONException e) {
            Log.e(LOGTAG, "Cannot parse stored superProperties");
            mSuperProperties = new JSONObject();
            storeSuperProperties();
        }
    }

    private void storeSuperProperties() {
        String props = mSuperProperties.toString();

        if (RKConfig.DEBUG)
            Log.d(LOGTAG, "Storing Super Properties " + props);
        SharedPreferences.Editor prefsEditor = mStoredPreferences.edit();
        prefsEditor.putString("super_properties", props);
        prefsEditor.commit();
    }


    private static final String LOGTAG = "RakeAPI";

    private static final DateFormat baseTimeFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");

    static {
        baseTimeFormat.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
    }

    private static final DateFormat localTimeFormat = new SimpleDateFormat("yyyyMMddHHmmssSSS");


    // Maps each token to a singleton RakeAPI instance
    private static Map<String, Map<Context, RakeAPI>> sInstanceMap = new HashMap<String, Map<Context, RakeAPI>>();


    private final Context mContext;
    private final SystemInformation mSystemInformation;
    private final AnalyticsMessages mMessages;

    private final String mToken;

    private final SharedPreferences mStoredPreferences;

    // Persistent members. These are loaded and stored from our preferences.
    private JSONObject mSuperProperties;
}
