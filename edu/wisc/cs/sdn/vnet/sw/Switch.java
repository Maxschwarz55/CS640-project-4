package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;
import java.util.HashMap;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Map;
/**
 * @author Aaron Gember-Jacobson
 */
class MACAddressAndTimeout {
    private MACAddress address;
    private Instant timeout; 

    public MACAddressAndTimeout(MACAddress address, int seconds) {
        this.address = address;
        this.timeout = Instant.now().plus(Duration.ofSeconds(seconds));
    }

    public MACAddress getAddress() {
        return address;
    }

    public Instant getTimeout() {
        return timeout;
    }

    public void setTimeout(int seconds) {
        this.timeout = Instant.now().plus(Duration.ofSeconds(seconds));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MACAddressAndTimeout)) return false;
        MACAddressAndTimeout other = (MACAddressAndTimeout) o;
        return this.address.equals(other.address);
    }
    @Override
    public int hashCode() {
        return Objects.hash(address);
    }

}

public class Switch extends Device
{	
    private HashMap<MACAddressAndTimeout, Iface> forwardingTable;
    private static final int INITIAL_TIMEOUT = 15;
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile)
	{
		super(host,logfile);
        this.forwardingTable = new HashMap<>();
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface)
	{
		System.out.println("*** -> Received packet: " +
				etherPacket.toString().replace("\n", "\n\t"));
		
		MACAddress sourceMac = etherPacket.getSourceMAC();
        MACAddress destMac = etherPacket.getDestinationMAC();

        MACAddressAndTimeout destEntry = new MACAddressAndTimeout(destMac, INITIAL_TIMEOUT);
        if (!forwardingTable.containsKey(destEntry)) {
            // Broadcast
            for (Map.Entry<String, Iface> entry : interfaces.entrySet()) {
                Iface currInterface = entry.getValue();
                if (!currInterface.equals(inIface)) {
                    this.sendPacket(etherPacket, currInterface);
                }
            }
        }
        else {
            Iface destInterface = forwardingTable.get(destEntry);
            this.sendPacket(etherPacket, destInterface);
        }
        
        // Update forwarding table
        MACAddressAndTimeout sourceEntry = new MACAddressAndTimeout(sourceMac, INITIAL_TIMEOUT);
        if (forwardingTable.containsKey(sourceEntry)) {
            forwardingTable.remove(sourceEntry);
        }
        forwardingTable.put(new MACAddressAndTimeout(sourceMac, INITIAL_TIMEOUT), inIface);
        
        // Check expiration
        Instant currentInstant = Instant.now();
        forwardingTable.entrySet().removeIf(entry -> Instant.now().isAfter(entry.getKey().getTimeout()));
    }
}
