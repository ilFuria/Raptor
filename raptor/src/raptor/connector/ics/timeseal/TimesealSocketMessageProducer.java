package raptor.connector.ics.timeseal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang.StringUtils;

import raptor.connector.ics.IcsUtils;
import raptor.util.RaptorLogger;

public class TimesealSocketMessageProducer implements MessageProducer {
	private class CryptOutputStream extends OutputStream {
		private byte buffer[];
		private final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		private OutputStream outputStreamToDecorate;
		private final byte timesealKey[] = "Timestamp (FICS) v1.0 - programmed by Henrik Gram.".getBytes();

		public CryptOutputStream(OutputStream outputstream) {
			buffer = new byte[10000];
			outputStreamToDecorate = outputstream;
		}

		@Override
		public void write(int i) throws IOException {
			if (i == 10) {
				synchronized (socket) {
					if (initialTime == -1) {
						initialTime = System.currentTimeMillis();
					}
					int resultLength = crypt(byteArrayOutputStream.toByteArray(),
							System.currentTimeMillis() - initialTime);
					outputStreamToDecorate.write(buffer, 0, resultLength);
					outputStreamToDecorate.flush();
					byteArrayOutputStream.reset();
				}
			} else {
				byteArrayOutputStream.write(i);
			}
		}

		private int crypt(byte stringToWriteBytes[], long timestamp) {
			int bytesInLength = stringToWriteBytes.length;
			System.arraycopy(stringToWriteBytes, 0, buffer, 0, stringToWriteBytes.length);
			buffer[bytesInLength++] = 24;
			byte abyte1[] = Long.toString(timestamp).getBytes();
			System.arraycopy(abyte1, 0, buffer, bytesInLength, abyte1.length);
			bytesInLength += abyte1.length;
			buffer[bytesInLength++] = 25;
			int j = bytesInLength;
			for (bytesInLength += 12 - bytesInLength % 12; j < bytesInLength;) {
				buffer[j++] = 49;
			}

			for (int k = 0; k < bytesInLength; k++) {
				buffer[k] |= 0x80;
			}

			for (int i1 = 0; i1 < bytesInLength; i1 += 12) {
				byte byte0 = buffer[i1 + 11];
				buffer[i1 + 11] = buffer[i1];
				buffer[i1] = byte0;
				byte0 = buffer[i1 + 9];
				buffer[i1 + 9] = buffer[i1 + 2];
				buffer[i1 + 2] = byte0;
				byte0 = buffer[i1 + 7];
				buffer[i1 + 7] = buffer[i1 + 4];
				buffer[i1 + 4] = byte0;
			}

			int l1 = 0;
			for (int j1 = 0; j1 < bytesInLength; j1++) {
				buffer[j1] ^= timesealKey[l1];
				l1 = (l1 + 1) % timesealKey.length;
			}

			for (int k1 = 0; k1 < bytesInLength; k1++) {
				buffer[k1] -= 32;
			}

			buffer[bytesInLength++] = -128;
			buffer[bytesInLength++] = 10;
			return bytesInLength;
		}
	}

	private static final RaptorLogger LOG = RaptorLogger.getLog(TimesealSocketMessageProducer.class);

	private CryptOutputStream cryptedOutputStream;

	private long initialTime = -1;

	private String initialTimesealString = null;

	private Socket socket;

	private MessageListener listener;

	protected Thread daemonThread;

	protected StringBuilder inboundMessageBuffer = new StringBuilder(25000);

	protected boolean isTimesealOn;

	private Lock writeLock = new ReentrantLock(true);

	public boolean isTimeseal2On() {
		return isTimesealOn;
	}

