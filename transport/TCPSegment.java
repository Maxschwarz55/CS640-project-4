import java.nio.ByteBuffer;

public class TCPSegment {
    private int seqNum;
    private int ackNum;
    private long timestamp;
    private int length;
    private boolean synSet;
    private boolean finSet;
    private boolean ackSet;
    private short checksum;
    private byte[] data;

    public TCPSegment(int seqNum, int ackNum, long timestamp, int length,
                      boolean synSet, boolean ackSet, boolean finSet, short checksum, byte[] data) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.timestamp = timestamp;
        this.length = length;
        this.synSet = synSet;
        this.ackSet = ackSet;
        this.finSet = finSet;
        this.checksum = checksum;
        this.data = data;
    }

    public void setSequenceNumber(int sequenceNumber) { this.seqNum = sequenceNumber; }
    public int getSequenceNumber() { return this.seqNum; }
    public void setAcknowledgementNumber(int acknowledgementNumber) { this.ackNum = acknowledgementNumber; }
    public int getAcknowledgementNumber() { return this.ackNum; }
    public void startTimestamp() { this.timestamp = System.nanoTime(); }
    public long getTimestamp() { return this.timestamp; }
    public void setLength(int length) { this.length = length; }
    public int getLength() { return this.length; }
    public void setSyn(boolean syn) { this.synSet = syn; }
    public boolean getSyn() { return this.synSet; }
    public void setAck(boolean ack) { this.ackSet = ack; }
    public boolean getAck() { return this.ackSet; }
    public void setFin(boolean fin) { this.finSet = fin; }
    public boolean getFin() { return this.finSet; }
    public void setData(byte[] data) { this.data = data; }
    public byte[] getData() { return this.data; }

    public short computeChecksum() {
        short checksumTemp = this.checksum;
        this.checksum = 0;
        ByteBuffer buf = this.serialize();
        byte[] packetData = buf.array();

        int sum = 0;
        for (int i = 0; i < packetData.length; i += 2) {
            int firstByte = packetData[i] & 0xFF;
            int secondByte = 0;
            if (i + 1 < packetData.length) {
                secondByte = packetData[i + 1] & 0xFF;
            }
            sum += (firstByte << 8) | secondByte;
        }
        while ((sum >>> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >>> 16);
        }
        this.checksum = checksumTemp;
        return (short) ~sum;
    }

    public void setChecksum(short checksum) { this.checksum = checksum; }
    public short getChecksum() { return this.checksum; }

    public ByteBuffer serialize() {
        int dataLength = (this.data != null) ? this.data.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(24 + dataLength);
        buf.putInt(this.seqNum);
        buf.putInt(this.ackNum);
        buf.putLong(this.timestamp);
        int flags = (this.synSet ? 0b100 : 0) | (this.finSet ? 0b010 : 0) | (this.ackSet ? 0b001 : 0);
        int lengthAndFlags = (this.length << 3) | flags;
        buf.putInt(lengthAndFlags);
        buf.putShort((short) 0);
        buf.putShort(this.checksum);
        if (this.data != null) {
            buf.put(this.data);
        }
        return buf;
    }

    public static TCPSegment deserialize(byte[] data, int packetLength) {
        ByteBuffer buf = ByteBuffer.wrap(data, 0, packetLength);
        int seqNum = buf.getInt();
        int ackNum = buf.getInt();
        long timestamp = buf.getLong();
        int lengthAndFlags = buf.getInt();
        int pLength = lengthAndFlags >>> 3;
        boolean synSet = (lengthAndFlags & 0b100) != 0;
        boolean finSet = (lengthAndFlags & 0b010) != 0;
        boolean ackSet = (lengthAndFlags & 0b001) != 0;
        buf.getShort();
        short checksum = buf.getShort();
        byte[] pData = null;
        if (pLength > 0 && buf.remaining() >= pLength) {
            pData = new byte[pLength];
            buf.get(pData);
        }
        return new TCPSegment(seqNum, ackNum, timestamp, pLength, synSet, ackSet, finSet, checksum, pData);
    }
}
