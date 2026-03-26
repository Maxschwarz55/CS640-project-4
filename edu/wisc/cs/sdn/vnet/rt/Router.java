package edu.wisc.cs.sdn.vnet.rt;

import java.util.List;
import java.util.LinkedList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
	private RouteTable ripRouteTable;
	private ScheduledExecutorService ripExecutor;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile, boolean tableProvided) {
		super(host, logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();

		if (!tableProvided) {
			this.ripRouteTable = new RouteTable();
			this.ripExecutor = Executors.newSingleThreadScheduledExecutor();
			this.initRIP();
		}
	}

	/**
	 * Initialize RIP by adding directly connected subnets to the RIP route table
	 * and scheduling periodic RIP updates.
	 */
	private void initRIP() {
		// Add directly connected subnets to RIP route table
		for (Iface iface : this.getInterfaces().values()) {
			int networkAddress = iface.getIpAddress() & iface.getSubnetMask();
			this.ripRouteTable.insert(networkAddress, 0, iface.getSubnetMask(), iface, 1, System.currentTimeMillis());
		}

		// Send initial RIP request
		sendRIPRequest();

		// Schedule periodic unsolicited RIP responses
		this.ripExecutor.scheduleAtFixedRate(() -> {
			sendRIPResponse(null, 0, null, true); // Unsolicited response
		}, 0, 10, TimeUnit.SECONDS); // Send immediately, then every 10 seconds

		// Schedule route table entry timeout checks
		this.ripExecutor.scheduleAtFixedRate(() -> {
			synchronized (this.ripRouteTable.getEntries()) {
				List<RouteEntry> entriesToRemove = new LinkedList<>();
				for (RouteEntry entry : this.ripRouteTable.getEntries()) {
					// Do not remove directly connected routes
					if (entry.getGatewayAddress() == 0) {
						continue;
					}

					if (System.currentTimeMillis() - entry.getTimestamp() > 30 * 1000) { // 30 seconds timeout
						entriesToRemove.add(entry);
					}
				}
				for (RouteEntry entry : entriesToRemove) {
					this.ripRouteTable.remove(entry.getDestinationAddress(), entry.getMaskAddress());
				}
			}
			updateMainRouteTable();
		}, 5, 5, TimeUnit.SECONDS); // Check every 5 seconds
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

	private void sendRIPRequest() {
		// Ensure directly connected subnets are in the RIP route table
		this.updateDirectlyConnectedRoutes();

		RIPv2 rip = new RIPv2();
		rip.setCommand(RIPv2.COMMAND_REQUEST);

		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(rip);

		IPv4 ip = new IPv4();
		ip.setDestinationAddress(IPv4.toIPv4Address("224.0.0.9")); // Multicast
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);

		Ethernet eth = new Ethernet();
		eth.setEtherType(Ethernet.TYPE_IPv4);
		eth.setDestinationMACAddress("FF:FF:FF:FF:FF:FF"); // Broadcast
		eth.setPayload(ip);

		for (Iface iface : this.getInterfaces().values()) {
			ip.setSourceAddress(iface.getIpAddress());
			eth.setSourceMACAddress(iface.getMacAddress().toBytes());
			
			// Reset checksums so they are recomputed for each interface
			ip.setChecksum((short)0);
			udp.setChecksum((short)0);
			
			this.sendPacket(eth, iface);
		}
	}

	private void updateDirectlyConnectedRoutes() {
		for (Iface iface : this.getInterfaces().values()) {
			int networkAddress = iface.getIpAddress() & iface.getSubnetMask();
			synchronized (this.ripRouteTable.getEntries()) {
				boolean found = false;
				for (RouteEntry entry : this.ripRouteTable.getEntries()) {
					if (entry.getDestinationAddress() == networkAddress && entry.getMaskAddress() == iface.getSubnetMask()) {
						found = true;
						break;
					}
				}
				if (!found) {
					this.ripRouteTable.insert(networkAddress, 0, iface.getSubnetMask(), iface, 1, System.currentTimeMillis());
				}
			}
		}
		updateMainRouteTable();
	}

	private void updateMainRouteTable() {
		synchronized (this.routeTable.getEntries()) {
			this.routeTable.clear();
			synchronized (this.ripRouteTable.getEntries()) {
				for (RouteEntry entry : this.ripRouteTable.getEntries()) {
					this.routeTable.insert(entry.getDestinationAddress(), entry.getGatewayAddress(), entry.getMaskAddress(), entry.getInterface());
				}
			}
		}
	}



	private void sendRIPResponse(Iface outIface, int dstIp, MACAddress dstMac, boolean isUnsolicited) {
		// Ensure directly connected subnets are in the RIP route table
		this.updateDirectlyConnectedRoutes();

		RIPv2 rip = new RIPv2();
		rip.setCommand(RIPv2.COMMAND_RESPONSE);

		for (RouteEntry entry : this.ripRouteTable.getEntries()) {
			// Split horizon: don't advertise routes back out the interface they were learned from
			if (outIface != null && entry.getInterface() == outIface) {
				continue;
			}
			RIPv2Entry ripEntry = new RIPv2Entry(entry.getDestinationAddress(), entry.getMaskAddress(), entry.getMetric());
			rip.addEntry(ripEntry);
		}

		UDP udp = new UDP();
		udp.setSourcePort(UDP.RIP_PORT);
		udp.setDestinationPort(UDP.RIP_PORT);
		udp.setPayload(rip);

		IPv4 ip = new IPv4();
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setPayload(udp);

		Ethernet eth = new Ethernet();
		eth.setEtherType(Ethernet.TYPE_IPv4);
		eth.setPayload(ip);

		if (isUnsolicited) {
			ip.setDestinationAddress(IPv4.toIPv4Address("224.0.0.9")); // Multicast
			eth.setDestinationMACAddress("FF:FF:FF:FF:FF:FF"); // Broadcast
			for (Iface iface : this.getInterfaces().values()) {
				ip.setSourceAddress(iface.getIpAddress());
				eth.setSourceMACAddress(iface.getMacAddress().toBytes());
				
				// Reset checksums so they are recomputed for each interface
				ip.setChecksum((short)0);
				udp.setChecksum((short)0);
				
				this.sendPacket(eth, iface);
			}
		} else {
			// Directed response
			ip.setDestinationAddress(dstIp);
			ip.setSourceAddress(outIface.getIpAddress());
			eth.setDestinationMACAddress(dstMac.toBytes());
			eth.setSourceMACAddress(outIface.getMacAddress().toBytes());
			
			// Reset checksums
			ip.setChecksum((short)0);
			udp.setChecksum((short)0);
			
			this.sendPacket(eth, outIface);
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

					if (ripPacket.getCommand() == RIPv2.COMMAND_REQUEST) {
						// Send directed RIP response
						sendRIPResponse(inIface, ipPacket.getSourceAddress(), MACAddress.valueOf(etherPacket.getSourceMACAddress()), false);
					} else if (ripPacket.getCommand() == RIPv2.COMMAND_RESPONSE) {
						// Update ripRouteTable based on received entries
						for (RIPv2Entry newEntry : ripPacket.getEntries()) {
							int newMetric = newEntry.getMetric() + 1; // Increment metric
							if (newMetric > 15) { // Ignore routes with metric > 15 (infinity)
								continue;
							}

							int dstIp = newEntry.getAddress();
							int subnetMask = newEntry.getSubnetMask();
							int gateway = ipPacket.getSourceAddress(); // Gateway is the sender of the RIP response

							RouteEntry existingEntry = this.ripRouteTable.find(dstIp, subnetMask);

							if (existingEntry == null) {
								// No existing route, insert new one
								this.ripRouteTable.insert(dstIp, gateway, subnetMask, inIface, newMetric, System.currentTimeMillis());
							} else {
								// Existing route found
								if (newMetric < existingEntry.getMetric()) {
									// New route has a better metric, update it
									this.ripRouteTable.update(dstIp, subnetMask, gateway, inIface, newMetric, System.currentTimeMillis());
								} else if (existingEntry.getGatewayAddress() == gateway) {
									// Same gateway, update metric and timestamp
									existingEntry.setMetric(newMetric);
									existingEntry.setTimestamp(System.currentTimeMillis());
								}
							}
						}
						// After updating ripRouteTable, update the main routeTable
						updateMainRouteTable();
					}
					return; // Packet handled by RIP, do not forward
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

	@Override
	public void destroy() {
		if (this.ripExecutor != null) {
			this.ripExecutor.shutdownNow();
		}
		super.destroy();
	}



}