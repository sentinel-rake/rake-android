package com.rake.android.rkmetrics;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Manage communication of events with the internal database and the Rake servers.
 * <p/>
 * <p>This class straddles the thread boundary between user threads and
 * a logical Rake thread.
 */
/* package */ class AnalyticsMessages {
    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(Context context) {
        mContext = context;
        mLogRakeMessages = new AtomicBoolean(false);
        mWorker = new Worker();
    }

    public static AnalyticsMessages getInstance(Context messageContext) {
        synchronized (sInstances) {
            Context appContext = messageContext.getApplicationContext();
            AnalyticsMessages ret;
            if (!sInstances.containsKey(appContext)) {
                if (RakeConfig.DEBUG) Log.d(LOGTAG, "Constructing new AnalyticsMessages for Context " + appContext);
                ret = new AnalyticsMessages(appContext);
                sInstances.put(appContext, ret);
            } else {
                if (RakeConfig.DEBUG)
                    Log.d(LOGTAG, "AnalyticsMessages for Context " + appContext + " already exists- returning");
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    public void eventsMessage(JSONObject eventsJson) {
        Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventsJson;

        mWorker.runMessage(m);
    }

    public void postToServer() {
        Message m = Message.obtain();
        m.what = FLUSH_QUEUE;

        mWorker.runMessage(m);
    }

    public void setFlushInterval(long milliseconds) {
        Message m = Message.obtain();
        m.what = SET_FLUSH_INTERVAL;
        m.obj = new Long(milliseconds);

        mWorker.runMessage(m);
    }

    public void setFallbackHost(String fallbackHost) {
        Message m = Message.obtain();
        m.what = SET_FALLBACK_HOST;
        m.obj = fallbackHost;

        mWorker.runMessage(m);
    }

    public void setEndpointHost(String endpointHost) {
        Message m = Message.obtain();
        m.what = SET_ENDPOINT_HOST;
        m.obj = endpointHost;

        mWorker.runMessage(m);
    }

    public void hardKill() {
        Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected RakeDbAdapter makeDbAdapter(Context context) {
        return new RakeDbAdapter(context);
    }

    protected HttpPoster getPoster(String endpointBase) {
        return new HttpPoster(endpointBase);
    }

    ////////////////////////////////////////////////////

    // Sends a message if and only if we are running with Rake Message log enabled.
    // Will be called from the Rake thread.
    private void logAboutMessageToRake(String message) {
        if (mLogRakeMessages.get() || RakeConfig.DEBUG) {
            Log.i(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
        }
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    private class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        public boolean isDead() {
            synchronized (mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            if (isDead()) {
                // We died under suspicious circumstances. Don't try to send any more events.
                logAboutMessageToRake("Dead rake worker dropping a message: " + msg);
            } else {
                synchronized (mHandlerLock) {
                    if (mHandler != null) mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        private Handler restartWorkerThread() {
            Handler ret = null;

            final SynchronousQueue<Handler> handlerQueue = new SynchronousQueue<Handler>();

            Thread thread = new Thread() {
                @Override
                public void run() {
                    if (RakeConfig.DEBUG)
                        Log.i(LOGTAG, "Starting worker thread " + this.getId());

                    Looper.prepare();

                    try {
                        handlerQueue.put(new AnalyticsMessageHandler());
                    } catch (InterruptedException e) {
                        throw new RuntimeException("Couldn't build worker thread for Analytics Messages", e);
                    }

                    try {
                        Looper.loop();
                    } catch (RuntimeException e) {
                        Log.e(LOGTAG, "Rake Thread dying from RuntimeException", e);
                    }
                }
            };
            thread.setPriority(Thread.MIN_PRIORITY);
            thread.start();

            try {
                ret = handlerQueue.take();
            } catch (InterruptedException e) {
                throw new RuntimeException("Couldn't retrieve handler from worker thread");
            }

            return ret;
        }

        private class AnalyticsMessageHandler extends Handler {
            public AnalyticsMessageHandler() {
                super();
                mDbAdapter = makeDbAdapter(mContext);
                mDbAdapter.cleanupEvents(System.currentTimeMillis() - RakeConfig.DATA_EXPIRATION, RakeDbAdapter.Table.EVENTS);
            }

            @Override
            public void handleMessage(Message msg) {
                try {
                    int queueDepth = -1;

                    if (msg.what == SET_FLUSH_INTERVAL) {
                        Long newIntervalObj = (Long) msg.obj;
                        logAboutMessageToRake("Changing flush interval to " + newIntervalObj);
                        mFlushInterval = newIntervalObj.longValue();
                        removeMessages(FLUSH_QUEUE);
                    } else if (msg.what == SET_ENDPOINT_HOST) {
                        logAboutMessageToRake("Setting endpoint API host to " + mEndpointHost);
                        mEndpointHost = msg.obj == null ? null : msg.obj.toString();
                    } else if (msg.what == ENQUEUE_EVENTS) {
                        JSONObject message = (JSONObject) msg.obj;

                        logAboutMessageToRake("Queuing event for sending later");
                        logAboutMessageToRake("    " + message.toString());

                        queueDepth = mDbAdapter.addJSON(message, RakeDbAdapter.Table.EVENTS);
                    } else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToRake("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        sendAllData();
                    } else if (msg.what == KILL_WORKER) {
                        Log.w(LOGTAG, "Worker recieved a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized (mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else {
                        Log.e(LOGTAG, "Unexpected message recieved by Rake worker: " + msg);
                    }

                    ///////////////////////////

                    if (queueDepth >= RakeConfig.BULK_UPLOAD_LIMIT) {
                        logAboutMessageToRake("Flushing queue due to bulk upload limit");
                        updateFlushFrequency();
                        sendAllData();
                    } else if (queueDepth > 0) {
                        if (!hasMessages(FLUSH_QUEUE)) {
                            logAboutMessageToRake("Queue depth " + queueDepth + " - Adding flush in " + mFlushInterval);
                            // The hasMessages check is a courtesy for the common case
                            // of delayed flushes already enqueued from inside of this thread.
                            // Callers outside of this thread can still send
                            // a flush right here, so we may end up with two flushes
                            // in our queue, but we're ok with that.
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                } catch (RuntimeException e) {
                    Log.e(LOGTAG, "Worker threw an unhandled exception- will not send any more Rake messages", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                        } catch (Exception tooLate) {
                            Log.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                    throw e;
                }
            }// handleMessage

            private void sendAllData() {
                logAboutMessageToRake("Sending records to Rake");

                sendData(RakeDbAdapter.Table.EVENTS, "/track");
            }

            private void sendData(RakeDbAdapter.Table table, String endpointUrl) {
                String[] eventsData = mDbAdapter.generateDataString(table);

                if (eventsData != null) {
                    String lastId = eventsData[0];
                    String rawMessage = eventsData[1];

                    // check rawMessageLength

                    HttpPoster poster = getPoster(mEndpointHost);
                    HttpPoster.PostResult eventsPosted = poster.postData(rawMessage, endpointUrl);

                    if (eventsPosted == HttpPoster.PostResult.SUCCEEDED) {
                        logAboutMessageToRake("Posted to " + endpointUrl);
                        mDbAdapter.cleanupEvents(lastId, table);
                    } else if (eventsPosted == HttpPoster.PostResult.FAILED_RECOVERABLE) {
                        // Try again later
                        if (!hasMessages(FLUSH_QUEUE)) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    } else { // give up, we have an unrecoverable failure.
                        mDbAdapter.cleanupEvents(lastId, table);
                    }
                }
            }

            private String mEndpointHost = RakeConfig.BASE_ENDPOINT;
            private final RakeDbAdapter mDbAdapter;
        }// AnalyticsMessageHandler

        private void updateFlushFrequency() {
            long now = System.currentTimeMillis();
            long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                long flushInterval = now - mLastFlushTime;
                long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToRake("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        private Handler mHandler;

        private long mFlushInterval = RakeConfig.FLUSH_RATE;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
    }

    /////////////////////////////////////////////////////////

    // Used across thread boundaries
    private final AtomicBoolean mLogRakeMessages;
    private final Worker mWorker;
    private final Context mContext;

    // Messages for our thread
    private static int ENQUEUE_PEOPLE = 0; // submit events and people data
    private static int ENQUEUE_EVENTS = 1; // push given JSON message to people DB
    private static int FLUSH_QUEUE = 2; // push given JSON message to events DB
    private static int SET_FLUSH_INTERVAL = 4; // Reset frequency of flush interval
    private static int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue.
    private static int SET_ENDPOINT_HOST = 6; // Use obj.toString() as the first part of the URL for api requests.
    private static int SET_FALLBACK_HOST = 7; // Use obj.toString() as the (possibly null) string for api fallback requests.

    private static final String LOGTAG = "RakeAPI";

    private static final Map<Context, AnalyticsMessages> sInstances = new HashMap<Context, AnalyticsMessages>();
}
