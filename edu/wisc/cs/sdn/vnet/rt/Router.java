package edu.wisc.cs.sdn.vnet.rt;

import java.nio.ByteBuffer;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;

import java.util.HashMap;
import java.util.LinkedList;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device {
	/** Routing table for the router */
	private RouteTable routeTable;

	private HashMap<Integer, RIPTableEntry> ripRouteTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile, boolean initTable) {
		super(host, logfile);
		this.routeTable = initTable ? new RouteTable() : null;
		if (this.routeTable == null) {
			ripRouteTable = new HashMap<Integer, RIPTableEntry>();
			this.initRIPTable();
		}
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

	public void initRIPTable() {

		var interfaces = this.getInterfaces();
		for (String interfaceName : interfaces.keySet()) {
			Iface currInterface = interfaces.get(interfaceName);
			int currInterfaceAddr = currInterface.getIpAddress();
			int currInterfaceMask = currInterface.getSubnetMask();
			var entry = new RIPTableEntry(new RIPv2Entry(currInterfaceAddr, currInterfaceMask, 1),
					currInterface);
			this.ripRouteTable.put(currInterfaceAddr & currInterfaceMask, entry);
		}
	}

	public void handleRIPPacket(RIPv2 ripPacket, Iface iface) {
		for (var newEntry : ripPacket.getEntries()) {
			int newEntryAddress = newEntry.getAddress();
			int newEntryMask = newEntry.getSubnetMask();
			int lookup = newEntryAddress & newEntryMask;
			if (ripRouteTable.containsKey(lookup)) {
				RIPTableEntry currEntry = ripRouteTable.get(lookup);
				if (newEntry.getMetric() < currEntry.getRIPEntry().getMetric()) {
					ripRouteTable.replace(lookup, new RIPTableEntry(newEntry, iface));
				}
			} else {
				ripRouteTable.put(lookup, new RIPTableEntry(newEntry, iface));
			}
		}
	}

	public void sendRIPPacket() {
		RIPv2 rip = new RIPv2();
		rip.setCommand(RIPv2.COMMAND_REQUEST);
		for (RIPTableEntry entry : this.ripRouteTable.values()) {
			rip.addEntry(entry.getRIPEntry());
		}

		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(rip);

		IPv4 ip = new IPv4();
		ip.setDestinationAddress(0xE0000009);
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);

		Ethernet eth = new Ethernet();
		eth.setEtherType(Ethernet.TYPE_IPv4);
		eth.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
		eth.setPayload(ip);

		for (RIPTableEntry entry : this.ripRouteTable.values()) {
			eth.setSourceMACAddress(entry.getIface().getMacAddress().toBytes());
			ip.setSourceAddress(entry.getIface().getIpAddress());
			this.sendPacket(eth, entry.getIface());
		}

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
		} else {
			IPv4 ipPacket = (IPv4) etherPacket.getPayload();
			if (ipPacket.getProtocol() == IPv4.PROTOCOL_UDP) {
				UDP udpPacket = (UDP) ipPacket.getPayload();
				if (udpPacket.getSourcePort() == UDP.RIP_PORT && udpPacket.getDestinationPort() == UDP.RIP_PORT) {
					RIPv2 ripPacket = (RIPv2) udpPacket.getPayload();
					handleRIPPacket(ripPacket, inIface);
				}

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
						"*** -> Destination address not found, packet dropped: "
								+ etherPacket.toString().replace("\n", "\n\t"));
				return;
			}

			if (this.routeTable.lookup(destAddr).getInterface() == inIface) {
				return;
			}

			int nextHopAddr = tableMatch.getGatewayAddress();
			if (nextHopAddr == 0) {
				nextHopAddr = destAddr;
			}
			MACAddress tableMatchMACAddr = arpCache.lookup(nextHopAddr).getMac();
			etherPacket.setDestinationMACAddress(tableMatchMACAddr.toBytes());
			Iface outgoingIface = tableMatch.getInterface();
			etherPacket.setSourceMACAddress(outgoingIface.getMacAddress().toBytes());

			this.sendPacket(etherPacket, outgoingIface);

		}
	}

	private class RIPTableEntry {
		public RIPv2Entry ripEntry;
		public Iface iface;

		public RIPTableEntry(RIPv2Entry ripEntry, Iface iface) {
			this.ripEntry = ripEntry;
			this.iface = iface;
		}

		public RIPv2Entry getRIPEntry() {
			return this.ripEntry;
		}

		public Iface getIface() {
			return this.iface;
		}
	}

}