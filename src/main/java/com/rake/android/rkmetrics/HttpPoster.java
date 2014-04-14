package com.rake.android.rkmetrics;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;


import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;


import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import android.util.Log;


import com.rake.android.util.Base64Coder;
import com.rake.android.util.StringUtils;

//import de.jarnbjo.jsnappy.SnappyCompressor;
//import de.jarnbjo.jsnappy.Buffer;
import org.json.JSONException;
import org.json.JSONObject;

public class HttpPoster {

    private static final String LOGTAG = "RakeAPI";
    private final String mDefaultHost;

    public static enum PostResult {
        // The post was sent and understood by the Rake service.
        SUCCEEDED,

        // The post couldn't be sent (for example, because there was no connectivity)
        // but might work later.
        FAILED_RECOVERABLE,

        // The post itself is bad/unsendable (for example, too big for system memory)
        // and shouldn't be retried.
        FAILED_UNRECOVERABLE
    }

    ;

    public HttpPoster(String defaultHost) {
        mDefaultHost = defaultHost;
    }

    // Will return true only if the request was successful
    public PostResult postData(String rawMessage, String endpointPath) {
        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
        String encodedData = null;
        String compress = null;

//        Buffer compressedBuffer = SnappyCompressor.compress(rawMessage.getBytes());
//        if (rawMessage.length() > compressedBuffer.getLength()) {
//            compress = "snappy";
//            encodedData = new String(Base64Coder.encode(compressedBuffer.toByteArray()));
//        } else {
        compress = "plain";
        encodedData = Base64Coder.encodeString(rawMessage);
//        }

        nameValuePairs.add(new BasicNameValuePair("compress", compress));
        nameValuePairs.add(new BasicNameValuePair("data", encodedData));

        String defaultUrl = mDefaultHost + endpointPath;
        PostResult ret = postHttpRequest(defaultUrl, nameValuePairs);

        return ret;
    }

    public PostResult postHttpValidationRequest(String log, String schemaId, String ssToken, String endpointPath) {

        String defaultUrl = mDefaultHost + endpointPath;

        Log.d("postHttpValidationRequest", defaultUrl);

        PostResult ret = PostResult.FAILED_UNRECOVERABLE;

        HttpParams params = setParamsTimeout();
        HttpClient httpclient = new DefaultHttpClient(params);


        HttpPost httppost = new HttpPost(defaultUrl);
        httppost.setHeader("Accept", "application/json");
        httppost.setHeader("Accept-Encoding", "gzip");
        httppost.setHeader("Content-type", "application/json");

        JSONObject valObj = new JSONObject();

        try {
            valObj.put("log", log);
            valObj.put("_$schemaId", schemaId);
            valObj.put("_$ssToken", ssToken);

            StringEntity se = new StringEntity(valObj.toString());
            httppost.setEntity(se);

            HttpResponse response = httpclient.execute(httppost);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                String result = StringUtils.inputStreamToString(entity.getContent());
                if (result.equals("1\n")) {
                    ret = PostResult.SUCCEEDED;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (ClientProtocolException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ret;
    }


    // by lons
    public HttpParams setParamsTimeout() {
        HttpParams httpParameters = new BasicHttpParams();
        int timeoutConnection = 3000;
        HttpConnectionParams.setConnectionTimeout(httpParameters, timeoutConnection);
        int timeoutSocket = 120000;
        HttpConnectionParams.setSoTimeout(httpParameters, timeoutSocket);
        return httpParameters;
    }


    private PostResult postHttpRequest(String endpointUrl, List<NameValuePair> nameValuePairs) {
        PostResult ret = PostResult.FAILED_UNRECOVERABLE;

        HttpParams params = setParamsTimeout();
        HttpClient httpclient = new DefaultHttpClient(params);

        //LONS: 
        if (endpointUrl.indexOf("https") >= 0 && RakeConfig.TRUSTED_SERVER) {
            httpclient = sslClientDebug(httpclient);
        }

        HttpPost httppost = new HttpPost(endpointUrl);

        try {

            httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs));

            HttpResponse response = httpclient.execute(httppost);
            HttpEntity entity = response.getEntity();

            if (entity != null) {
                String result = StringUtils.inputStreamToString(entity.getContent());
                if (result.equals("1\n")) {
                    ret = PostResult.SUCCEEDED;
                }
            }
        } catch (IOException e) {
            Log.i(LOGTAG, "Cannot post message to Rake Servers (May Retry)", e);
            ret = PostResult.FAILED_RECOVERABLE;
        } catch (OutOfMemoryError e) {
            Log.e(LOGTAG, "Cannot post message to Rake Servers, will not retry.", e);
            ret = PostResult.FAILED_UNRECOVERABLE;
        }

        return ret;
    }


    private HttpClient sslClientDebug(HttpClient client) {
        try {
            X509TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                }

                public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            SSLSocketFactory ssf = new MySSLSocketFactory(ctx);
            ssf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
            ClientConnectionManager ccm = client.getConnectionManager();
            SchemeRegistry sr = ccm.getSchemeRegistry();
            sr.register(new Scheme("https", ssf, 443));
            return new DefaultHttpClient(ccm, client.getParams());
        } catch (Exception ex) {
            return null;
        }
    }

    public class MySSLSocketFactory extends SSLSocketFactory {
        SSLContext sslContext = SSLContext.getInstance("TLS");

        public MySSLSocketFactory(KeyStore truststore) throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException {
            super(truststore);

            TrustManager tm = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            };

            sslContext.init(null, new TrustManager[]{tm}, null);
        }

        public MySSLSocketFactory(SSLContext context) throws KeyManagementException, NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException {
            super(null);
            sslContext = context;
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException, UnknownHostException {
            return sslContext.getSocketFactory().createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket() throws IOException {
            return sslContext.getSocketFactory().createSocket();
        }
    }
}