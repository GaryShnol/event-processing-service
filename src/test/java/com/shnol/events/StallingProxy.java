package com.shnol.events;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Forwards TCP to a dependency until stalled, then drops bytes both ways: the connection stays open but never
 * answers, as with a paused VM or a network blackhole, rather than being refused.
 */
public final class StallingProxy implements AutoCloseable {

	private final ServerSocket server = new ServerSocket(0);
	private final AtomicBoolean stalled = new AtomicBoolean();
	private final List<Socket> sockets = new CopyOnWriteArrayList<>();

	public StallingProxy(String host, int port) throws IOException {
		Thread.ofVirtual().start(() -> {
			while (!server.isClosed()) {
				try {
					Socket client = server.accept();
					Socket upstream = new Socket(host, port);
					sockets.addAll(List.of(client, upstream));
					pump(client, upstream);
					pump(upstream, client);
				}
				catch (IOException e) {
					return; // the proxy was closed
				}
			}
		});
	}

	public int port() {
		return server.getLocalPort();
	}

	public void stall() {
		stalled.set(true);
	}

	private void pump(Socket from, Socket to) {
		Thread.ofVirtual().start(() -> {
			byte[] buffer = new byte[8192];
			try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
				for (int read; (read = in.read(buffer)) != -1;) {
					if (!stalled.get()) {
						out.write(buffer, 0, read);
					}
				}
			}
			catch (IOException e) {
				// a socket was closed: this direction of the connection is over
			}
		});
	}

	@Override
	public void close() throws IOException {
		server.close();
		for (Socket socket : sockets) {
			socket.close();
		}
	}
}
