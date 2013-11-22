package com.mixpanel.android.mpmetrics;

import java.io.IOException;
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
import javax.net.ssl.SSLSocket;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.conn.ssl.X509HostnameVerifier;

import javax.net.ssl.*;


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


import com.mixpanel.android.util.Base64Coder;
import com.mixpanel.android.util.StringUtils;

import de.jarnbjo.jsnappy.SnappyCompressor;
import de.jarnbjo.jsnappy.SnappyDecompressor;
import de.jarnbjo.jsnappy.Buffer;

import java.util.Arrays;

/* package */ class HttpPoster {

    private static final String LOGTAG = "MixpanelAPI";
    private final String mDefaultHost;
    private final String mFallbackHost;

    public static enum PostResult {
        // The post was sent and understood by the Mixpanel service.
        SUCCEEDED,

        // The post couldn't be sent (for example, because there was no connectivity)
        // but might work later.
        FAILED_RECOVERABLE,

        // The post itself is bad/unsendable (for example, too big for system memory)
        // and shouldn't be retried.
        FAILED_UNRECOVERABLE
    };

    public HttpPoster(String defaultHost, String fallbackHost) {
        mDefaultHost = defaultHost;
        mFallbackHost = fallbackHost;
    }

    // Will return true only if the request was successful
    public PostResult postData(String rawMessage, String endpointPath) {
        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2);
        String encodedData = null;
        String compress = null;

        Buffer compressedBuffer = SnappyCompressor.compress(rawMessage.getBytes());
        if(rawMessage.length() > compressedBuffer.getLength()){
            compress = "snappy";
            encodedData = new String(Base64Coder.encode(compressedBuffer.toByteArray()));
        }else{
            compress = "plain";
            encodedData = Base64Coder.encodeString(rawMessage);
        }

        nameValuePairs.add(new BasicNameValuePair("compress",compress));
        nameValuePairs.add(new BasicNameValuePair("data", encodedData));

        String defaultUrl = mDefaultHost + endpointPath;
        PostResult ret = postHttpRequest(defaultUrl, nameValuePairs);

        if (ret == PostResult.FAILED_RECOVERABLE && mFallbackHost != null) {
            String fallbackUrl = mFallbackHost + endpointPath;
            if (MPConfig.DEBUG) Log.i(LOGTAG, "Retrying post with new URL: " + fallbackUrl);
            ret = postHttpRequest(fallbackUrl, nameValuePairs);
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
        if(endpointUrl.indexOf("https") >= 0 && MPConfig.TRUSTED_SERVER) {
        	//Log.d(LOGTAG, "https client changed by lons : ssl client for debuging");
        	httpclient = sslClientDebug(httpclient);
        } else {
        	//Log.d(LOGTAG, "original https client used");	
        }        
        
        HttpPost httppost = new HttpPost(endpointUrl);

        try {

            String urlEncoded = StringUtils.inputStreamToString(new UrlEncodedFormEntity(nameValuePairs).getContent());
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
            Log.i(LOGTAG, "Cannot post message to Mixpanel Servers (May Retry)", e);
            ret = PostResult.FAILED_RECOVERABLE;
        } catch (OutOfMemoryError e) {
            Log.e(LOGTAG, "Cannot post message to Mixpanel Servers, will not retry.", e);
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

            sslContext.init(null, new TrustManager[] { tm }, null);
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
