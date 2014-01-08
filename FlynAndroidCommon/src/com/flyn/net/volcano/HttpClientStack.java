package com.flyn.net.volcano;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.zip.GZIPInputStream;

import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRouteBean;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.HttpEntityWrapper;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.DefaultRedirectHandler;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.SyncBasicHttpContext;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.flyn.net.volcano.Request.Method;

public class HttpClientStack implements NetStack
{

    private static final int                        DEFAULT_MAX_CONNETIONS          = 10;
    private static final int                        DEFAULT_SOCKET_TIMEOUT          = 10 * 1000;
    private static final int                        DEFAULT_MAX_RETRIES             = 3;
    private static final int                        DEFAULT_RETRY_SLEEP_TIME_MILLIS = 1500;
    private static final int                        DEFAULT_SOCKET_BUFFER_SIZE      = 8192;
    private static final String                     HEADER_ACCEPT_ENCODING          = "Accept-Encoding";
    private static final String                     ENCODING_GZIP                   = "gzip";
    private static final String                     TAG                             = HttpClientStack.class.getName();
    private final boolean                           fixNoHttpResponseException      = false;
    private static int                              httpPort                        = 80;
    private static int                              httpsPort                       = 443;

    private int                                     maxConnections                  = DEFAULT_MAX_CONNETIONS;
    private int                                     timeout                         = DEFAULT_SOCKET_TIMEOUT;

    private final DefaultHttpClient                 httpClient;
    private final HttpContext                       httpContext;
    private ExecutorService                         threadPool;
    private final Map<Context, List<RequestFuture>> requestMap;
    private final Map<String, String>               httpHeaderMap;
    private boolean                                 isURLEncodingEnabled            = true;

    public HttpClientStack()
    {
        BasicHttpParams httpParams = new BasicHttpParams();

        ConnManagerParams.setTimeout(httpParams, timeout);
        ConnManagerParams.setMaxConnectionsPerRoute(httpParams, new ConnPerRouteBean(maxConnections));
        ConnManagerParams.setMaxTotalConnections(httpParams, DEFAULT_MAX_CONNETIONS);

        HttpConnectionParams.setSoTimeout(httpParams, timeout);
        HttpConnectionParams.setConnectionTimeout(httpParams, timeout);
        HttpConnectionParams.setTcpNoDelay(httpParams, true);// 禁用nagle算法,排除对小封包的处理(降低延迟)
        HttpConnectionParams.setSocketBufferSize(httpParams, DEFAULT_SOCKET_BUFFER_SIZE);

        HttpProtocolParams.setVersion(httpParams, HttpVersion.HTTP_1_1);
        HttpProtocolParams.setUserAgent(httpParams, "Mozilla/5.0(Linux;U;Android 2.2.1;en-us;Nexus One Build.FRG83) " + "AppleWebKit/553.1(KHTML,like Gecko) Version/4.0 Mobile Safari/533.1");

        ThreadSafeClientConnManager cm = new ThreadSafeClientConnManager(httpParams, getDefaultSchemeRegistry());

        this.threadPool = Executors.newCachedThreadPool();
        this.requestMap = new WeakHashMap<Context, List<RequestFuture>>();
        this.httpHeaderMap = new HashMap<String, String>();
        this.httpContext = new SyncBasicHttpContext(new BasicHttpContext());
        this.httpClient = new DefaultHttpClient(cm, httpParams);
        this.httpClient.addRequestInterceptor(new HttpRequestInterceptor()
        {
            @Override
            public void process(HttpRequest request, HttpContext context) throws HttpException, IOException
            {
                if (!request.containsHeader(HEADER_ACCEPT_ENCODING))
                {
                    request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
                }
                for (String header : HttpClientStack.this.httpHeaderMap.keySet())
                {
                    request.addHeader(header, HttpClientStack.this.httpHeaderMap.get(header));
                }
            }
        });
        this.httpClient.addResponseInterceptor(new HttpResponseInterceptor()
        {

            @Override
            public void process(HttpResponse response, HttpContext context) throws HttpException, IOException
            {
                final HttpEntity entity = response.getEntity();
                if (null == entity)
                    return;

                final Header encoding = entity.getContentEncoding();
                if (null != encoding)
                {
                    for (HeaderElement element : encoding.getElements())
                    {

                        if (element.getName().equalsIgnoreCase(ENCODING_GZIP))
                        {
                            // 响应内容返回给客户端（通常是浏览器，或者HttpClient等）之前先进行压缩，以此来节省宽带占用
                            response.setEntity(new InflatingEntity(response.getEntity()));
                            break;
                        }
                    }

                }

            }
        });
        this.httpClient.setHttpRequestRetryHandler(new RetryHandler(DEFAULT_MAX_RETRIES, DEFAULT_RETRY_SLEEP_TIME_MILLIS));
    }

