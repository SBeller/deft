package org.deftserver.io;

import static com.google.common.collect.Collections2.transform;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.deftserver.io.callback.CallbackManager;
import org.deftserver.io.callback.JMXDebuggableCallbackManager;
import org.deftserver.io.timeout.JMXDebuggableTimeoutManager;
import org.deftserver.io.timeout.Timeout;
import org.deftserver.io.timeout.TimeoutManager;
import org.deftserver.util.MXBeanUtil;
import org.deftserver.web.AsyncCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public enum IOLoop implements IOLoopMXBean {
	
	INSTANCE;
	
	private boolean running = false;
	
	private final Logger logger = LoggerFactory.getLogger(IOLoop.class);

	private Selector selector;
	
	private final Map<SelectableChannel, IOHandler> handlers = Maps.newHashMap();
	
	private final TimeoutManager tm = new JMXDebuggableTimeoutManager();
	private final CallbackManager cm = new JMXDebuggableCallbackManager();
	
	private IOLoop() {
		try {
			selector = Selector.open();
		} catch (IOException e) {
			logger.error("Could not open selector: {}", e.getMessage());
		}
		MXBeanUtil.registerMXBean(this, "org.deftserver.web:type=IOLoop");
	}
	
	public void start() {
		Thread.currentThread().setName("I/O-LOOP");
		running = true;
		
		long selectorTimeout = 250; // 250 ms
		while (running) {
			try {
				if (selector.select(selectorTimeout) == 0) {
					long ms = tm.execute();
					selectorTimeout = Math.min(ms, /*selectorTimeout*/ 250);
					if (cm.execute()) {
						selectorTimeout = 1;
					}
					continue;
				}

				Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
				while (keys.hasNext()) {
					SelectionKey key = keys.next();
					IOHandler handler = handlers.get(key.channel());
					if (key.isAcceptable()) {
						handler.handleAccept(key);
					}
					if (key.isConnectable()) {
						handler.handleConnect(key);
					}
					if (key.isReadable()) {
						handler.handleRead(key);
					}
					if (key.isValid() && key.isWritable()) {
						handler.handleWrite(key);
					}
					keys.remove();
				}
				long ms = tm.execute();
				selectorTimeout = Math.min(ms, /*selectorTimeout*/ 250);
				if (cm.execute()) { 
					selectorTimeout = 1; 
				}

			} catch (IOException e) {
				logger.error("Exception received in IOLoop: {}", e);			
			}
		}
	}
	
	public void stop() {
		running = false;
		logger.debug("Stopping IOLoop...");
	}

	public SelectionKey addHandler(SelectableChannel channel, IOHandler handler, int interestOps, Object attachment) {
		handlers.put(channel, handler);
		return registerChannel(channel, interestOps, attachment);		
	}
	
	public void removeHandler(SelectableChannel channel) {
		handlers.remove(channel);
	}
	
	/**
	 * 
	 * @param newInterestOps The complete new set of interest operations.
	 */
	public void updateHandler(SelectableChannel channel, int newInterestOps) {
		if (handlers.containsKey(channel)) {
			channel.keyFor(selector).interestOps(newInterestOps);
		} else {
			logger.warn("Tried to update interestOps for an unknown SelectableChannel.");
		}
	}
	
	private SelectionKey registerChannel(SelectableChannel channel, int interestOps, Object attachment) {
		try {
			return channel.register(selector, interestOps, attachment);
		} catch (ClosedChannelException e) {
			removeHandler(channel);
			logger.error("Could not register channel: {}", e.getMessage());		
		}		
		return null;
	}
	
	public void addKeepAliveTimeout(SocketChannel channel, Timeout keepAliveTimeout) {
		tm.addKeepAliveTimeout(channel, keepAliveTimeout);
	}
	
	public boolean hasKeepAliveTimeout(SelectableChannel channel) {
		return tm.hasKeepAliveTimeout(channel);
	}
	
	public void addTimeout(Timeout timeout) {
		tm.addTimeout(timeout);
	}
	
	public void addCallback(AsyncCallback callback) {
		cm.addCallback(callback);
	}
	
// implements IOLoopMXBean
	@Override
	public int getNumberOfRegisteredIOHandlers() {
		return handlers.size();
	}

	@Override
	public List<String> getRegisteredIOHandlers() {
		Map<SelectableChannel, IOHandler> defensive = new HashMap<SelectableChannel, IOHandler>(handlers);
		Collection<String> readables = transform(defensive.values(), new Function<IOHandler, String>() {
			@Override public String apply(IOHandler handler) { return handler.toString(); }
		});
		return Lists.newLinkedList(readables);
	}

}
