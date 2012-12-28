/**
 * Copyright (C) 2009-2012 Barchart, Inc. <http://www.barchart.com/>
 *
 * All rights reserved. Licensed under the OSI BSD License.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package com.barchart.udt.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.channels.SocketChannel;
import java.nio.channels.UnresolvedAddressException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.barchart.udt.ExceptionUDT;
import com.barchart.udt.SocketUDT;
import com.barchart.udt.TypeUDT;
import com.barchart.udt.anno.ThreadSafe;

/**
 * {@link SocketChannel}-like wrapper for {@link SocketUDT}, can be either
 * stream or message oriented, depending on {@link TypeUDT}
 * <p>
 * you must use {@link SelectorProviderUDT#openSocketChannel()} to obtain
 * instance of this class; do not use JDK
 * {@link java.nio.channels.SocketChannel#open()};
 * <p>
 * example:
 * 
 * <pre>
 * SelectorProvider provider = SelectorProviderUDT.DATAGRAM;
 * SocketChannel clientChannel = provider.openSocketChannel();
 * clientChannel.configureBlocking(true);
 * Socket clientSocket = clientChannel.socket();
 * InetSocketAddress clientAddress = new InetSocketAddress(&quot;localhost&quot;, 10000);
 * clientSocket.bind(clientAddress);
 * assert clientSocket.isBound();
 * InetSocketAddress serverAddress = new InetSocketAddress(&quot;localhost&quot;, 12345);
 * clientChannel.connect(serverAddress);
 * assert clientSocket.isConnected();
 * </pre>
 */
public class SocketChannelUDT extends SocketChannel implements ChannelUDT {

	protected static final Logger log = LoggerFactory
			.getLogger(SocketChannelUDT.class);

	protected final Object connectLock = new Object();

	/**
	 * local volatile variable, which mirrors super.blocking, to avoid the cost
	 * of synchronized call inside isBlocking()
	 */
	protected volatile boolean isBlockingMode = isBlocking();

	protected volatile boolean isConnectFinished;

	protected volatile boolean isConnectionPending;

	@ThreadSafe("this")
	protected Socket socketAdapter;

	protected final SocketUDT socketUDT;

	protected SocketChannelUDT( //
			final SelectorProviderUDT provider, //
			final SocketUDT socketUDT //
	) {

		super(provider);
		this.socketUDT = socketUDT;
	}

	protected SocketChannelUDT( //
			final SelectorProviderUDT provider, //
			final SocketUDT socketUDT, //
			final boolean isConnected //
	) {

		super(provider);
		this.socketUDT = socketUDT;

		if (isConnected) {
			isConnectFinished = true;
			isConnectionPending = false;
		} else {
			isConnectFinished = false;
			isConnectionPending = true;
		}

	}

	@Override
	public boolean connect(final SocketAddress remote) throws IOException {

		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (isConnected()) {
			log.warn("already connected; ignoring remote={}", remote);
			return true;
		}

		if (remote == null) {
			close();
			log.error("remote == null");
			throw new NullPointerException();
		}

		final InetSocketAddress remoteSocket = (InetSocketAddress) remote;

		if (remoteSocket.isUnresolved()) {
			log.error("can not use unresolved address: remote={}", remote);
			close();
			throw new UnresolvedAddressException();
		}

		if (isBlocking()) {
			synchronized (connectLock) {
				try {

					if (isConnectionPending) {
						close();
						throw new ConnectionPendingException();
					}

					isConnectionPending = true;

					begin();

					socketUDT.connect(remoteSocket);

				} finally {

					end(true);

					isConnectionPending = false;

					connectLock.notifyAll();

				}
			}

			return socketUDT.isConnected();

		} else {

			/** non Blocking */

			if (!isRegistered()) {

				/** this channel is independent of any selector */

				log.error("UDT channel is in NON blocking mode; "
						+ "must register with a selector " //
						+ "before trying to connect(); " //
						+ "socketId=" + socketUDT.id());

				throw new IllegalBlockingModeException();

			}

			/** this channel is registered with a selector */

			synchronized (connectLock) {

				if (isConnectionPending) {
					close();
					log.error("connection already in progress");
					throw new ConnectionPendingException();
				}

				isConnectFinished = false;
				isConnectionPending = true;

				socketUDT.connect(remoteSocket);

			}

			/**
			 * connection operation must later be completed by invoking the
			 * #finishConnect() method.
			 */

			return false;

		}

	}

	@Override
	public boolean finishConnect() throws IOException {

		if (!isOpen()) {
			throw new ClosedChannelException();
		}

		if (isBlocking()) {

			synchronized (connectLock) {
				while (isConnectionPending) {
					try {
						connectLock.wait();
					} catch (final InterruptedException e) {
						throw new IOException(e);
					}
				}
			}

		}

		if (isConnected()) {

			isConnectFinished = true;
			isConnectionPending = false;

			return true;

		} else {

			log.error("connect failure : {}", socketUDT);
			throw new IOException();

		}

	}

	@Override
	protected void implCloseSelectableChannel() throws IOException {
		socketUDT.close();
	}

	@Override
	protected void implConfigureBlocking(final boolean block)
			throws IOException {
		socketUDT.configureBlocking(block);
		isBlockingMode = block;
	}

	@Override
	public boolean isConnected() {
		return socketUDT.isConnected();
	}