    private SchemeRegistry getDefaultSchemeRegistry()
    {
        if (fixNoHttpResponseException)
        {
            Log.d(TAG, "Using the fix is insecure, as it doesn't verify SSL certificates.");
        }
        if (httpPort < 1)
        {
            httpPort = 80;
            Log.d(TAG, "Invalid HTTP port number specified, defaulting to 80");
        }

        if (httpsPort < 1)
        {
            httpsPort = 443;
            Log.d(TAG, "Invalid HTTPS port number specified, defaulting to 443");
        }

        SSLSocketFactory sslSocketFactory;
        if (fixNoHttpResponseException)
            sslSocketFactory = CustomSSLSocketFactory.getFixedSocketFactory();
        else
            sslSocketFactory = SSLSocketFactory.getSocketFactory();

        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), httpPort));
        schemeRegistry.register(new Scheme("https", sslSocketFactory, httpsPort));

        return schemeRegistry;
    }

    @Override
    public RequestFuture makeRequest(int method, Context context, String contentType, String url, Map<String, String> headers, RequestParams params, IResponseHandler responseHandler)
    {
        RequestFuture requestHandle = null;

        switch (method)
        {
            case Method.GET:
                requestHandle = get(context, url, headers, params, responseHandler);
                break;
            case Method.POST:
                requestHandle = post(context, url, headers, params, contentType, responseHandler);
                break;
            case Method.PUT:
                requestHandle = put(context, url, headers, params, contentType, responseHandler);
                break;
            case Method.DELETE:
                requestHandle = delete(context, url, headers, params, responseHandler);
                break;
            case Method.HEAD:
                requestHandle = head(context, url, headers, params, contentType, responseHandler);
                break;
            default:
                throw new IllegalStateException("Unknown request method.");
        }
        return requestHandle;
    }

    public RequestFuture get(Context context, String url, Map<String, String> headers, RequestParams params, IResponseHandler responseHandler)
    {
        HttpGet request = new HttpGet(getUrlWithParams(this.isURLEncodingEnabled, url, params));
        addHeaders(request, headers);
        return sendRequest(context, null, responseHandler, prepareArgument(this.httpClient, this.httpContext, request));

    }

    public RequestFuture post(Context context, String url, Map<String, String> headers, RequestParams params, String contentType, IResponseHandler responseHandler)
    {
        HttpPost request = new HttpPost(url);
        if (params != null)
            request.setEntity(paramsToEntity(params, responseHandler));
        addHeaders(request, headers);
        return sendRequest(context, contentType, responseHandler, prepareArgument(this.httpClient, this.httpContext, request));
    }

    public RequestFuture delete(Context context, String url, Map<String, String> headers, RequestParams params, IResponseHandler responseHandler)
    {
        HttpDelete request = new HttpDelete(getUrlWithParams(this.isURLEncodingEnabled, url, params));
        addHeaders(request, headers);
        return sendRequest(context, null, responseHandler, prepareArgument(this.httpClient, this.httpContext, request));
    }

    public RequestFuture put(Context context, String url, Map<String, String> headers, RequestParams params, String contentType, IResponseHandler responseHandler)
    {
        HttpPut request = new HttpPut(url);
        if (null != params)
            request.setEntity(paramsToEntity(params, responseHandler));
        addHeaders(request, headers);
        return sendRequest(context, contentType, responseHandler, prepareArgument(this.httpClient, this.httpContext, request));
    }

    public RequestFuture head(Context context, String url, Map<String, String> headers, RequestParams params, String contentType, IResponseHandler responseHandler)
    {
        HttpUriRequest request = new HttpHead(getUrlWithParams(this.isURLEncodingEnabled, url, params));
        addHeaders(request, headers);
        return sendRequest(context, contentType, responseHandler, prepareArgument(this.httpClient, this.httpContext, request));
    }

    private Object prepareArgument(DefaultHttpClient client, HttpContext httpContext, HttpUriRequest uriRequest)
    {
        return new Object[] { client, httpContext, uriRequest };
    }

    private void addHeaders(HttpUriRequest request, Map<String, String> headers)
    {
        if (headers != null)
        {
            for (Entry<String, String> entry : headers.entrySet())
            {
                request.setHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    @SuppressWarnings("serial")
    @Override
    public RequestFuture sendRequest(Context context, String contentType, IResponseHandler responseHandler, Object... objs)
    {
        final DefaultHttpClient client = (DefaultHttpClient) objs[0];
        final HttpContext httpContext = (HttpContext) objs[1];
        final HttpUriRequest uriRequest = (HttpUriRequest) objs[2];

        if (!TextUtils.isEmpty(contentType))
        {
            uriRequest.setHeader("ContentType", contentType);
        }

        responseHandler.setRequestHeaders(new HashMap<String, String>()
        {
            {
                for (Header header : uriRequest.getAllHeaders())
                {
                    put(header.getName(), header.getValue());
                }
            }
        });
        responseHandler.setRequestURI(uriRequest.getURI());

        Request request = new HttpClientRequest(client, httpContext, uriRequest, responseHandler);
        this.threadPool.submit(request);
        RequestFuture requestHandle = new RequestFuture(request);

        if (null != context)
        {
            List<RequestFuture> list = this.requestMap.get(context);
            if (null == list)
            {
                list = new LinkedList<RequestFuture>();
                this.requestMap.put(context, list);
            }
            list.add(requestHandle);

            Iterator<RequestFuture> iterator = list.iterator();
            while (iterator.hasNext())
            {
                if (requestHandle.shouldBeGarbageCollected())
                    iterator.remove();
            }

        }

        return requestHandle;
    }

    public HttpClient getHttpClient()
    {
        return this.httpClient;
    }

    public HttpContext getHttpContext()
    {
        return this.httpContext;
    }

    public void setCookieStore(CookieStore cookieStore)
    {
        this.httpContext.setAttribute(ClientContext.COOKIE_STORE, cookieStore);
    }

    public void setThreadPool(ThreadPoolExecutor threadPool)
    {
        this.threadPool = threadPool;
    }

    public void setEnableRedirects(final boolean enableRedirects)
    {
        this.httpClient.setRedirectHandler(new DefaultRedirectHandler()
        {
            @Override
            public boolean isRedirectRequested(HttpResponse response, HttpContext context)
            {
                return enableRedirects;
            }

        });
    }

    public void setUserAgent(String userAgent)
    {
        HttpProtocolParams.setUserAgent(this.httpClient.getParams(), userAgent);
    }

    public int getMaxConnections()
    {
        return this.maxConnections;
    }

    public void setMaxConnections(int maxConnections)
    {
        if (maxConnections < 1)
            maxConnections = DEFAULT_MAX_CONNETIONS;
        this.maxConnections = maxConnections;
        final HttpParams httpParams = this.httpClient.getParams();
        ConnManagerParams.setMaxConnectionsPerRoute(httpParams, new ConnPerRouteBean(this.maxConnections));

    }

    public int timeOut()
    {
        return this.timeout;
    }

    public void setTimeOut(int timeout)
    {
        if (timeout < 1000)
            this.timeout = DEFAULT_SOCKET_TIMEOUT;

        final HttpParams httpParams = this.httpClient.getParams();
        ConnManagerParams.setTimeout(httpParams, this.timeout);
        HttpConnectionParams.setConnectionTimeout(httpParams, this.timeout);

    }

    public void setProxy(String hostname, int port)
    {
        final HttpHost proxy = new HttpHost(hostname, port);
        final HttpParams httpParams = this.httpClient.getParams();

        httpParams.setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);

    }

    public void setProxy(String hostname, int port, String username, String password)
    {
        this.httpClient.getCredentialsProvider().setCredentials(new AuthScope(hostname, port), new UsernamePasswordCredentials(username, password));
        final HttpHost proxy = new HttpHost(hostname, port);
        final HttpParams httpParams = this.httpClient.getParams();

        httpParams.setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);

    }

    public void setSSLSocketFactory(SSLSocketFactory sslSocketFactory)
    {
        this.httpClient.getConnectionManager().getSchemeRegistry().register(new Scheme("https", sslSocketFactory, 443));
    }

    public void setMaxRetriesAndTimeout(int retries, int timeout)
    {
        this.httpClient.setHttpRequestRetryHandler(new RetryHandler(retries, timeout));
    }

    public void addHeader(String header, String value)
    {
        this.httpHeaderMap.put(header, value);
    }

    public void removeHeader(String header)
    {
        this.httpHeaderMap.remove(header);

    }

    public void setBasicAuth(String username, String password, AuthScope authScope)
    {

        UsernamePasswordCredentials credentials = new UsernamePasswordCredentials(username, password);
        this.httpClient.getCredentialsProvider().setCredentials(authScope, credentials);

    }

    public void setBasicAuth(String username, String password)
    {
        AuthScope scope = AuthScope.ANY;
        setBasicAuth(username, password, scope);
    }

    public String getUrlWithParams(boolean shouldEncodeUrl, String url, RequestParams params)
    {
        if (shouldEncodeUrl)
        {
            url = url.replace(" ", "%20");
        }
        if (null != params)
        {
            String paramString = params.getParamString();
            if (!url.contains("?"))
            {
                url += "?" + paramString;
            } else
            {
                url += "&" + paramString;
            }
        }

        return url;
    }

    private HttpEntity paramsToEntity(RequestParams params, IResponseHandler responseHandler)
    {
        HttpEntity entity = null;
        try
        {
            if (params != null)
            {
                entity = new ByteArrayEntity(params.getBody(responseHandler));
            }
        } catch (Throwable t)
        {
            if (responseHandler != null)
                responseHandler.sendFailureMessage(0, null, null, t);
            else
                t.printStackTrace();
        }

        return entity;
    }

    public void clearBasicAuth()
    {
        this.httpClient.getCredentialsProvider().clear();
    }

    public void setURLEncodingEnabled(boolean isURLEncodingEnabled)
    {
        this.isURLEncodingEnabled = isURLEncodingEnabled;
    }

    public void cancelRequests(Context context, boolean mayInterruptIfRunning)
    {

        List<RequestFuture> requestList = this.requestMap.get(context);
        if (requestList != null)
        {
            for (RequestFuture requestHandle : requestList)
            {
                requestHandle.cancel(mayInterruptIfRunning);
            }
            this.requestMap.remove(context);
        }
    }

    private static class InflatingEntity extends HttpEntityWrapper
    {

        public InflatingEntity(HttpEntity wrapped)
        {
            super(wrapped);
        }

        @Override
        public InputStream getContent() throws IOException
        {
            return new GZIPInputStream(this.wrappedEntity.getContent());
        }

        @Override
        public long getContentLength()
        {
            return -1;
        }

    }

}