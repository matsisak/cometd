/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.javascript;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BlockingResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XMLHttpRequestExchange extends ScriptableObject
{
    private CometDExchange exchange;

    public XMLHttpRequestExchange()
    {
    }

    public void jsConstructor(Object client, Object cookieStore, Object threadModel, Scriptable thiz, String method, String url, boolean async)
    {
        Request theRequest = ((XMLHttpRequestClient)client).getHttpClient().newRequest(url);
        exchange = new CometDExchange(theRequest, (HttpCookieStore)cookieStore, (ThreadModel)threadModel, thiz, method, url, async);
    }
    
    public String getClassName()
    {
        return "XMLHttpRequestExchange";
    }

    public boolean isAsynchronous()
    {
        return exchange.isAsynchronous();
    }

    public void await() throws InterruptedException, ExecutionException
    {
        exchange.get();
        exchange.notifyReadyStateChange(false);
    }

    public void jsFunction_addRequestHeader(String name, String value)
    {
        exchange.getRequest().header(name, value);
    }

    public String jsGet_method()
    {
        return exchange.getRequest().getMethod().asString();
    }

    public void jsFunction_setRequestContent(String data) throws UnsupportedEncodingException
    {
        exchange.setRequestContent(data);
    }

    public int jsGet_readyState()
    {
        return exchange.getReadyState();
    }

    public String jsGet_responseText()
    {
        return exchange.getResponseText();
    }

    public int jsGet_responseStatus()
    {
        return exchange.getResponseStatus();
    }

    public String jsGet_responseStatusText()
    {
        return exchange.getResponseStatusText();
    }

    public void jsFunction_abort()
    {
        exchange.abort();
    }

    public String jsFunction_getAllResponseHeaders()
    {
        return exchange.getAllResponseHeaders();
    }

    public String jsFunction_getResponseHeader(String name)
    {
        return exchange.getResponseHeader(name);
    }

    public void jsFunction_send() throws Exception
    {
        exchange.send();
        try
        {
            if (!isAsynchronous())
                await();
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
        catch (ExecutionException x)
        {
            Throwable cause = x.getCause();
            if (cause instanceof Exception)
                throw (Exception)cause;
            else
                throw (Error)cause;
        }
    }

    public static class CometDExchange extends BlockingResponseListener
    {
        public enum ReadyState
        {
            UNSENT, OPENED, HEADERS_RECEIVED, LOADING, DONE
        }

        private final Logger logger = LoggerFactory.getLogger(getClass().getName());
        private final HttpCookieStore cookieStore;
        private final ThreadModel threads;
        private final Scriptable thiz;
        private final boolean async;
        private final Request request;
        private volatile boolean aborted;
        private volatile ReadyState readyState = ReadyState.UNSENT;
        private volatile String responseText;
        private volatile int responseStatus;
        private volatile String responseStatusText;

        public CometDExchange(Request request, HttpCookieStore cookieStore, ThreadModel threads, Scriptable thiz, String method, String url, boolean async)
        {
            super(request);
            this.request = request.method(HttpMethod.fromString(method));
            this.cookieStore = cookieStore;
            this.threads = threads;
            this.thiz = thiz;
            this.async = async;
            aborted = false;
            readyState = ReadyState.OPENED;
            responseStatusText = null;
            if (async)
                notifyReadyStateChange(false);
        }

        public Request getRequest()
        {
            return this.request;
        }

        public boolean isAsynchronous()
        {
            return async;
        }

        /**
         * If this method is invoked in the same stack of a JavaScript call,
         * then it must be asynchronous.
         * The reason is that the JavaScript may modify the onreadystatechange
         * function afterwards, and we would be notifying the wrong function.
         *
         * @param sync whether the call should be synchronous
         */
        private void notifyReadyStateChange(boolean sync)
        {
            threads.invoke(sync, thiz, thiz, "onreadystatechange");
        }

        public void send() throws Exception
        {
            log("Submitted {}", this);
            getRequest().send(this);
        }

        public void abort()
        {
            cancel(false);
            log("Aborted {}", this);
            aborted = true;
            responseText = null;
            getRequest().getHeaders().clear();
            if (!async || readyState == ReadyState.HEADERS_RECEIVED || readyState == ReadyState.LOADING)
            {
                readyState = ReadyState.DONE;
                notifyReadyStateChange(false);
            }
            readyState = ReadyState.UNSENT;
        }

        public int getReadyState()
        {
            return readyState.ordinal();
        }

        public String getResponseText()
        {
            return responseText;
        }

        public int getResponseStatus()
        {
            return responseStatus;
        }

        public String getResponseStatusText()
        {
            return responseStatusText;
        }

        public void setRequestContent(String content) throws UnsupportedEncodingException
        {
            getRequest().content(new StringContentProvider(content));
        }

        public String getAllResponseHeaders()
        {
            return getRequest().getHeaders().toString();
        }

        public String getResponseHeader(String name)
        {
            return getRequest().getHeaders().getStringField(name);
        }

        @Override
        public void onBegin(Response response)
        {
            super.onBegin(response);
            this.responseStatus = response.getStatus();
            this.responseStatusText = response.getReason();
        }

        @Override
        public void onHeaders(Response response)
        {
            super.onHeaders(response);
            if (!aborted)
            {
                if (async)
                {
                    readyState = ReadyState.HEADERS_RECEIVED;
                    notifyReadyStateChange(true);
                }
            }
        }

        @Override
        public void onContent(Response response, ByteBuffer content)
        {
            super.onContent(response, content);
            if (!aborted)
            {
                if (async)
                {
                    if (readyState != ReadyState.LOADING)
                    {
                        readyState = ReadyState.LOADING;
                        notifyReadyStateChange(true);
                    }
                }
            }
        }

        @Override
        public void onComplete(Result result)
        {
            if (!aborted)
            {
                if (result.isSucceeded())
                {
                    Response response = result.getResponse();
                    log("Succeeded ({}) {}", response.getStatus(), this);
                    responseText = getContentAsString();
                    readyState = ReadyState.DONE;
                    if (async)
                        notifyReadyStateChange(true);
                }
                else
                {
                    Throwable failure = result.getFailure();
                    if (!(failure instanceof EOFException))
                        log("Failed " + this, failure);
                }
            }
        }

        private void log(String message, Object... args)
        {
            if (Boolean.getBoolean("debugTests"))
                logger.info(message, args);
            else
                logger.debug(message, args);
        }
    }
}
