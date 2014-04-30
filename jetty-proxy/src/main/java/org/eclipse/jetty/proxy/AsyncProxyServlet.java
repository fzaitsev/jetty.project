//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritePendingException;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.util.Callback;

public class AsyncProxyServlet extends ProxyServlet
{
    private static final String WRITE_LISTENER_ATTRIBUTE = AsyncProxyServlet.class.getName() + ".writeListener";

    @Override
    protected ContentProvider proxyRequestContent(Request proxyRequest, HttpServletRequest request) throws IOException
    {
        ServletInputStream input = request.getInputStream();
        DeferredContentProvider provider = new DeferredContentProvider();
        input.setReadListener(new StreamReader(input, getRequestId(request), provider));
        return provider;
    }

    @Override
    protected void onResponseContent(HttpServletRequest request, HttpServletResponse response, Response proxyResponse, byte[] buffer, int offset, int length, Callback callback)
    {
        try
        {
            int requestId = getRequestId(request);
            _log.debug("{} proxying content to downstream: {} bytes", requestId, length);
            StreamWriter writeListener = (StreamWriter)request.getAttribute(WRITE_LISTENER_ATTRIBUTE);
            if (writeListener == null)
            {
                writeListener = new StreamWriter(request.getAsyncContext(), requestId);
                request.setAttribute(WRITE_LISTENER_ATTRIBUTE, writeListener);

                // Set the data to write before calling setWriteListener(), because
                // setWriteListener() may trigger the call to onWritePossible() on
                // a different thread and we would have a race.
                writeListener.data(buffer, offset, length, callback);

                // Setting the WriteListener triggers an invocation to onWritePossible().
                response.getOutputStream().setWriteListener(writeListener);
            }
            else
            {
                writeListener.data(buffer, offset, length, callback);
                writeListener.onWritePossible();
            }
        }
        catch (Throwable x)
        {
            // TODO: who calls asyncContext.complete() in this case ?
            callback.failed(x);
        }
    }

    private class StreamReader implements ReadListener, Callback
    {
        private final byte[] buffer = new byte[512];
//        private final byte[] buffer = new byte[getHttpClient().getRequestBufferSize()];
        private final ServletInputStream input;
        private final int requestId;
        private final DeferredContentProvider provider;

        public StreamReader(ServletInputStream input, int requestId, DeferredContentProvider provider)
        {
            this.input = input;
            this.requestId = requestId;
            this.provider = provider;
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            _log.debug("{} asynchronous read start on {}", requestId, input);

            // First check for isReady() because it has
            // side effects, and then for isFinished().
            while (input.isReady() && !input.isFinished())
            {
                int read = input.read(buffer);
                _log.debug("{} asynchronous read {} bytes on {}", requestId, read, input);
                if (read > 0)
                {
                    _log.debug("{} proxying content to upstream: {} bytes", requestId, read);
                    provider.offer(ByteBuffer.wrap(buffer, 0, read), this);
                    // Do not call isReady() so that we can apply backpressure.
                    break;
                }
            }
            if (!input.isFinished())
                _log.debug("{} asynchronous read pending on {}", requestId, input);
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            _log.debug("{} proxying content to upstream completed", requestId);
            provider.close();
        }

        @Override
        public void onError(Throwable x)
        {
            failed(x);
        }

        @Override
        public void succeeded()
        {
            try
            {
                if (input.isReady())
                    onDataAvailable();
            }
            catch (Throwable x)
            {
                failed(x);
            }
        }

        @Override
        public void failed(Throwable x)
        {
            // TODO: send a response error ?
            // complete the async context since we cannot throw an exception from here.
        }
    }

    private class StreamWriter implements WriteListener
    {
        private final AsyncContext asyncContext;
        private final int requestId;
        private WriteState state;
        private byte[] buffer;
        private int offset;
        private int length;
        private Callback callback;

        private StreamWriter(AsyncContext asyncContext, int requestId)
        {
            this.asyncContext = asyncContext;
            this.requestId = requestId;
            this.state = WriteState.IDLE;
        }

        private void data(byte[] bytes, int offset, int length, Callback callback)
        {
            if (state != WriteState.IDLE)
                throw new WritePendingException();
            this.state = WriteState.READY;
            this.buffer = bytes;
            this.offset = offset;
            this.length = length;
            this.callback = callback;
        }

        @Override
        public void onWritePossible() throws IOException
        {
            ServletOutputStream output = asyncContext.getResponse().getOutputStream();
            if (state == WriteState.READY)
            {
                // There is data to write.
                _log.debug("{} asynchronous write start of {} bytes on {}", requestId, length, output);
                output.write(buffer, offset, length);
                state = WriteState.PENDING;
                if (output.isReady())
                {
                    _log.debug("{} asynchronous write of {} bytes completed on {}", requestId, length, output);
                    complete();
                }
                else
                {
                    _log.debug("{} asynchronous write of {} bytes pending on {}", requestId, length, output);
                }
            }
            else if (state == WriteState.PENDING)
            {
                // The write blocked but is now complete.
                _log.debug("{} asynchronous write of {} bytes completing on {}", requestId, length, output);
                complete();
            }
            else
            {
                throw new IllegalStateException();
            }
        }

        private void complete()
        {
            buffer = null;
            offset = 0;
            length = 0;
            Callback c = callback;
            callback = null;
            state = WriteState.IDLE;
            // Call the callback only after the whole state has been reset,
            // because the callback may trigger a reentrant call and the
            // state would be the old one
            c.succeeded();
        }

        @Override
        public void onError(Throwable failure)
        {
            // TODO:
        }
    }

    private enum WriteState
    {
        READY, PENDING, IDLE
    }
}
