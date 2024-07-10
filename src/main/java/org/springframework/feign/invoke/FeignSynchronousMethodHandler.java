package org.springframework.feign.invoke;

import feign.*;
import org.springframework.feign.FeignExceptionHandler;
import org.springframework.feign.codec.FeignDecoder;
import org.springframework.feign.codec.HttpResponse;
import org.springframework.feign.codec.RemoteChain;
import org.springframework.feign.invoke.template.FeignTemplateFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static feign.Util.checkNotNull;
import static feign.Util.ensureClosed;
import static java.lang.String.format;

/**
 * @author shanhuiming
 */
public class FeignSynchronousMethodHandler implements InvocationHandlerFactory.MethodHandler {
    private static final long MAX_RESPONSE_BUFFER_SIZE = 8192L;
    private final org.slf4j.Logger logger;
    private final MethodMetadata metadata;
    private final Target<?> target;
    private final Client client;
    private final org.springframework.feign.retryer.Retryer retryer;
    private final List<RequestInterceptor> requestInterceptors;
    private final FeignTemplateFactory buildTemplateFromArgs;
    private final Request.Options options;
    private final FeignDecoder decoder;

    private final FeignExceptionHandler exceptionHandler;

    public FeignSynchronousMethodHandler(Target<?> target, Client client,
                                         org.springframework.feign.retryer.Retryer retryer,
                                         List<RequestInterceptor> requestInterceptors,
                                         MethodMetadata metadata,
                                         FeignTemplateFactory buildTemplateFromArgs,
                                         Request.Options options,
                                         FeignDecoder decoder,
                                         org.slf4j.Logger logger,
                                         FeignExceptionHandler exceptionHandler) {
        this.target = checkNotNull(target, "target");
        this.client = checkNotNull(client, "client for %s", target);
        this.retryer = checkNotNull(retryer, "retryer for %s", target);
        this.requestInterceptors = checkNotNull(requestInterceptors, "requestInterceptors for %s", target);
        this.metadata = checkNotNull(metadata, "metadata for %s", target);
        this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
        this.options = checkNotNull(options, "options for %s", target);
        this.decoder = checkNotNull(decoder, "decoder for %s", target);
        this.logger = checkNotNull(logger, "decoder for %s", target);
        this.exceptionHandler = exceptionHandler;
    }

    @Override
    public Object invoke(Object[] argv) throws Throwable {
        RequestTemplate template = buildTemplateFromArgs.create(argv);
        org.springframework.feign.retryer.Retryer retryer = this.retryer.clone();
        while (true) {
            try {
                return executeAndDecode(template);
            } catch (RetryableException e) {
                retryer.continueOrPropagate(e);
            }
        }
    }

    Object executeAndDecode(RequestTemplate template) throws Throwable {
        Request request = targetRequest(template);
        String url = request.url();

        FeignTarget<?> feignTarget = (FeignTarget<?>)target;
        String name = feignTarget.name();

        Response response;
        long start = System.nanoTime();
        try {
            // Deprecated
            RequestAttributes attributes =  RequestContextHolder.getRequestAttributes();
            String threadName = Thread.currentThread().getName();
            if(attributes == null && threadName != null && threadName.endsWith("-async")){
                threadName = threadName.substring(0, threadName.length() - 6);
                ConcurrentMap<String, RemoteChain> chainMap =
                        RemoteChain.ASYNC_CHAIN.computeIfAbsent(threadName, (key) -> new ConcurrentHashMap<>());

                String realUrl = url;
                int index = realUrl.indexOf("?");
                if(index != -1){
                    realUrl = realUrl.substring(0, index);
                }
                RemoteChain chain = RemoteChain.newChain(false, name, realUrl, 0, 0, "0", null);
                chain.setCount(new AtomicInteger(0)); // 先减1尝试放进去，然后再统一加1
                chain.setAsync(true);
                RemoteChain effectChain = chainMap.computeIfAbsent(realUrl, (key) -> chain);
                effectChain.increaseCount();
            }

            // http调用
            response = client.execute(request, options);
        } catch (IOException e) {
            long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            RemoteChain.appendChain(false, name, url, cost, -1, "-1", null);
            RemoteException ex = new RemoteException(url, -1, -1, format("remote[%s] failed %sms %s ", -1, cost, url), e);
            if(exceptionHandler != null){
                exceptionHandler.handle(ex);
            }
            throw ex;
        }

        long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        int status = response.status();
        boolean shouldClose = true;
        try {
            // Feign的Http响应
            if (Response.class == metadata.returnType()) {
                if (response.body() == null) {
                    return response;
                }

                if (response.body().length() == null || response.body().length() > MAX_RESPONSE_BUFFER_SIZE) {
                    shouldClose = false;
                    return response;
                }

                // Ensure the response body is disconnected
                byte[] bodyData = Util.toByteArray(response.body().asInputStream());
                return Response.create(status, response.reason(), response.headers(), bodyData);
            }

            // 自定义的Http响应
            if (HttpResponse.class == metadata.returnType()) {
                return new HttpResponse(response, logger, cost, url);
            }

            // 业务Response
            if (status >= 200 && status < 300) {
                return decoder.decode(response, metadata.returnType(), name, url, cost, status, logger);
            }

            RemoteChain.appendChain(false, name, url, cost, status, "-3", null);
            throw new RemoteException(url, status, -3, format("remote[%s] %sms %s", -3, cost, url));

        } catch(RemoteException e) {
            if(exceptionHandler != null){
                exceptionHandler.handle(e);
            }
            throw e;
        } catch (Exception e) {
            RemoteChain.appendChain(false, name, url, cost, status, "-2", null);
            RemoteException ex = new RemoteException(url, status, -2, format("remote[%s] failed %sms %s ", -2, cost, url), e);
            if(exceptionHandler != null){
                exceptionHandler.handle(ex);
            }
            throw ex;
        } finally {
            if (shouldClose) {
                ensureClosed(response.body());
            }
        }
    }

    Request targetRequest(RequestTemplate template) {
        for (RequestInterceptor interceptor : requestInterceptors) {
            interceptor.apply(template);
        }
        return target.apply(new RequestTemplate(template));
    }
}
