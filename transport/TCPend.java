import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TCPend {
	static double timeout = 5000.0;
	static double maxTimeout = 5000.0;
	static double ertt = 0;
	static double edev = 0;
	static final double a = 0.875;
	static final double b = 0.75;
	static boolean firstAck = true;

	static long startNanoTime;

	static long statDataTransferred = 0;
	static long statPacketsSent = 0;
	static long statPacketsReceived = 0;
	static long statOutOfSequence = 0;
	static long statIncorrectChecksum = 0;
	static long statRetransmissions = 0;
	static long statDuplicateAcks = 0;

	static class SenderSegment {
		int seqNum;
		byte[] data;
		boolean resent;
	}

	static class PacketInfo {
		TCPSegment seg;
		InetAddress ip;
		int port;
	}

	public static void main(String[] args) throws Exception {
		int port = -1;
		String remoteIP = null;
		int remotePort = -1;
		String fileName = null;
		int mtu = -1;
		int sws = -1;
		boolean isSender = false;

		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "-p":
					port = Integer.parseInt(args[++i]);
					break;
				case "-s":
					remoteIP = args[++i];
					isSender = true;
					break;
				case "-a":
					remotePort = Integer.parseInt(args[++i]);
					break;
				case "-f":
					fileName = args[++i];
					break;
				case "-m":
					mtu = Integer.parseInt(args[++i]);
					break;
				case "-c":
					sws = Integer.parseInt(args[++i]);
					break;
			}
		}

		startNanoTime = System.nanoTime();

		if (isSender) {
			handleSender(port, remoteIP, remotePort, fileName, mtu, sws);
		} else {
			handleReceiver(port, fileName, mtu, sws);
		}
	}

	static void logPacket(String direction, TCPSegment seg) {
		double timeSec = (System.nanoTime() - startNanoTime) / 1_000_000_000.0;
		String sFlag = seg.getSyn() ? "S" : "-";
		String aFlag = seg.getAck() ? "A" : "-";
		String fFlag = seg.getFin() ? "F" : "-";
		String dFlag = (seg.getData() != null && seg.getData().length > 0) ? "D" : "-";
		int dataLen = seg.getLength();

		System.out.printf("%s %.3f %s %s %s %s %d %d %d\n",
				direction, timeSec, sFlag, aFlag, fFlag, dFlag,
				seg.getSequenceNumber(), dataLen, seg.getAcknowledgementNumber());
	}

	static void updateTimeout(long sendNanoTimestamp) {
		// No ack
		if (sendNanoTimestamp == -1) {
			timeout *= 2;
		}
		// Ack
		else {
			long currentNano = System.nanoTime();
			double rttMs = (currentNano - sendNanoTimestamp) / 1_000_000.0;

			if (firstAck) {
				ertt = rttMs;
				edev = 0;
				timeout = 2 * ertt;
				firstAck = false;
			} else {
				double sdev = Math.abs(rttMs - ertt);
				ertt = a * ertt + (1 - a) * rttMs;
				edev = b * edev + (1 - b) * sdev;
				timeout = ertt + 4 * edev;
			}
		}
		if (timeout > maxTimeout) {
			timeout = maxTimeout;
		}

		System.out.println("Timeout: " + timeout);
	}

	static PacketInfo receivePacketFrom(DatagramSocket socket) throws Exception {
		byte[] buf = new byte[65535];
		DatagramPacket dp = new DatagramPacket(buf, buf.length);
		socket.receive(dp);
		statPacketsReceived++;
		TCPSegment seg = TCPSegment.deserialize(dp.getData(), dp.getLength());
		logPacket("rcv", seg);
		PacketInfo info = new PacketInfo();
		info.seg = seg;
		info.ip = dp.getAddress();
		info.port = dp.getPort();
		return info;
	}

	static TCPSegment receivePacket(DatagramSocket socket) throws Exception {
		return receivePacketFrom(socket).seg;
	}

	static void sendPacket(DatagramSocket socket, TCPSegment seg, InetAddress ip, int port) throws Exception {
		ByteBuffer buf = seg.serialize();
		DatagramPacket dp = new DatagramPacket(buf.array(), buf.limit(), ip, port);
		socket.send(dp);
		statPacketsSent++;
		logPacket("snd", seg);
	}

	static void handleSender(int port, String remoteIP, int remotePort, String fileName, int mtu, int sws)
			throws Exception {
		DatagramSocket socket = new DatagramSocket(port);
		InetAddress destIP = InetAddress.getByName(remoteIP);

		File file = new File(fileName);
		byte[] fileData = new byte[0];
		if (file.exists()) {
			FileInputStream fis = new FileInputStream(file);
			fileData = new byte[(int) file.length()];
			fis.read(fileData);
			fis.close();
		}

		List<SenderSegment> segments = new ArrayList<>();
		int currentSeq = 1;
		for (int i = 0; i < fileData.length; i += mtu) {
			int len = Math.min(mtu, fileData.length - i);
			byte[] chunk = new byte[len];
			System.arraycopy(fileData, i, chunk, 0, len);
			SenderSegment seg = new SenderSegment();
			seg.seqNum = currentSeq;
			seg.data = chunk;
			seg.resent = false;
			segments.add(seg);
			currentSeq += len;
		}

		TCPSegment syn = new TCPSegment(0, 0, System.nanoTime(), 0, true, false, false, (short) 0, null);
		syn.setChecksum(syn.computeChecksum());
		sendPacket(socket, syn, destIP, remotePort);

		socket.setSoTimeout((int) timeout);
		TCPSegment synAck = null;
		int synRetries = 0;
		while (synAck == null && synRetries < 16) {
			try {
				synAck = receivePacket(socket);
				if (synAck.getChecksum() != synAck.computeChecksum()) {
					statIncorrectChecksum++;
					synAck = null;
					continue;
				}
				if (!synAck.getSyn() || !synAck.getAck() || synAck.getAcknowledgementNumber() != 1) {
					synAck = null;
				}
			} catch (SocketTimeoutException e) {
				syn.startTimestamp();
				syn.setChecksum(syn.computeChecksum());
				sendPacket(socket, syn, destIP, remotePort);
				synRetries++;
				statRetransmissions++;
			}
		}
		if (synAck == null) {
			System.out.println("Failed to connect");
			return;
		}

		TCPSegment ack = new TCPSegment(1, 1, synAck.getTimestamp(), 0, false, true, false, (short) 0, null);
		ack.setChecksum(ack.computeChecksum());
		sendPacket(socket, ack, destIP, remotePort);

		updateTimeout(syn.getTimestamp());
		socket.setSoTimeout((int) timeout);

		int base = 0;
		int nextSegment = 0;
		int dupAcks = 0;
		int lastAckReceived = 1;

		while (base < segments.size()) {
			while (nextSegment < base + sws && nextSegment < segments.size()) {
				SenderSegment seg = segments.get(nextSegment);
				TCPSegment p = new TCPSegment(seg.seqNum, 1, System.nanoTime(), seg.data.length, false, true, false,
						(short) 0, seg.data);
				p.setChecksum(p.computeChecksum());
				sendPacket(socket, p, destIP, remotePort);
				nextSegment++;
			}

			try {
				TCPSegment p = receivePacket(socket);
				if (p.getChecksum() != p.computeChecksum()) {
					statIncorrectChecksum++;
					continue;
				}
				if (p.getAck()) {
					int ackNum = p.getAcknowledgementNumber();
					if (ackNum > lastAckReceived) {
						for (int i = base; i < nextSegment; i++) {
							SenderSegment seg = segments.get(i);
							if (seg.seqNum + seg.data.length == ackNum) {
								if (!seg.resent) {
									updateTimeout(p.getTimestamp());
									socket.setSoTimeout((int) timeout);
								}
								break;
							}
						}

						while (base < segments.size() && segments.get(base).seqNum < ackNum) {
							statDataTransferred += segments.get(base).data.length;
							base++;
						}
						lastAckReceived = ackNum;
						dupAcks = 0;
					} else if (ackNum == lastAckReceived) {
						dupAcks++;
						statDuplicateAcks++;
						if (dupAcks == 3) {
							SenderSegment seg = segments.get(base);
							TCPSegment rtx = new TCPSegment(seg.seqNum, 1, System.nanoTime(), seg.data.length,
									false, true, false, (short) 0, seg.data);
							rtx.setChecksum(rtx.computeChecksum());
							sendPacket(socket, rtx, destIP, remotePort);
							statRetransmissions++;
						}
					}
				}
			} catch (SocketTimeoutException e) {
				SenderSegment seg = segments.get(base);
				TCPSegment rtx = new TCPSegment(seg.seqNum, 1, System.nanoTime(), seg.data.length, false, true,
						false, (short) 0, seg.data);
				rtx.setChecksum(rtx.computeChecksum());
				sendPacket(socket, rtx, destIP, remotePort);
				statRetransmissions++;

				updateTimeout(-1);
				socket.setSoTimeout((int) timeout);
			}
		}

		int finSeq = currentSeq;
		TCPSegment fin = new TCPSegment(finSeq, 1, System.nanoTime(), 0, false, true, true, (short) 0, null);
		fin.setChecksum(fin.computeChecksum());
		sendPacket(socket, fin, destIP, remotePort);

		boolean finAcked = false;
		boolean finReceived = false;
		int finRetries = 0;
		socket.setSoTimeout((int) timeout);

		while ((!finAcked || !finReceived) && finRetries < 16) {
			try {
				TCPSegment p = receivePacket(socket);
				if (p.getChecksum() != p.computeChecksum()) {
					statIncorrectChecksum++;
					continue;
				}
				if (p.getAck() && p.getAcknowledgementNumber() == finSeq + 1) {
					finAcked = true;
				}
				if (p.getFin()) {
					finReceived = true;
					TCPSegment ackFin = new TCPSegment(finSeq + 1, p.getSequenceNumber() + 1, p.getTimestamp(), 0,
							false, true, false, (short) 0, null);
					ackFin.setChecksum(ackFin.computeChecksum());
					sendPacket(socket, ackFin, destIP, remotePort);
				}
			} catch (SocketTimeoutException e) {
				if (!finAcked) {
					sendPacket(socket, fin, destIP, remotePort);
					statRetransmissions++;
					finRetries++;
				} else if (!finReceived) {
					finRetries++;
				}
			}
		}

		printStats();
	}

	static void handleReceiver(int port, String fileName, int mtu, int sws) throws Exception {
		DatagramSocket socket = new DatagramSocket(port);

		TCPSegment syn = null;
		InetAddress clientIP = null;
		int clientPort = -1;

		while (syn == null) {
			PacketInfo p = receivePacketFrom(socket);
			if (p.seg.getChecksum() != p.seg.computeChecksum()) {
				statIncorrectChecksum++;
				continue;
			}
			if (p.seg.getSyn()) {
				syn = p.seg;
				clientIP = p.ip;
				clientPort = p.port;
			}
		}

		TCPSegment synAck = new TCPSegment(0, 1, syn.getTimestamp(), 0, true, true, false, (short) 0, null);
		synAck.setChecksum(synAck.computeChecksum());
		sendPacket(socket, synAck, clientIP, clientPort);

		int expectedSeq = 1;
		Map<Integer, TCPSegment> buffer = new HashMap<>();
		FileOutputStream fos = new FileOutputStream(fileName);

		while (true) {
			TCPSegment p = null;
			try {
				p = receivePacket(socket);
			} catch (SocketTimeoutException e) {
				continue;
			}
			if (p.getChecksum() != p.computeChecksum()) {
				statIncorrectChecksum++;
				continue;
			}

			if (p.getSyn()) {
				sendPacket(socket, synAck, clientIP, clientPort);
				continue;
			}

			if (p.getFin()) {
				TCPSegment ackFin = new TCPSegment(1, p.getSequenceNumber() + 1, p.getTimestamp(), 0, false, true,
						false, (short) 0, null);
				ackFin.setChecksum(ackFin.computeChecksum());
				sendPacket(socket, ackFin, clientIP, clientPort);

				TCPSegment fin = new TCPSegment(1, p.getSequenceNumber() + 1, System.nanoTime(), 0, false, true, true,
						(short) 0, null);
				fin.setChecksum(fin.computeChecksum());
				sendPacket(socket, fin, clientIP, clientPort);

				socket.setSoTimeout(5000);
				try {
					while (true) {
						TCPSegment lastAck = receivePacket(socket);
						if (lastAck.getAck() && lastAck.getAcknowledgementNumber() == 2) {
							break;
						}
					}
				} catch (SocketTimeoutException e) {
				}
				break;
			}

			if (p.getData() != null && p.getData().length > 0) {
				int seq = p.getSequenceNumber();
				if (seq == expectedSeq) {
					fos.write(p.getData());
					statDataTransferred += p.getData().length;
					expectedSeq += p.getData().length;

					while (buffer.containsKey(expectedSeq)) {
						TCPSegment buffered = buffer.remove(expectedSeq);
						fos.write(buffered.getData());
						statDataTransferred += buffered.getData().length;
						expectedSeq += buffered.getData().length;
					}
				} else if (seq > expectedSeq) {
					if (!buffer.containsKey(seq)) {
						buffer.put(seq, p);
						statOutOfSequence++;
					}
				}

				TCPSegment ack = new TCPSegment(1, expectedSeq, p.getTimestamp(), 0, false, true, false, (short) 0,
						null);
				ack.setChecksum(ack.computeChecksum());
				sendPacket(socket, ack, clientIP, clientPort);
			} else if (p.getAck()) {
				// ACK
			}
		}

		fos.close();
		printStats();
	}

	static void printStats() {
		System.out.printf("%d %d %d %d %d %d\n",
				statDataTransferred,
				statPacketsSent + statPacketsReceived,
				statOutOfSequence,
				statIncorrectChecksum,
				statRetransmissions,
				statDuplicateAcks);
	}
}
