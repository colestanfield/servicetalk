/*
 * Copyright © 2018 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.router.jersey;

import io.servicetalk.buffer.Buffer;
import io.servicetalk.concurrent.api.Executor;
import io.servicetalk.concurrent.internal.DefaultThreadFactory;
import io.servicetalk.concurrent.internal.ServiceTalkTestTimeout;
import io.servicetalk.http.api.HttpConnection;
import io.servicetalk.http.api.HttpPayloadChunk;
import io.servicetalk.http.api.HttpProtocolVersion;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpRequestMethod;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpResponseStatus;
import io.servicetalk.http.api.HttpResponseStatuses;
import io.servicetalk.http.netty.DefaultHttpConnectionBuilder;
import io.servicetalk.http.netty.DefaultHttpServerStarter;
import io.servicetalk.transport.api.DefaultExecutionContext;
import io.servicetalk.transport.api.IoExecutor;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.IoThreadFactory;

import org.glassfish.jersey.server.ApplicationHandler;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.ws.rs.core.Application;

import static io.servicetalk.buffer.netty.BufferAllocators.DEFAULT_ALLOCATOR;
import static io.servicetalk.concurrent.api.Executors.newCachedThreadExecutor;
import static io.servicetalk.concurrent.internal.Await.awaitIndefinitelyNonNull;
import static io.servicetalk.concurrent.internal.Await.awaitNonNull;
import static io.servicetalk.concurrent.internal.ServiceTalkTestTimeout.DEFAULT_TIMEOUT_SECONDS;
import static io.servicetalk.http.api.CharSequences.newAsciiString;
import static io.servicetalk.http.api.HttpHeaderNames.CONTENT_LENGTH;
import static io.servicetalk.http.api.HttpHeaderNames.CONTENT_TYPE;
import static io.servicetalk.http.api.HttpHeaderNames.HOST;
import static io.servicetalk.http.api.HttpHeaderNames.TRANSFER_ENCODING;
import static io.servicetalk.http.api.HttpHeaderValues.CHUNKED;
import static io.servicetalk.http.api.HttpPayloadChunks.newPayloadChunk;
import static io.servicetalk.http.api.HttpProtocolVersions.HTTP_1_1;
import static io.servicetalk.http.api.HttpRequestMethods.GET;
import static io.servicetalk.http.api.HttpRequestMethods.HEAD;
import static io.servicetalk.http.api.HttpRequestMethods.OPTIONS;
import static io.servicetalk.http.api.HttpRequestMethods.POST;
import static io.servicetalk.http.api.HttpRequestMethods.PUT;
import static io.servicetalk.http.api.HttpRequests.newRequest;
import static io.servicetalk.http.router.jersey.TestUtil.getContentAsString;
import static io.servicetalk.transport.netty.NettyIoExecutors.createExecutor;
import static java.lang.Thread.NORM_PRIORITY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public abstract class AbstractJerseyHttpServiceTest {
    @Rule
    public final MockitoRule rule = MockitoJUnit.rule();
    @Rule
    public final ServiceTalkTestTimeout timeout = new ServiceTalkTestTimeout();
    @Rule
    public final ExpectedException expected = ExpectedException.none();

    private IoExecutor serverIoExecutor;
    private Executor serverExecutor;
    private ServerContext serverContext;
    private IoExecutor clientIoExecutor;
    private Executor clientExecutor;
    private HttpConnection<HttpPayloadChunk, HttpPayloadChunk> clientConnection;

    @Before
    public void initServer() throws Exception {
        serverExecutor = newCachedThreadExecutor(new DefaultThreadFactory("st-server-", true, NORM_PRIORITY));
        serverIoExecutor = createExecutor(new IoThreadFactory("st-server-io"));
        serverContext = awaitIndefinitelyNonNull(
                new DefaultHttpServerStarter(serverIoExecutor)
                        .start(new InetSocketAddress(0),
                                serverExecutor,
                                new DefaultJerseyHttpService(new ApplicationHandler(getApplication()))));
    }

    @Before
    public void initClient() throws Exception {
        clientExecutor = newCachedThreadExecutor(new DefaultThreadFactory("st-client-", true, NORM_PRIORITY));
        clientIoExecutor = createExecutor(new IoThreadFactory("st-client-io"));
        clientConnection = awaitIndefinitelyNonNull(
                new DefaultHttpConnectionBuilder<InetSocketAddress>()
                        .build(new DefaultExecutionContext(DEFAULT_ALLOCATOR, clientIoExecutor, clientExecutor),
                                (InetSocketAddress) serverContext.getListenAddress()));
    }

    @After
    public void closeClient() {
        clientConnection.closeAsync().merge(clientExecutor.closeAsync(), clientIoExecutor.closeAsync()).subscribe();
    }

    @After
    public void closeServer() {
        serverContext.closeAsync().merge(serverExecutor.closeAsync(), serverIoExecutor.closeAsync()).subscribe();
    }

    protected abstract Application getApplication();

    protected String host() {
        return "localhost:" + ((InetSocketAddress) serverContext.getListenAddress()).getPort();
    }

    protected String testUri(final String path) {
        return path;
    }

    protected HttpRequest<HttpPayloadChunk> options(final String path) {
        return noPayloadRequest(OPTIONS, path);
    }

    protected HttpRequest<HttpPayloadChunk> head(final String path) {
        return noPayloadRequest(HEAD, path);
    }

    protected HttpRequest<HttpPayloadChunk> get(final String path) {
        return noPayloadRequest(GET, path);
    }

    protected HttpRequest<HttpPayloadChunk> post(final String path, final String payload, final CharSequence contentType) {
        return payloadRequest(POST, path, payload, contentType);
    }

    protected HttpRequest<HttpPayloadChunk> put(final String path, final String payload, final CharSequence contentType) {
        return payloadRequest(PUT, path, payload, contentType);
    }

    protected HttpRequest<HttpPayloadChunk> noPayloadRequest(final HttpRequestMethod method, final String path) {
        final HttpRequest<HttpPayloadChunk> req = newRequest(method, testUri(path), clientExecutor);
        req.getHeaders().set(HOST, host());
        return req;
    }

    protected HttpRequest<HttpPayloadChunk> payloadRequest(final HttpRequestMethod method,
                                                           final String path,
                                                           final String payload,
                                                           final CharSequence contentType) {
        final Buffer content = DEFAULT_ALLOCATOR.fromUtf8(payload);
        final HttpRequest<HttpPayloadChunk> req = newRequest(method, testUri(path), newPayloadChunk(content), clientExecutor);
        req.getHeaders().set(HOST, host());
        req.getHeaders().set(CONTENT_TYPE, contentType);
        req.getHeaders().set(CONTENT_LENGTH, Integer.toString(content.getReadableBytes()));
        return req;
    }

    protected HttpRequest<HttpPayloadChunk> withHeader(final HttpRequest<HttpPayloadChunk> req, final String name, final String value) {
        req.getHeaders().set(name, value);
        return req;
    }

    protected HttpResponse<HttpPayloadChunk> sendAndAssertNoResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                     final HttpResponseStatus expectedStatus) {
        return sendAndAssertResponse(req, expectedStatus, null, "");
    }

    protected HttpResponse<HttpPayloadChunk> sendAndAssertResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                   final HttpResponseStatus expectedStatus,
                                                                   @Nullable final CharSequence expectedContentType,
                                                                   final String expectedContent) {
        return sendAndAssertResponse(req, expectedStatus, expectedContentType, is(expectedContent), $ -> expectedContent.length());
    }

    protected HttpResponse<HttpPayloadChunk> sendAndAssertResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                   final HttpResponseStatus expectedStatus,
                                                                   @Nullable final CharSequence expectedContentType,
                                                                   final Matcher<String> contentMatcher,
                                                                   final int expectedContentLength) {
        return sendAndAssertResponse(req, expectedStatus, expectedContentType, contentMatcher, $ -> expectedContentLength);
    }

    protected HttpResponse<HttpPayloadChunk> sendAndAssertResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                   final HttpResponseStatus expectedStatus,
                                                                   @Nullable final CharSequence expectedContentType,
                                                                   final Matcher<String> contentMatcher,
                                                                   final Function<String, Integer> expectedContentLengthExtractor) {
        return sendAndAssertResponse(req, HTTP_1_1, expectedStatus, expectedContentType, contentMatcher,
                expectedContentLengthExtractor);
    }

    protected HttpResponse<HttpPayloadChunk> sendAndAssertResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                   final HttpResponseStatuses expectedStatus,
                                                                   final CharSequence expectedContentType,
                                                                   final String expectedContent,
                                                                   final int timeout,
                                                                   final TimeUnit unit) {
        return sendAndAssertResponse(req, HTTP_1_1, expectedStatus, expectedContentType, is(expectedContent),
                $ -> expectedContent.length(), timeout, unit);
    }

    protected HttpResponse<HttpPayloadChunk> sendAndAssertResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                   final HttpProtocolVersion expectedHttpVersion,
                                                                   final HttpResponseStatus expectedStatus,
                                                                   @Nullable final CharSequence expectedContentType,
                                                                   final Matcher<String> contentMatcher,
                                                                   final Function<String, Integer> expectedContentLengthExtractor) {
        return sendAndAssertResponse(req, expectedHttpVersion, expectedStatus, expectedContentType, contentMatcher,
                expectedContentLengthExtractor, DEFAULT_TIMEOUT_SECONDS, SECONDS);
    }

    private HttpResponse<HttpPayloadChunk> sendAndAssertResponse(final HttpRequest<HttpPayloadChunk> req,
                                                                 final HttpProtocolVersion expectedHttpVersion,
                                                                 final HttpResponseStatus expectedStatus,
                                                                 @Nullable final CharSequence expectedContentType,
                                                                 final Matcher<String> contentMatcher,
                                                                 final Function<String, Integer> expectedContentLengthExtractor,
                                                                 final int timeout,
                                                                 final TimeUnit unit) {
        try {
            final HttpResponse<HttpPayloadChunk> res = awaitNonNull(clientConnection.request(req), timeout, unit);

            assertThat(res.getVersion(), is(expectedHttpVersion));
            final HttpResponseStatus status = res.getStatus();
            assertThat(status.getCode(), is(expectedStatus.getCode()));
            assertThat(status.getReasonPhrase(), is(expectedStatus.getReasonPhrase()));

            if (expectedContentType != null) {
                assertThat(res.getHeaders().get(CONTENT_TYPE), is(expectedContentType));
            } else {
                assertThat(res.getHeaders().contains(CONTENT_TYPE), is(false));
            }

            final String contentAsString = getContentAsString(res);

            @Nullable
            final Integer expectedContentLength = expectedContentLengthExtractor.apply(contentAsString);
            if (expectedContentLength != null) {
                assertThat(res.getHeaders().get(CONTENT_LENGTH), is(newAsciiString(Integer.toString(expectedContentLength))));
                res.getHeaders().getAll(TRANSFER_ENCODING)
                        .forEachRemaining(h -> assertThat(h.toString(), equalToIgnoringCase("chunked")));
            } else {
                assertThat(res.getHeaders().contains(CONTENT_LENGTH), is(false));
                if (status.getCode() >= 200 && status.getCode() != 204) {
                    assertThat(res.getHeaders().get(TRANSFER_ENCODING), is(CHUNKED));
                }
            }

            assertThat(contentAsString, contentMatcher);

            return res;
        } catch (final Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