	@Override
	public void send(String message) {
		try {
			writeLock.lock();
			getOutputStream().write(message.getBytes());
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void close() {
		try {
			if (socket != null) {
				socket.close();
			}
		} catch (IOException ioe) {
		}

		socket = null;
		daemonThread = null;
		cryptedOutputStream = null;
		initialTimesealString = null;
		if (listener != null) {
			MessageListener tempListener = listener;
			listener = null;
			try {
				tempListener.connectionClosed(inboundMessageBuffer);
			} catch (Throwable t) {
			}

			inboundMessageBuffer = null;
		}
	}

	public TimesealSocketMessageProducer(String address, int port, String initialTimestampString, boolean isTimesealOn,
			MessageListener listener) {
		try {
			this.isTimesealOn = isTimesealOn;
			this.listener = listener;
			socket = new Socket(address, port);
			this.initialTimesealString = initialTimestampString;
			init();
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	public boolean isConnected() {
		return socket != null && daemonThread != null;
	}

	/**
	 * Handles sending the timeseal ack.
	 * 
	 * @param text
	 * @return
	 * @throws IOException
	 */
	protected String handleTimeseal(String text) throws IOException {
		String result = text;
		if (LOG.isDebugEnabled())
			LOG.debug("Entering handleTimeseal " + result);
		while (result.contains("[G]\0")) {
			/**
			 * You have to ack each [G]\0! This was the major timeseal bug. Not
			 * all were acked!
			 */
			sendAck();
			result = result.replaceFirst("\\[G\\]\0", "");

			if (LOG.isDebugEnabled())
				LOG.debug("Handled ack new result " + result);
		}

		if (LOG.isDebugEnabled())
			LOG.debug("Leaving handleTimeseal " + result);
		return result;
	}

	/**
	 * The messageLoop. Reads the inputChannel and then invokes publishInput
	 * with the text read. Should really never be invoked.
	 */
	protected void messageLoop() {
		try {
			byte[] buffer = new byte[40000];
			while (isConnected()) {
				// long start = System.currentTimeMillis();
				int numRead = socket.getInputStream().read(buffer);
				if (numRead > 0) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("TimesealSocketMessageProducer " + "Read " + numRead + " bytes.");
					}

					// System.err.println("Raw in: " + new String(buffer, 0,
					// numRead));

					String text = isTimesealOn ? handleTimeseal(new String(buffer, 0, numRead))
							: new String(buffer, 0, numRead);

					if (StringUtils.isNotBlank(text)) {
						inboundMessageBuffer.append(IcsUtils.cleanupMessage(text));
						listener.messageArrived(inboundMessageBuffer);
					}
				} else {
					if (LOG.isDebugEnabled()) {
						LOG.debug("TimesealSocketMessageProducer  " + "Read 0 bytes disconnecting.");
					}
					close();
					break;
				}
				// System.err.println("Processed message in " +
				// (System.currentTimeMillis() - start));
			}
			LOG.debug("TimesealSocketMessageProducer " + "Not connected disconnecting.");
		} catch (Throwable t) {
			if (t instanceof IOException) {
				LOG.debug(
						"TimesealSocketMessageProducer " + "Connector "
								+ "IOException occured in messageLoop (These are common when disconnecting and ignorable)",
						t);
			} else {
				listener.onError("TimesealSocketMessageProducer " + "Connector Error in DaemonRun Thwoable", t);
			}
			close();
		} finally {
			LOG.debug("TimesealSocketMessageProducer  Leaving readInput");
		}
	}

	private OutputStream getOutputStream() throws IOException {
		return isTimesealOn ? cryptedOutputStream : socket.getOutputStream();
	}

	private void sendAck() throws IOException {
		try {
			writeLock.lock();
			getOutputStream().write("\0029\n".getBytes());
		} catch (IOException ioe) {
			throw ioe;
		} finally {
			writeLock.unlock();
		}
	}

	private void init() throws IOException {
		initialTime = System.currentTimeMillis();
		cryptedOutputStream = new CryptOutputStream(socket.getOutputStream());
		writeInitialTimesealString();
	}

	private void writeInitialTimesealString() throws IOException {

		if (isTimesealOn) {
			OutputStream outputstream = getOutputStream();
			try {
				writeLock.lock();
				outputstream.write(initialTimesealString.getBytes());
				outputstream.write(10);
			} catch (IOException ioe) {
				throw ioe;
			} finally {
				writeLock.unlock();
			}
		}

		daemonThread = new Thread(new Runnable() {
			@Override
			public void run() {
				messageLoop();
			}
		});
		daemonThread.setDaemon(true);
		daemonThread.setName("TimesealSocketMessageProducer Thread");
		daemonThread.setPriority(Thread.MAX_PRIORITY);
		daemonThread.start();
	}
}
