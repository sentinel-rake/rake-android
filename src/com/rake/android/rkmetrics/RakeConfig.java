package com.rake.android.rkmetrics;

/**
 * Stores global configuration options for the Rake library.
 * May be overridden to achieve custom behavior.
 */
/* package */
public class RakeConfig {
    // When we've reached this many track calls, flush immediately
    public static final int BULK_UPLOAD_LIMIT = 40;

    // Time interval in ms events/people requests are flushed at.
    public static final long FLUSH_RATE = 60 * 1000;

    // Remove events that have sat around for this many milliseconds
    // on first initialization of the library. Default is 48 hours.
    // Must be reconfigured before the library is initialized for the first time.
    public static final int DATA_EXPIRATION = 1000 * 60 * 60 * 48;


    public static String BASE_ENDPOINT = "https://rake.skplanet.com:8443/log";
    public static String DEV_BASE_ENDPOINT = "http://dev.rake.skplanet.com:8000/log/";


    public static final int SUBMIT_THREAD_TTL = 60 * 1000;


    // LONS
    public static boolean DEBUG = false;
    // LONS
    public static boolean TRUSTED_SERVER = false;
    // LONS
//    public static int CONNECTION_TIMEOUT = 5000;
//    public static int SO_TIMEOUT = 5000;


    public static String SENTINEL_REMOTE_SERVER = "http://211.110.43.80:8080";
//    public static String SENTINEL_REMOTE_SERVER = "http://sentinel.skplanet.co.kr:8080";

}
