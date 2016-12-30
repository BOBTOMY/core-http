package com.joy.http.volley;

import android.text.TextUtils;

import com.alibaba.fastjson.JSON;
import com.android.volley.AuthFailureError;
import com.android.volley.Cache.Entry;
import com.android.volley.DefaultRetryPolicy;
import com.android.volley.NetworkResponse;
import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.VolleyLog;
import com.android.volley.toolbox.HttpHeaderParser;
import com.joy.http.JoyError;
import com.joy.http.JoyHttp;
import com.joy.http.RequestMode;
import com.joy.http.ResponseListener;
import com.joy.http.utils.ParamsUtil;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;

import static com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT;
import static com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES;
import static com.joy.http.RequestMode.REFRESH_ONLY;

/**
 * Created by KEVIN.DAI on 15/7/10.
 */
public class ObjectRequest<T> extends Request<T> {

    /**
     * The default socket timeout in milliseconds
     */
    private static final int DEFAULT_TIMEOUT_MS = 10 * 1000;
    private Class mClazz;
    private ResponseListener<T> mObjRespLis;
    private Map<String, String> mHeaders, mParams;
    private RequestMode mReqMode = RequestMode.REFRESH_ONLY;
    private boolean mHasCache;
    protected Response<T> mObjResp;
    private String mCacheKey;
    private SerializedSubject<T, T> mSubject;

    /**
     * Creates a new request with the given method.
     *
     * @param method the request {@link Method} to use
     * @param url    URL to fetch the Object
     * @param clazz  the Object class to return
     */
    protected ObjectRequest(int method, String url, Class clazz) {
        super(method, url, null);
        mClazz = clazz;
        mHasCache = JoyHttp.getVolleyCache().get(getCacheKey()) != null;
        setShouldCache(false);
        addEntryListener();
        setRetryPolicy(new DefaultRetryPolicy(DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_MULT));

        mSubject = new SerializedSubject<>(PublishSubject.<T>create());
    }

    Observable<T> observable() {
        return mSubject;
    }

    private void addEntryListener() {
        RetroCache cache = (RetroCache) JoyHttp.getVolleyCache();
        if (cache != null) {
            cache.addEntryListener(mOnEntryListener);
        }
    }

    private void removeEntryListener() {
        RetroCache cache = (RetroCache) JoyHttp.getVolleyCache();
        if (cache != null) {
            cache.removeEntryListener(mOnEntryListener);
        }
    }

    private RetroCache.OnEntryListener mOnEntryListener = entry -> {
        if (entry != null) {
            entry.setRequestMode(mReqMode);
        }
    };

    /**
     * Creates a new GET request.
     *
     * @param url   URL to fetch the Object
     * @param clazz the Object class to return
     */
    public static <T> ObjectRequest<T> get(String url, Class clazz) {
        return new <T>ObjectRequest<T>(Method.GET, url, clazz);
    }

    /**
     * Creates a new POST request.
     *
     * @param url   URL to fetch the Object
     * @param clazz the Object class to return
     */
    public static <T> ObjectRequest<T> post(String url, Class clazz) {
        return new <T>ObjectRequest<T>(Method.POST, url, clazz);
    }

    public void setHeaders(Map<String, String> headers) {
        mHeaders = headers;
    }

    public void setParams(Map<String, String> params) {
        mParams = params;
    }

    @Override
    public Map<String, String> getHeaders() throws AuthFailureError {
        if (mHeaders != null && !mHeaders.isEmpty()) {
            return mHeaders;
        }
        return super.getHeaders();
    }

    @Override
    protected Map<String, String> getParams() throws AuthFailureError {
        if (mParams != null && !mParams.isEmpty()) {
            return mParams;
        }
        return super.getParams();
    }

    public void setRequestMode(RequestMode mode) {
        mReqMode = mode;
        setShouldCache(mode != REFRESH_ONLY);
    }

    public RequestMode getRequestMode() {
        return mReqMode;
    }

    public boolean hasCache() {
        return mHasCache;
    }

    /**
     * @return True if this response was a soft-expired one and a second one MAY be coming.
     */
    public boolean isFinalResponse() {
        return mObjResp != null && !mObjResp.intermediate;
    }

    /**
     * @param lisn Listener to receive the Object response
     */
    public void setResponseListener(ResponseListener<T> lisn) {
        mObjRespLis = lisn;
    }

    @Override
    protected void deliverResponse(T t) {
        if (mObjRespLis != null) {
            mObjRespLis.onSuccess(getTag(), isTestMode() ? this.t : t);
        }
        mSubject.onNext(t);
    }

    @Override
    public void deliverError(VolleyError error) {
        if (isTestMode()) {
            deliverResponse(t);
        } else {
            JoyError e = JoyError.empty();
            if (error != null) {
                NetworkResponse nr = error.networkResponse;
                if (nr != null) {
                    e = new JoyError(nr.statusCode, new String(nr.data));
                } else {
                    e = new JoyError(JoyError.STATUS_NONE, ErrorHelper.getErrorType(error));
                }
            }
            if (mObjRespLis != null) {
                mObjRespLis.onError(getTag(), e);
            }
            mSubject.onError(e);
        }
    }

    @Override
    public Response<T> parseNetworkResponse(NetworkResponse response) {
        String parsed = parseJsonResponse(response);
        Entry entry = HttpHeaderParser.parseCacheHeaders(response);
        return mObjResp = Response.success(shift(parsed), entry);
    }

    protected String parseJsonResponse(NetworkResponse response) {
        String parsed;
        try {
            String charsetName = HttpHeaderParser.parseCharset(response.headers);
            parsed = new String(response.data, charsetName);
        } catch (UnsupportedEncodingException e) {
            parsed = new String(response.data);
            e.printStackTrace();
        }
        return parsed;
    }

    protected T shift(String json) {
        T t = null;
        try {
            if (TextUtils.isEmpty(json)) {
                t = (T) mClazz.newInstance();
            } else {
                if (mClazz.newInstance() instanceof String) {
                    t = (T) json;
                } else {
                    if (json.startsWith("[")) {// JsonArray
                        t = ((T) JSON.parseArray(json, mClazz));
                    } else {// JsonObj
                        t = (T) JSON.parseObject(json, mClazz);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return t;
    }

    @Override
    protected void onFinish() {
        if (VolleyLog.DEBUG) {
            VolleyLog.d("~~Finished # tag: %s", getTag());
        }

        mSubject.onCompleted();

        t = null;
        mClazz = null;
        mHasCache = false;
        mObjRespLis = null;
        mObjResp = null;
        mCacheKey = null;

        removeEntryListener();

        if (mHeaders != null) {
            mHeaders.clear();
            mHeaders = null;
        }
        if (mParams != null) {
            mParams.clear();
            mParams = null;
        }
    }

    // --- for test data ---
    private T t;

    public void setTestData(T t) {
        this.t = t;
    }

    public void setTestData(String json) {
        setTestData(shift(json));
    }

    private boolean isTestMode() {
        return t != null;
    }

    @Override
    public void cancel() {
        super.cancel();
        if (VolleyLog.DEBUG) {
            VolleyLog.d("~~Canceled # tag: %s", getTag());
        }
    }

    public void setCacheKey(String cacheKey) {
        mCacheKey = cacheKey;
    }

    @Override
    public String getCacheKey() {
        if (getMethod() == Method.POST) {
            return Method.POST + ":" + getOriginUrl() + "?" + ParamsUtil.createUrl(mParams);
        }
        return TextUtils.isEmpty(mCacheKey) ? super.getCacheKey() : mCacheKey;
    }
}
