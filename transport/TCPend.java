import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;



public static void senderHandshake(DatagramSocket senderSocket, InetAddress destIP, int destPort, int mtu) {
    
    TCPSegment synSegment = new TCPSegment(0, 0, -1, 0, true, false, false, -1, null);
    synSegment.computeChecksum();
    synSegment.startTimestamp();
    ByteBuffer synData = synSegment.serialize();
    DatagramPacket synPacket = new DatagramPacket(synData.array(), 0, destIP, destPort);

    senderSocket.send(synPacket);
    
    byte[] recvBuffer = new byte[24 + mtu];
    DatagramPacket recvSynAckPacket = new DatagramPacket(recvBuffer, 24 + mtu);

    senderSocket.receive(recvSynAckPacket);
    
    byte[] recvSynAckData = recvSynAckPacket.getData();
    TCPSegment recvSynAckSegment = TCPSegment.deserialize(recvSynAckData, recvSynAckPacket.getLength());
    if (recvSynAckSegment.getSyn() && recvSynAckSegment.getSequenceNumber() == 0 
        && recvSynAckSegment.getAck() && recvSynAckSegment.getAcknowledgementNumber() == 1) {
        
        TCPSegment ackSegment = new TCPSegment(1, 1, recvSynAckSegment.getTimestamp(), 0, false, true, false, -1, null);
        ackSegment.computeChecksum();
        ByteBuffer ackData = ackSegment.serialize();
        DatagramPacket ackPacket = new DatagramPacket(ackData.array(), 0, destIP, destPort);

        senderSocket.send(ackPacket);
    }

    int RTT = System.nanoTime() - recvSynAckSegment.getTimestamp();
        
}

public static void handleSender(int sourcePort, String destIP, int destPort, String fileName, int mtu, int sws) {
    
    DatagramSocket senderSocket = new DatagramSocket(sourcePort);
    InetAddress destInetAddress = InetAddress.getByName(destIP);
    senderHandshake(senderSocket, destInetAddress, destPort, mtu);

}

public static void receiverHandshake(DatagramSocket receiverSocket, int mtu) {

    byte[] recvBuffer = new byte[24 + mtu];
    DatagramPacket recvSynPacket = new DatagramPacket(recvBuffer, 24 + mtu);
    
    receiverSocket.receive(recvSynPacket);
    InetAddress destIP = recvSynPacket.getAddress();
    int destPort = recvSynPacket.getPort();

    byte[] recvSynData = recvSynPacket.getData();
    TCPSegment recvSynSegment = TCPSegment.deserialize(recvSynData, recvSynPacket.getLength());

    if (recvSynSegment.getSyn() && recvSynSegment.getSequenceNumber() == 0) {
        
        TCPSegment synAckSegment = new TCPSegment(0, 1, recvSynSegment.getTimestamp(), 0, true, true, false, -1, null);
        synAckSegment.computeChecksum();
        ByteBuffer synAckData = synAckSegment.serialize();
        DatagramPacket synAckPacket = new DatagramPacket(synAckData.array(), 0, destIP, destPort);
        
        receiverSocket.send(synAckPacket);
    }
}

public static void handleReceiver(int sourcePort, String fileName, int mtu, int sws) {
    
    DatagramSocket receiverSocket = new DatagramSocket(sourcePort);
    recieverHandshake(receiverSocket, mtu); 

}

public static void main(String[] args) {

    if (args.length != 12 && args.lengt != 8) {
        System.out.println("Error: Command line args must be of length 12 or 8 (Not including executable)");
        System.exit(1);
    }

    int sourcePort;
    String fileName;
    int mtu;
    int sws;
    String destIP = null;
    int destPort = -1;

    if (!args[0].equals("-p")) {
        System.out.println("Error: First arg must contain -p flag");
        System.exit(1);
    }

    try {
        sourcePort = Integer.parseInt(args[1]);
        if (sourcePort < 0 || sourcePort > 65_535) {
            System.out.println("Error: Source port must be in range 0-65535");
            System.exit(1);
        }
    } 
    catch (NumberFormatException e) {
        System.out.println("Error: Source port not a number");
        System.exit(1);
    }

    String mode = args[2];

    if (!mode.equals("-s") && !mode.equals("-m")) {
        System.out.println("Error: Mode must be -s or -m");
        System.exit(1);
    }

    if (mode.equals("-s")) {
        try {
            destIP = Integer.parseInt(args[3]);
            if (destIP < 0) {
                System.out.println("Error: Destination IP must be positive");
                System.exit(1);
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Error: Destination IP not a number");
            System.exit(1);
        }
    }
    else {
        try {
            mtu = Integer.parseInt(args[3]);
            if (mtu < 0) {
                System.out.println("Error: MTU must be positive");
                System.exit(1);
            }

        }
        catch (NumberFormatException e) {
            System.out.println("Error: MTU not a number");
            System.exit(1);
        }
    }
    String nextOption = args[4];
    if (!nextOption.equals("-a") && !nextOption.equals("-c")) {
        System.out.println("Error: Option must be -a or -c");
        System.exit(1);
    }

    if (nextOption.equals("-a")) {
        try {
            destPort = Integer.parseInt(args[5]);
            if (destPort < 0 || destPort > 65_535) {
                System.out.println("Error: Destination port must be in range 0-65535");
                System.exit(1);
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Error: Destination port not a number");
            System.exit(1);
        }
    }

    if (nextOption.equals("-c") {
        try {
            sws = Integer.parseInt(args[5]);
            if (sws < 0) {
                System.out.println("Error: SWS must be positive");
                System.exit(1);
            }
        }
        catch (NumberFormatException e) {
            System.out.println("Error: SWS not a number");
            System.exit(1);
        }
    }

    if (!args[6].equals("-f")) {
        System.out.println("Error: must specify filename with -f");
        System.exit(1);
    }

    fileName = args[7];

    if (args.length() == 12) {
        if (!args[8].equals("-m")) {
            System.out.println("Error: must specify MTU with -m");
            System.exit(1);
        }
        try {
            mtu = Integer.parseInt(args[9]);
            if (mtu < 0) {
                System.out.println("Error: MTU must be positive");
                System.exit(1);
            }

        }
        catch (NumberFormatException e) {
            System.out.println("Error: MTU not a number");
            System.exit(1);
        }
        if (!args[10].equals("-c")) {
            System.out.println("Error: must specify SWS with -c");
            System.exit(1);
        }
        try {
            sws = Integer.parseInt(args[11]);
            if (sws < 0) {
                System.out.println("Error: SWS must be positive");
                System.exit(1);
            }

        }
        catch (NumberFormatException e) {
            System.out.println("Error: SWS not a number");
            System.exit(1);
        }

        if (mode.equals("-s")) {
            handleSender(sourcePort, destIP, destPort, fileName, mtu, sws);
        }

    }

    else if (mode.equals("-m")) {
        handleReciever(sourcePort, fileName, mtu, sws);
    }
}
