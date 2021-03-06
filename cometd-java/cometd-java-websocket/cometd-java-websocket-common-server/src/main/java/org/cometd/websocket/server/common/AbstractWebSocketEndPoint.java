/*
 * Copyright (c) 2008-2018 the original author or authors.
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
package org.cometd.websocket.server.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.text.ParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeoutException;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.Promise;
import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.common.AsyncFoldLeft;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.ServerMessageImpl;
import org.cometd.server.ServerSessionImpl;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractWebSocketEndPoint {
    private final Logger _logger = LoggerFactory.getLogger(getClass());
    private final Flusher flusher = new Flusher();
    private final AbstractWebSocketTransport _transport;
    private final BayeuxContext _bayeuxContext;
    private ServerSessionImpl _session;

    protected AbstractWebSocketEndPoint(AbstractWebSocketTransport transport, BayeuxContext context) {
        this._transport = transport;
        this._bayeuxContext = context;
    }

    protected abstract void send(ServerSession session, String data, Callback callback);

    public abstract void close(int code, String reason);

    public void onMessage(String data, Promise<Void> p) {
        Promise<Void> promise = Promise.from(p::succeed, failure -> {
            if (_logger.isDebugEnabled()) {
                _logger.debug("", failure);
            }
            close(1011, failure.toString());
            p.fail(failure);
        });

        try {
            ServerMessage.Mutable[] messages = _transport.parseMessages(data);
            if (_logger.isDebugEnabled()) {
                _logger.debug("Parsed {} messages", messages == null ? -1 : messages.length);
            }
            if (messages != null) {
                processMessages(messages, promise);
            } else {
                promise.succeed(null);
            }
        } catch (ParseException x) {
            close(1011, x.toString());
            _logger.warn("Error parsing JSON: " + data, x);
            promise.succeed(null);
        } catch (Throwable x) {
            promise.fail(x);
        }
    }

    public void onClose(int code, String reason) {
        final ServerSessionImpl session = _session;
        if (_logger.isDebugEnabled()) {
            _logger.debug("Closing {}/{} - {}", code, reason, session);
        }
        if (session != null) {
            // There is no need to call BayeuxServerImpl.removeServerSession(),
            // because the connection may have been closed for a reload, so
            // just null out the current session to have it retrieved again.
            _session = null;
            session.setScheduler(null);
            session.scheduleExpiration(_transport.getInterval());
        }
        _transport.onClose(code, reason);
    }

    public void onError(Throwable failure) {
        if (failure instanceof SocketTimeoutException || failure instanceof TimeoutException) {
            if (_logger.isDebugEnabled()) {
                _logger.debug("WebSocket Timeout", failure);
            }
        } else {
            InetSocketAddress address = _bayeuxContext == null ? null : _bayeuxContext.getRemoteAddress();
            _logger.info("WebSocket Error, Address: " + address, failure);
        }
    }

    private void processMessages(ServerMessage.Mutable[] messages, Promise<Void> promise) {
        if (messages.length == 0) {
            promise.fail(new IOException("bayeux protocol violation"));
        } else {
            ServerSessionImpl session;
            ServerMessage.Mutable m = messages[0];
            if (Channel.META_HANDSHAKE.equals(m.getChannel())) {
                _session = null;
                session = _transport.getBayeux().newServerSession();
                session.setAllowMessageDeliveryDuringHandshake(_transport.isAllowMessageDeliveryDuringHandshake());
            } else {
                session = _session;
                if (session == null) {
                    if (!_transport.isRequireHandshakePerConnection()) {
                        session = _session = (ServerSessionImpl)_transport.getBayeux().getSession(m.getClientId());
                    }
                } else if (_transport.getBayeux().getSession(session.getId()) == null) {
                    session = _session = null;
                }
            }

            Context context = new Context(session);
            AsyncFoldLeft.run(messages, true, (result, message, loop) -> {
                        processMessage(messages, context, (ServerMessageImpl)message, Promise.from(b -> loop.proceed(result && b), loop::fail));
                    },
                    Promise.from(flush -> {
                        if (flush) {
                            flush(context, promise);
                        } else {
                            promise.succeed(null);
                        }
                    }, promise::fail));
        }
    }

    private void processMessage(ServerMessage.Mutable[] messages, Context context, ServerMessageImpl message, Promise<Boolean> promise) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Processing {}", message);
        }

        message.setServerTransport(_transport);
        message.setBayeuxContext(_bayeuxContext);
        ServerSessionImpl session = context.session;
        if (session != null) {
            session.setServerTransport(_transport);
        }

        switch (message.getChannel()) {
            case Channel.META_HANDSHAKE: {
                if (messages.length > 1) {
                    promise.fail(new IOException("protocol violation"));
                } else {
                    processMetaHandshake(context, message, promise);
                }
                break;
            }
            case Channel.META_CONNECT: {
                processMetaConnect(context, message, Promise.from(proceed -> {
                    if (proceed) {
                        resume(context, message, Promise.from(y -> promise.succeed(true), promise::fail));
                    } else {
                        promise.succeed(false);
                    }
                }, promise::fail));
                break;
            }
            default: {
                processMessage(context, message, promise);
                break;
            }
        }
    }

    private void processMetaHandshake(Context context, ServerMessage.Mutable message, Promise<Boolean> promise) {
        ServerSessionImpl session = context.session;
        _transport.getBayeux().handle(session, message, Promise.from(reply -> {
            _transport.processReply(session, reply, Promise.from(r -> {
                if (r != null) {
                    context.replies.add(r);
                    if (r.isSuccessful()) {
                        _session = session;
                    }
                }
                context.sendQueue = _transport.allowMessageDeliveryDuringHandshake(session) && r != null && r.isSuccessful();
                context.scheduleExpiration = true;
                promise.succeed(true);
            }, promise::fail));
        }, promise::fail));
    }

    private void processMetaConnect(Context context, ServerMessage.Mutable message, Promise<Boolean> promise) {
        // Remember the connected status before handling the message.
        ServerSessionImpl session = context.session;
        boolean wasConnected = session != null && session.isConnected();
        _transport.getBayeux().handle(session, message, Promise.from(reply -> {
            boolean proceed = true;
            if (session != null) {
                boolean maySuspend = !session.shouldSchedule();
                boolean metaConnectDelivery = _transport.isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
                if ((maySuspend || !metaConnectDelivery) && reply.isSuccessful()) {
                    long timeout = session.calculateTimeout(_transport.getTimeout());
                    if (timeout > 0 && wasConnected && session.isConnected()) {
                        AbstractServerTransport.Scheduler scheduler = suspend(context, message, timeout);
                        session.setScheduler(scheduler);
                        proceed = false;
                    }
                }
                if (proceed && session.isDisconnected()) {
                    reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
                }
            }
            promise.succeed(proceed);
        }, promise::fail));
    }

    private void processMessage(Context context, ServerMessageImpl message, Promise<Boolean> promise) {
        ServerSessionImpl session = context.session;
        _transport.getBayeux().handle(session, message, Promise.from(y ->
                _transport.processReply(session, message.getAssociated(), Promise.from(reply -> {
                    if (reply != null) {
                        context.replies.add(reply);
                    }
                    // Leave sendQueue unchanged.
                    // Leave scheduleExpiration unchanged.
                    promise.succeed(true);
                }, promise::fail)), promise::fail));
    }

    private AbstractServerTransport.Scheduler suspend(Context context, ServerMessage.Mutable message, long timeout) {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Suspended {}", message);
        }
        context.session.notifySuspended(message, timeout);
        return new WebSocketScheduler(context, message, timeout);
    }

    private void resume(Context context, ServerMessage.Mutable message, Promise<Void> promise) {
        ServerMessage.Mutable reply = message.getAssociated();
        ServerSessionImpl session = context.session;
        if (session != null && session.isTerminated()) {
            reply.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_NONE_VALUE);
        }
        _transport.processReply(session, reply, Promise.from(r -> {
            if (r != null) {
                context.replies.add(r);
            }
            context.sendQueue = true;
            context.scheduleExpiration = true;
            promise.succeed(null);
        }, promise::fail));
    }

    protected void send(List<? extends ServerMessage> messages, int batchSize, Callback callback) {
        if (messages.isEmpty()) {
            callback.succeeded();
            return;
        }

        int size = messages.size();
        int batch = Math.min(batchSize, size);
        // Assume 4 fields of 48 chars per message
        int capacity = batch * 4 * 48;
        StringBuilder builder = new StringBuilder(capacity);
        builder.append("[");
        if (batch == 1) {
            // Common path.
            ServerMessage serverMessage = messages.remove(0);
            builder.append(toJSON(serverMessage));
        } else {
            boolean comma = false;
            for (int b = 0; b < batch; ++b) {
                ServerMessage serverMessage = messages.get(b);
                if (comma) {
                    builder.append(",");
                }
                comma = true;
                builder.append(toJSON(serverMessage));
            }
            if (batch == size) {
                messages.clear();
            } else {
                messages.subList(0, batch).clear();
            }
        }
        builder.append("]");
        send(_session, builder.toString(), callback);
    }

    protected void flush(Context context, Promise<Void> promise) {
        List<ServerMessage> queue = Collections.emptyList();
        ServerSessionImpl session = context.session;
        if (context.sendQueue && session != null) {
            queue = session.takeQueue();
        }
        if (_logger.isDebugEnabled()) {
            _logger.debug("Flushing {}, replies={}, messages={}", session, context.replies, queue);
        }
        boolean queued = flusher.queue(new Entry(context, queue, promise));
        if (queued) {
            flusher.iterate();
        }
    }

    private String toJSON(ServerMessage message) {
        return _transport.toJSON(message);
    }

    private class WebSocketScheduler implements AbstractServerTransport.Scheduler, Runnable, Promise<Void> {
        private final Context context;
        private final ServerMessage.Mutable message;
        private Scheduler.Task task;

        public WebSocketScheduler(Context context, ServerMessage.Mutable message, long timeout) {
            this.context = context;
            this.message = message;
            this.task = _transport.getBayeux().schedule(this, timeout);
        }

        @Override
        public void schedule() {
            ServerSessionImpl session = context.session;
            boolean metaConnectDelivery = _transport.isMetaConnectDeliveryOnly() || session.isMetaConnectDeliveryOnly();
            if (metaConnectDelivery || session.isTerminated()) {
                if (cancelTimeout()) {
                    session.notifyResumed(message, false);
                    resume(context, message, this);
                }
            } else {
                Context context = new Context(session);
                context.sendQueue = true;
                flush(context, Promise.from(y -> {}, this::fail));
            }
        }

        @Override
        public void cancel() {
            if (cancelTimeout()) {
                close(1000, "Cancel");
            }
        }

        @Override
        public void run() {
            // Executed when the /meta/connect timeout expires.
            if (cancelTimeout()) {
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Resumed {}", message);
                }
                context.session.notifyResumed(message, true);
                resume(context, message, this);
            }
        }

        private boolean cancelTimeout() {
            Scheduler.Task task;
            synchronized (this) {
                task = this.task;
                if (task != null) {
                    this.task = null;
                }
            }
            if (task != null) {
                task.cancel();
                return true;
            }
            return false;
        }

        @Override
        public void succeed(Void result) {
            flush(context, Promise.from(y -> {}, this::fail));
        }

        @Override
        public void fail(Throwable failure) {
            close(1011, failure.toString());
        }
    }

    private class Flusher extends IteratingCallback {
        private final Queue<Entry> _entries = new ArrayDeque<>();
        private Entry _entry;
        private Throwable _failure;

        private boolean queue(Entry entry) {
            Throwable failure;
            synchronized (this) {
                failure = _failure;
                if (failure == null) {
                    return _entries.offer(entry);
                }
            }
            // If we are terminated, we still need to schedule
            // the expiration so that the session can be swept.
            entry.scheduleExpiration();
            entry._promise.fail(failure);
            return false;
        }

        @Override
        protected Action process() {
            Entry entry;
            synchronized (this) {
                entry = this._entry = _entries.peek();
            }
            if (_logger.isDebugEnabled()) {
                _logger.debug("Processing {}", entry);
            }

            if (entry == null) {
                return Action.IDLE;
            }

            List<ServerMessage.Mutable> replies = entry._context.replies;
            List<ServerMessage> queue = entry._queue;
            if (replies.size() > 0) {
                ServerMessage.Mutable reply = replies.get(0);
                if (Channel.META_HANDSHAKE.equals(reply.getChannel())) {
                    if (_transport.allowMessageDeliveryDuringHandshake(_session) && !queue.isEmpty()) {
                        reply.put("x-messages", queue.size());
                    }
                    _transport.getBayeux().freeze(reply);
                    send(replies, 1, this);
                    return Action.SCHEDULED;
                }
            }

            if (!queue.isEmpty()) {
                // Under load, it is possible that we have many bayeux messages and
                // that these would generate a large websocket message that the client
                // could not handle, so we need to split the messages into batches.
                int size = queue.size();
                int messagesPerFrame = _transport.getMessagesPerFrame();
                int batchSize = messagesPerFrame > 0 ? Math.min(messagesPerFrame, size) : size;
                if (_logger.isDebugEnabled()) {
                    _logger.debug("Processing queue, batch size {}: {}", batchSize, queue);
                }
                send(queue, batchSize, this);
                return Action.SCHEDULED;
            }

            synchronized (this) {
                _entries.poll();
            }

            // Start the interval timeout after writing the
            // messages since they may take time to be written.
            entry.scheduleExpiration();

            if (_logger.isDebugEnabled()) {
                _logger.debug("Processing replies {}", replies);
            }
            for (ServerMessage.Mutable reply : replies) {
                _transport.getBayeux().freeze(reply);
            }
            send(replies, replies.size(), this);
            return Action.SCHEDULED;
        }

        @Override
        public void succeeded() {
            _entry._promise.succeed(null);
            super.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable x) {
            Entry entry;
            synchronized (this) {
                _failure = x;
                entry = this._entry;
            }
            if (entry != null) {
                entry.scheduleExpiration();
                entry._promise.fail(x);
            }
        }
    }

    private class Entry {
        private final Context _context;
        private final List<ServerMessage> _queue;
        private final Promise<Void> _promise;

        private Entry(Context context, List<ServerMessage> queue, Promise<Void> promise) {
            this._context = context;
            this._queue = queue;
            this._promise = promise;
        }

        private void scheduleExpiration() {
            if (_context.scheduleExpiration) {
                ServerSessionImpl session = _context.session;
                if (session != null) {
                    session.scheduleExpiration(_transport.getInterval());
                }
            }
        }

        @Override
        public String toString() {
            return String.format("%s@%x[messages=%d,replies=%d]",
                    getClass().getSimpleName(),
                    hashCode(),
                    _queue.size(),
                    _context.replies.size());
        }
    }

    protected static class Context {
        private final List<ServerMessage.Mutable> replies = new ArrayList<>();
        private final ServerSessionImpl session;
        private boolean sendQueue;
        private boolean scheduleExpiration;

        private Context(ServerSessionImpl session) {
            this.session = session;
        }
    }
}