	@Override
	public boolean isConnectFinished() {
		return isConnectFinished;
	}

	@Override
	public boolean isConnectionPending() {
		return isConnectionPending;
	}

	@Override
	public KindUDT kindUDT() {
		return KindUDT.CONNECTOR;
	}

	@Override
	public SelectorProviderUDT providerUDT() {
		return (SelectorProviderUDT) super.provider();
	}

	//

	/**
	 * See {@link java.nio.channels.SocketChannel#read(ByteBuffer)} contract;
	 * note: this method does not return (-1) as EOS (end of stream flag)
	 * 
	 * @return <code><0</code> should not happen<br>
	 *         <code>=0</code> blocking mode: timeout occurred on receive<br>
	 *         <code>=0</code> non-blocking mode: nothing is received by the
	 *         underlying UDT socket<br>
	 *         <code>>0</code> actual bytes received count<br>
	 * @see com.barchart.udt.SocketUDT#receive(ByteBuffer)
	 * @see com.barchart.udt.SocketUDT#receive(byte[], int, int)
	 */
	@Override
	public int read(final ByteBuffer buffer) throws IOException {

		final int remaining = buffer.remaining();

		if (remaining <= 0) {
			return 0;
		}

		final SocketUDT socket = socketUDT;
		final boolean isBlocking = isBlockingMode;

		final int sizeReceived;

		try {

			if (isBlocking) {
				begin(); // JDK contract for NIO blocking calls
			}

			if (buffer.isDirect()) {

				sizeReceived = socket.receive(buffer);

			} else {

				final byte[] array = buffer.array();
				final int position = buffer.position();
				final int limit = buffer.limit();

				sizeReceived = socket.receive(array, position, limit);

				if (0 < sizeReceived && sizeReceived <= remaining) {
					buffer.position(position + sizeReceived);
				}

			}

		} finally {
			if (isBlocking) {
				end(true); // JDK contract for NIO blocking calls
			}
		}

		// see contract for receive()

		if (sizeReceived < 0) {
			log.trace("nothing was received; socket={}", socket);
			return 0;
		}

		if (sizeReceived == 0) {
			log.trace("receive timeout; socket={}", socket);
			return 0;
		}

		if (sizeReceived <= remaining) {
			return sizeReceived;
		} else {
			log.error("should not happen: socket={}", socket);
			return 0;
		}

	}

	@Override
	public long read(final ByteBuffer[] dsts, final int offset, final int length)
			throws IOException {
		throw new RuntimeException("feature not available");
	}

	@Override
	public Socket socket() {
		synchronized (this) {
			if (socketAdapter == null) {
				try {
					socketAdapter = new NioSocketUDT(this);
				} catch (final ExceptionUDT e) {
					log.error("failed to make socket", e);
				}
			}
			return socketAdapter;
		}
	}

	@Override
	public SocketUDT socketUDT() {
		return socketUDT;
	}

	@Override
	public String toString() {
		return socketUDT.toString();
	}

	/**
	 * See {@link java.nio.channels.SocketChannel#write(ByteBuffer)} contract;
	 * 
	 * @return <code><0</code> should not happen<br>
	 *         <code>=0</code> blocking mode: timeout occurred on send<br>
	 *         <code>=0</code> non-blocking mode: buffer is full in the
	 *         underlying UDT socket; nothing is sent<br>
	 *         <code>>0</code> actual bytes sent count<br>
	 * @see com.barchart.udt.SocketUDT#send(ByteBuffer)
	 * @see com.barchart.udt.SocketUDT#send(byte[], int, int)
	 */
	@Override
	public int write(final ByteBuffer buffer) throws IOException {

		// writeCount.incrementAndGet();

		if (buffer == null) {
			throw new NullPointerException("buffer == null");
		}

		final int remaining = buffer.remaining();

		if (remaining <= 0) {
			return 0;
		}

		final SocketUDT socket = socketUDT;
		final boolean isBlocking = isBlockingMode;

		final int sizeSent;

		try {

			if (isBlocking) {
				begin(); // JDK contract for NIO blocking calls
			}

			if (buffer.isDirect()) {

				sizeSent = socket.send(buffer);

			} else {

				final byte[] array = buffer.array();
				final int position = buffer.position();
				final int limit = buffer.limit();

				sizeSent = socket.send(array, position, limit);

				if (0 < sizeSent && sizeSent <= remaining) {
					buffer.position(position + sizeSent);
				}
			}
		} finally {
			if (isBlocking) {
				end(true); // JDK contract for NIO blocking calls
			}
		}

		// see contract for send()

		if (sizeSent < 0) {
			log.trace("no buffer space; socket={}", socket);
			return 0;
		}

		if (sizeSent == 0) {
			log.trace("send timeout; socket={}", socket);
			return 0;
		}

		if (sizeSent <= remaining) {
			return sizeSent;
		} else {
			log.error("should not happen; socket={}", socket);
			return 0;
		}

	}

	@Override
	public long write(final ByteBuffer[] srcs, final int offset,
			final int length) throws IOException {
		throw new RuntimeException("feature not available");
	}

	@Override
	public TypeUDT typeUDT() {
		return providerUDT().type;
	}

	/** java 7 */
	public SocketChannelUDT bind(final SocketAddress localAddress)
			throws IOException {

		socketUDT.bind((InetSocketAddress) localAddress);

		return this;

	}

}
