package edu.wisc.cs.sdn.vnet.rt;

import java.nio.ByteBuffer;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.Ethernet;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device {
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile) {
		super(host, logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable() {
		return this.routeTable;
	}

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile) {
		if (!routeTable.load(routeTableFile, this)) {
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile) {
		if (!arpCache.load(arpCacheFile)) {
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface) {
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));

		/********************************************************************/

		// Drop packet if not IPv4
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
			System.out.println(
					"*** -> Non IPv4 Packet dropped: " + etherPacket.toString().replace("\n", "\n\t"));
			return;
		}

		// Verify Checksum
		var packet = (net.floodlightcontroller.packet.IPv4) etherPacket.getPayload();
		var checksum = packet.getChecksum();
		packet.resetChecksum();

		byte[] data = new byte[packet.getHeaderLength() * 4];
		ByteBuffer bb = ByteBuffer.wrap(data);

		bb.put((byte) (((packet.getVersion() & 0xf) << 4) | (packet.getHeaderLength() & 0xf)));
		bb.put(packet.getDiffServ());
		bb.putShort(packet.getTotalLength());
		bb.putShort(packet.getIdentification());
		bb.putShort((short) (((packet.getFlags() & 0x7) << 13) | (packet.getFragmentOffset() & 0x1fff)));
		bb.put(packet.getTtl());
		bb.put(packet.getProtocol());
		bb.putShort(packet.getChecksum());
		bb.putInt(packet.getSourceAddress());
		bb.putInt(packet.getDestinationAddress());
		if (packet.getOptions() != null)
			bb.put(packet.getOptions());
        bb.rewind();
		int accumulation = 0;
		for (int i = 0; i < packet.getHeaderLength() * 2; ++i) {
			accumulation += 0xffff & bb.getShort();
		}
		accumulation = ((accumulation >> 16) & 0xffff)
				+ (accumulation & 0xffff);

		if (checksum != (short) (~accumulation & 0xffff)) {
			System.out.println(
					"*** -> Invalid checksum, packet dropped: " + etherPacket.toString().replace("\n", "\n\t"));
			return;
		}

		// Verify TTL
		var ttl = packet.getTtl();
		if (ttl <= 1) {
			System.out.println(
					"*** -> TTL 0, packet dropped: " + etherPacket.toString().replace("\n", "\n\t"));
			return;
		}
		packet.setTtl((byte) (ttl - 1));

        int destAddr = packet.getDestinationAddress();     
        RouteEntry tableMatch = routeTable.lookup(destAddr);
        if (tableMatch == null) {
            System.out.println(
                    "*** -> Destination address not found, packet dropped: " + etherPacket.toString().replace("\n", "\n\t"));
            return;
        }
        
        int nextHopAddr = tableMatch.getGatewayAddress();
        if (nextHopAddr == 0) {
            nextHopAddr = destAddr;
        }
        MACAddress tableMatchMACAddr = arpCache.lookup(nextHopAddr).getMac();
        if (tableMatch == null) {
            System.out.println("*** -> No route found, dropping");
            return;
        }
        etherPacket.setDestinationMACAddress(tableMatchMACAddr.toBytes());
        Iface outgoingIface = tableMatch.getInterface();
        etherPacket.setSourceMACAddress(outgoingIface.getMacAddress().toBytes());
        
        this.sendPacket(etherPacket, outgoingIface);

	}
}

