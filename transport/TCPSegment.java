import java.nio.ByteBuffer;

public class TCPSegment {

    private int seqNum;
    private int ackNum;
    private long timestamp;
    private int length;
    private boolean synSet;
    private boolean ackSet;
    private boolean finSet;
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

    public void setSequenceNumber(int sequenceNumber) {
        this.seqNum = sequenceNumber;
    }

    public int getSequenceNumber() {
        return this.seqNum;
    }
     public void setAcknowledgementNumber(int acknowledgementNumber) {
        this.ackNum = acknowledgementNumber;
    }

    public int getAcknowledgementNumber() {
        return this.ackNum;
    }

    public void startTimestamp() {
        this.timestamp = System.nanoTime();
    }

    public long getTimestamp() {
        return this.timestamp;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getLength() {
        return this.length;
    }

    public void setSyn() {
        this.synSet = true;
    }

    public boolean getSyn() {
        return this.synSet;
    }

    public void setAck() {
        this.ackSet = true;
    }
     public boolean getAck() {
        return this.ackSet;
    }

    public void setFin() {
        this.finSet = true;
    }

    public boolean getFin() {
        return this.finSet;
    }

    public void setData(byte[] data) {
        if (!(this.synSet || this.finSet)) {
            this.data = data;
        }
    }

    public byte[] getData() {
        return this.data;
    }

    public void computeChecksum() {

    }

    public short getChecksum() {
        return this.checksum
    }

    public ByteBuffer serialize() {

        if (this.timestamp == -1) {
            throw new IllegalArgumentException("Timestamp must be started");
        }
        if (this.checksum == -1) {
            throw new IllegalArgumentException("Checksum must be computed");
        }
        ByteBuffer buf = ByteBuffer.allocate(24 + data.length);
        // Sequence number - first 4 bytes
        buf.putInt(this.seqNum);
        // Acknowledgement number - next 4 bytes
        buf.putInt(this.ackNum);
        // Timestamp - next 8 bytes
        buf.putLong(this.timestamp);
        // Length + Flags - next 4 bytes
        int flags = (this.synSet ? 0b100 : 0) | (this.finSet ? 0b010 : 0) | (this.ackSet ? 0b001 : 0);
        int lengthAndFlags = (this.length << 3) | flags;
        buf.putInt(lengthAndFlags);
        // All zeroes - next 2 bytes
        buf.putShort(0);
        // Checksum - next 2 bytes
        buf.putShort(this.checksum);
        // Data
        if (this.data != null) {
            buf.put(this.data);
        }
        return buf;
    }

    public static TCPSegment deserialize(byte[] data, int length) {

        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        // Sequence number - first 4 bytes
        int seqNum = buf.getInt();
        // Acknowledgement number - next 4 bytes
        int ackNum = buf.getInt();
        // Timestamp - next 8 bytes
        long timestamp = buf.getLong();
        // Length + Flags - next 4 bytes
        int lengthAndFlags = buf.getInt();
        int length = lengthAndFlags >>> 3;
         boolean synSet = (lengthAndFlags & 0b100) != 0;
        boolean finSet = (lengthAndFlags & 0b010) != 0;
        boolean ackSet = (lengthAndFlags & 0b001) != 0;
        // All zeroes - next 2 bytes
        buf.getShort();
        // Checksum - next 2 bytes
        short checksum = buf.getShort();
        // Data
        byte[] data = new byte[length];
        buf.get(data);

        return new TCPSegment(seqNum, ackNum, timestamp, length, synSet, finSet, ackSet, checksum, data);

    }

}

